package com.longzhousz.ami.data

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.net.Uri
import android.util.AtomicFile
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import coil.memory.MemoryCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.buffer
import okio.source
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/** Only requested artwork is fetched. Original bytes are shared by covers and backdrop sampling. */
internal class OnlineArtworkCache(
    private val context: Context,
    private val dir: File = File(context.cacheDir, "OnlineArtwork"),
    private val download: suspend (String) -> ByteArray = ArtworkDownloader()::download,
    private val clock: () -> Long = System::currentTimeMillis,
    private val reserve: Long = 1024L * 1024 * 1024,
) {
    // ponytail: 16 striped locks bound memory and merge duplicate requests; widen only if contended.
    private val locks = Array(16) { Mutex() }
    private val sweptAt = AtomicLong(0)
    private val writing = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    // Small, already-seen previews survive eviction of the much larger display bitmaps.
    // Original files remain the sole disk copy; no extra downloads or thumbnail directory.
    private val previews = MemoryCache.Builder(context)
        .maxSizeBytes(minOf(16 * 1024 * 1024L, Runtime.getRuntime().maxMemory() / 20).toInt())
        .weakReferencesEnabled(false).build()
    fun preview(url: String): Bitmap? = previews[MemoryCache.Key(url)]?.bitmap
    fun clearMemory() = previews.clear()
    internal val previewBytes get() = previews.size
    internal val previewLimit get() = previews.maxSize
    private fun previewOptions(bounds: BitmapFactory.Options): BitmapFactory.Options {
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 96) sample *= 2
        return BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565 }
    }
    private fun key(url: String) = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun lock(key: String) = locks[(key.hashCode() and Int.MAX_VALUE) % locks.size]

    fun factory() = object : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            val url = ArtworkDownloader.url(data.toString()) ?: return null
            return object : Fetcher { override suspend fun fetch() = load(url) }
        }
    }
    internal suspend fun load(raw: String): SourceResult = withContext(Dispatchers.IO) {
        val url = requireNotNull(ArtworkDownloader.url(raw))
        val hash = key(url)
        val file = File(dir, "$hash.img")
        cached(file, url, hash)?.let { return@withContext it }
        lock(hash).withLock {
            cached(file, url, hash)?.let { return@withLock it }
            val bytes = download(url)
            require(bytes.size in 1..ArtworkDownloader.MAX_BYTES)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0)
            val preview = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, previewOptions(bounds)))
            previews[MemoryCache.Key(url)] = MemoryCache.Value(preview)
            // Persist best-effort; low storage must not prevent the image being displayed now.
            runCatching {
                dir.mkdirs()
                if (dir.usableSpace >= reserve + bytes.size) {
                    writing.add(hash)
                    try {
                        val atomic = AtomicFile(file)
                        val output = atomic.startWrite()
                        try { output.write(bytes); atomic.finishWrite(output); file.setLastModified(clock()) }
                        catch (error: Exception) { atomic.failWrite(output); throw error }
                    } finally { writing.remove(hash) }
                }
            }
            SourceResult(ImageSource(Buffer().write(bytes), context), bounds.outMimeType, DataSource.NETWORK)
        }
    }
    private fun cached(file: File, url: String, hash: String): SourceResult? {
        if (!file.isFile || file.length() == 0L) return null
        val source = try { file.source().buffer() } catch (_: IOException) { return null }
        // Check after opening: old Android AtomicFile versions expose their unfinished base
        // file. An open, completed file remains readable even if idle cleanup unlinks it later.
        if (hash in writing) { source.close(); return null }
        try {
            if (clock() - file.lastModified() > HOUR) file.setLastModified(clock())
            if (preview(url) == null) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                if (bounds.outWidth > 0 && bounds.outHeight > 0)
                    BitmapFactory.decodeFile(file.path, previewOptions(bounds))?.let { previews[MemoryCache.Key(url)] = MemoryCache.Value(it) }
            }
            return SourceResult(ImageSource(source, context), null, DataSource.DISK)
        } catch (error: Throwable) { source.close(); throw error }
    }
    suspend fun touch(raw: String) = withContext(Dispatchers.IO) {
        val url = ArtworkDownloader.url(raw) ?: return@withContext
        val hash = key(url)
        lock(hash).withLock {
            val file = File(dir, "$hash.img")
            if (clock() - file.lastModified() > HOUR) file.setLastModified(clock())
        }
    }
    /** Caller uses IO. Yield when the UI/playback returns; no timer or eager image prefetch. */
    fun cleanup(now: Long = clock(), force: Boolean = false, active: () -> Boolean = { true }) {
        if (!active()) return
        val previous = sweptAt.get()
        if (!force && now - previous < HOUR || !sweptAt.compareAndSet(previous, now)) return
        dir.listFiles()?.forEach { file ->
            if (!active()) { sweptAt.compareAndSet(now, 0); return }
            val hash = file.name.substringBefore('.')
            if (hash.matches(Regex("[a-f0-9]{64}")) && lock(hash).tryLock()) {
                try { if (file.extension != "img" || file.lastModified() < now - WEEK) file.delete() }
                finally { lock(hash).unlock() }
            }
        }
    }
    companion object {
        private const val HOUR = 3_600_000L
        internal const val WEEK = 7 * 86_400_000L
    }
}
