package com.longzhousz.ami.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.AtomicFile
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.buffer
import okio.source
import java.io.File
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
        cleanup()
        val hash = key(url)
        lock(hash).withLock {
            val file = File(dir, "$hash.img")
            if (file.isFile && file.length() > 0) {
                file.setLastModified(clock())
                return@withLock SourceResult(ImageSource(file.source().buffer(), context), null, DataSource.DISK)
            }
            val bytes = download(url)
            require(bytes.size in 1..ArtworkDownloader.MAX_BYTES)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 64) sample *= 2
            requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })).recycle()
            // Persist best-effort; low storage must not prevent the image being displayed now.
            runCatching {
                dir.mkdirs()
                if (dir.usableSpace >= reserve + bytes.size) {
                    val atomic = AtomicFile(file)
                    val output = atomic.startWrite()
                    try { output.write(bytes); atomic.finishWrite(output); file.setLastModified(clock()) }
                    catch (error: Exception) { atomic.failWrite(output); throw error }
                }
            }
            SourceResult(ImageSource(Buffer().write(bytes), context), bounds.outMimeType, DataSource.NETWORK)
        }
    }
    suspend fun touch(raw: String) = withContext(Dispatchers.IO) {
        val url = ArtworkDownloader.url(raw) ?: return@withContext
        val hash = key(url)
        lock(hash).withLock {
            val file = File(dir, "$hash.img")
            if (clock() - file.lastModified() > HOUR) file.setLastModified(clock())
        }
    }
    /** Caller uses IO. Busy files are skipped; no timer or eager image prefetch. */
    fun cleanup(now: Long = clock(), force: Boolean = false) {
        val previous = sweptAt.get()
        if (!force && now - previous < HOUR || !sweptAt.compareAndSet(previous, now)) return
        dir.listFiles()?.forEach { file ->
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
