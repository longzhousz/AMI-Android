package com.longzhousz.ami.data

import android.content.Context
import android.net.Uri
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.longzhousz.ami.core.Song
import com.longzhousz.ami.core.LibrarySnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.io.File

data class ImportState(val running: Boolean = false, val done: Int = 0, val total: Int = 0, val message: String = "", val errors: List<String> = emptyList())

class LibraryRepository(val context: Context) {
    val db = LibraryDatabase(context)
    val secrets = SecretStore(context)
    val prefs = context.getSharedPreferences("ami", Context.MODE_PRIVATE)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val playback = PlaybackStore(db, prefs, scope)
    val songs = MutableStateFlow<List<Song>>(emptyList())
    val library = MutableStateFlow(LibrarySnapshot(emptyList()))
    val loading = MutableStateFlow(true)
    val importState = MutableStateFlow(ImportState())
    val importLock = Mutex()
    val audioDir = File(context.filesDir, "AudioCache").apply { mkdirs() }
    val coverDir = File(context.filesDir, "Covers").apply { mkdirs() }
    private val libraryRequested = java.util.concurrent.atomic.AtomicBoolean(false)

    fun load() {
        if (libraryRequested.compareAndSet(false, true)) scope.launch { try { reload() } finally { loading.value = false } }
    }
    @Synchronized fun reload() {
        // A headset, system browser or background import does not need the UI/search snapshot.
        if (!libraryRequested.get()) return
        val fresh = db.songs()
        if (fresh != library.value.songs) {
            val snapshot = LibrarySnapshot(fresh)
            library.value = snapshot
            songs.value = fresh
        }
    }
    fun roots(): Set<String> = prefs.getStringSet("roots", emptySet())!!.toSet()
    fun lyricRoots(): Set<String> = prefs.getStringSet("lyricRoots", emptySet())!!.toSet()
    fun addLyricRoot(uri: Uri) {
        prefs.edit().putStringSet("lyricRoots", lyricRoots() + uri.toString()).apply()
        scan(afterCurrent = true)
    }
    fun forgetLyricRoot(uri: String) { prefs.edit().putStringSet("lyricRoots", lyricRoots() - uri).apply() }
    fun addRoot(uri: Uri) {
        prefs.edit().putStringSet("roots", roots() + uri.toString()).apply()
        scan(afterCurrent = true)
    }
    fun forgetRoot(uri: String) { prefs.edit().putStringSet("roots", roots() - uri).apply() }
    fun scan(afterCurrent: Boolean = false) {
        val policy = if (afterCurrent) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP
        WorkManager.getInstance(context).enqueueUniqueWork("library-import", policy, OneTimeWorkRequestBuilder<ImportWorker>().build())
    }
    fun cancelScan() { WorkManager.getInstance(context).cancelUniqueWork("library-import") }
    suspend fun favorite(song: Song) { db.favorite(song.id, !song.favorite); reload() }
    suspend fun reorder(ids: List<String>) { db.reorder(ids); reload() }
    suspend fun remove(song: Song) { db.remove(song.id); reload() }

    data class CacheInfo(val bytes: Long, val files: Int, val reclaimable: Long)
    fun cacheInfo(): CacheInfo {
        val referenced = referencedFiles()
        val files = listOf(audioDir, coverDir).flatMap { it.listFiles()?.toList().orEmpty() }.filter { it.isFile }
        return CacheInfo(files.sumOf { it.length() }, files.size, files.filter { it.canonicalPath !in referenced }.sumOf { it.length() })
    }
    fun cleanup(): Long {
        check(!importState.value.running) { "请等待导入完成" }
        val referenced = referencedFiles()
        var freed = 0L
        listOf(audioDir, coverDir).forEach { dir -> dir.listFiles()?.filter { it.isFile && it.canonicalPath !in referenced }?.forEach {
            val size = it.length(); if (it.delete()) freed += size
        } }
        return freed
    }
    private fun referencedFiles(): Set<String> = db.cachedFileReferences().map { File(it).canonicalPath }.toSet()
}
