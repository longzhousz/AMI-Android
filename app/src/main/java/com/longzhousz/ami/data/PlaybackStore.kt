package com.longzhousz.ami.data

import android.content.SharedPreferences
import android.database.sqlite.SQLiteException
import kotlinx.coroutines.CancellationException
import com.longzhousz.ami.core.PlaybackSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** One writer per app process; position updates never rewrite the stored queue or settings. */
class PlaybackStore(private val db: LibraryDatabase, private val legacy: SharedPreferences, scope: CoroutineScope) {
    private val updates = MutableStateFlow<PlaybackSnapshot?>(null)
    private val lock = Mutex()
    private var loaded = false
    private var saved: PlaybackSnapshot? = null
    init {
        scope.launch(Dispatchers.IO) {
            updates.filterNotNull().collect { snapshot ->
                lock.withLock {
                    if (!loaded) { saved = db.playbackSnapshot(); loaded = true }
                    if (saved != snapshot) {
                        try {
                            db.savePlayback(snapshot, queueChanged = saved?.queue != snapshot.queue)
                            val previous = saved
                            saved = snapshot
                            val modeChanged = previous?.repeat != snapshot.repeat || previous?.shuffle != snapshot.shuffle
                            if (legacy.contains("queue") || modeChanged) legacy.edit()
                                .remove("queue").remove("queueIndex").remove("position")
                                .putInt("repeat", snapshot.repeat).putBoolean("shuffle", snapshot.shuffle).apply()
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: SQLiteException) { /* Keep the latest in memory; the next real checkpoint retries. */ }
                    }
                }
            }
        }
    }
    fun publish(snapshot: PlaybackSnapshot) { updates.value = snapshot }
    suspend fun load(): PlaybackSnapshot = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!loaded) { saved = db.playbackSnapshot(); loaded = true }
            if (updates.value == null) {
                val previous = saved ?: run {
                    val queue = runCatching {
                        val text = legacy.getString("queue", "[]")!!
                        require(text.length <= 8_000_000)
                        JSONArray(text).let { a -> require(a.length() <= 100_000); (0 until a.length()).map { a.getString(it) } }
                    }.getOrDefault(emptyList())
                    PlaybackSnapshot(queue, legacy.getInt("queueIndex", 0), legacy.getLong("position", 0), legacy.getInt("repeat", 2), legacy.getBoolean("shuffle", false))
                }
                updates.compareAndSet(null, previous)
            }
            updates.value!!
        }
    }
}
