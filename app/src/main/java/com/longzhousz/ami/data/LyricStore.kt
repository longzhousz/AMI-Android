package com.longzhousz.ami.data

import android.os.Trace
import android.util.LruCache
import com.longzhousz.ami.core.Song
import com.longzhousz.ami.core.SongLyrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/** Shared, byte-bounded cache; favorites and unrelated imports do not invalidate lyric bodies. */
class LyricStore(private val db: LibraryDatabase, private val byteLimit: Int = 2 * 1024 * 1024) {
    private val generation = AtomicLong()
    private val cache = object : LruCache<String, SongLyrics>(byteLimit) {
        override fun sizeOf(key: String, value: SongLyrics) = value.estimatedBytes
    }
    suspend fun load(song: Song): SongLyrics = withContext(Dispatchers.IO) {
        if (!song.hasLyrics) return@withContext SongLyrics(revision = song.lyricsVersion)
        val key = "${song.id}:${song.lyricsVersion}"
        val requestGeneration = generation.get()
        cache.get(key)?.let { return@withContext it }
        ensureActive()
        Trace.beginSection("ami:load-lyrics")
        val document = try { db.songLyrics(song.id).also { it.lines } }
        finally { Trace.endSection() }
        ensureActive()
        synchronized(cache) {
            if (generation.get() == requestGeneration && document.revision == song.lyricsVersion && document.estimatedBytes <= byteLimit) cache.put(key, document)
        }
        document
    }
    fun trim() = synchronized(cache) { generation.incrementAndGet(); cache.evictAll() }
    internal val cachedBytes get() = cache.size()
}
