package com.longzhousz.ami.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longzhousz.ami.core.PlaybackSnapshot
import com.longzhousz.ami.core.Song
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun scenario(test: suspend (LibraryDatabase, android.content.SharedPreferences, PlaybackStore) -> Unit) = runBlocking {
        val name = "qa-playback-store-${System.nanoTime()}"
        val db = LibraryDatabase(context, name)
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val job = SupervisorJob()
        val store = PlaybackStore(db, prefs, CoroutineScope(job + Dispatchers.IO))
        try { test(db, prefs, store) }
        finally { job.cancelAndJoin(); db.close(); context.deleteDatabase(name); context.deleteSharedPreferences(name) }
    }
    private suspend fun saved(db: LibraryDatabase, value: PlaybackSnapshot) = withTimeout(20000) {
        while (db.playbackSnapshot() != value) delay(10)
    }
    private fun changes(db: LibraryDatabase): Long = db.transaction {
        db.writableDatabase.rawQuery("SELECT total_changes()", null).use { c -> c.moveToFirst(); c.getLong(0) }
    }
    @Test fun positionCheckpointDoesNotRewriteA2000SongQueue() = scenario { db, _, store ->
        val snapshot = PlaybackSnapshot((0 until 2000).map { "song-$it" }, 1500, 0)
        store.publish(snapshot); saved(db, snapshot)
        val before = changes(db)
        val next = snapshot.copy(position = 1250)
        store.publish(next); saved(db, next)
        assertEquals("Only the checkpoint row should change", 1L, changes(db) - before)
        val after = changes(db)
        repeat(8) { store.publish(next) }
        delay(100)
        assertEquals(after, changes(db))
    }
    @Test fun legacyQueueMigratesWithoutLosingPositionOrModes() = scenario { db, prefs, store ->
        prefs.edit().putString("queue", "[\"a\",\"b\"]").putInt("queueIndex", 1).putLong("position", 1200).putInt("repeat", 1).putBoolean("shuffle", true).commit()
        val expected = PlaybackSnapshot(listOf("a", "b"), 1, 1200, 1, true)
        assertEquals(expected, store.load()); saved(db, expected)
        val changed = expected.copy(repeat = 0, shuffle = false)
        store.publish(changed); saved(db, changed)
        withTimeout(20000) { while (prefs.getInt("repeat", -1) != 0 || prefs.getBoolean("shuffle", true)) delay(10) }
        assertFalse(prefs.contains("queue"))
    }
    @Test fun clearingQueueCannotResurrectLegacyDataOnRestart() = scenario { db, prefs, store ->
        val first = PlaybackSnapshot(listOf("a"), 0, 10)
        store.publish(first); saved(db, first)
        val empty = PlaybackSnapshot(repeat = 1, shuffle = true)
        store.publish(empty); saved(db, empty)
        prefs.edit().putString("queue", "[\"old\"]").apply()
        assertEquals(empty, db.playbackSnapshot())
        val restart = SupervisorJob()
        try {
            val reopened = PlaybackStore(db, prefs, CoroutineScope(restart + Dispatchers.IO))
            assertEquals(empty, reopened.load())
        } finally { restart.cancelAndJoin() }
    }
    @Test fun versionTwoUpgradePreservesSongsFavoritesAndTranslations() = scenario { db, _, _ ->
        val song = Song("upgrade", "file:///unused", folder = "test", title = "Preserved")
        db.upsert(song); db.favorite(song.id, true); db.cacheTranslation("translation", "[\"保留译文\"]")
        val path = db.readableDatabase.path
        db.writableDatabase.execSQL("DROP TABLE scan_cache")
        db.writableDatabase.execSQL("DROP TABLE playback_queue")
        db.writableDatabase.execSQL("DROP TABLE playback_checkpoint")
        db.writableDatabase.execSQL("DROP INDEX songs_import_order")
        db.writableDatabase.version = 2
        db.close()
        assertTrue(db.songs().single { it.id == song.id }.favorite)
        assertEquals("[\"保留译文\"]", db.translation("translation"))
        assertTrue(db.scanRecords().isEmpty())
        assertNull(db.playbackSnapshot())
        assertEquals(3, db.readableDatabase.version)
        assertEquals(path, db.readableDatabase.path)
    }
}
