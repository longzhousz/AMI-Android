package com.longzhousz.ami.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** An original is larger than the display budget; the caller may retry with a CDN resize. */
internal class ArtworkTooLarge : IOException("封面图片过大")

/** NCM artwork is untrusted input. Only NetEase image hosts are contacted, without credentials. */
internal class ArtworkDownloader(
    private val openConnection: (String) -> HttpURLConnection = {
        URI(it).toURL().openConnection() as HttpURLConnection
    },
) {
    companion object {
        internal const val MAX_BYTES = 8 * 1024 * 1024

        fun url(raw: String?): String? =
            runCatching {
                if (raw.isNullOrBlank() || raw.length > 2048) return null
                val uri = URI(raw.trim())
                val host = uri.host?.lowercase(java.util.Locale.ROOT) ?: return null
                if (uri.scheme?.lowercase(java.util.Locale.ROOT) !in setOf("http", "https") ||
                    !host.matches(Regex("p[0-9]+\\.music\\.126\\.net")) || uri.userInfo != null ||
                    uri.port !in setOf(-1, 443) || uri.rawPath.isNullOrEmpty()
                ) {
                    return null
                }
                URI("https://$host${uri.rawPath}${uri.rawQuery?.let { "?$it" }.orEmpty()}").toASCIIString()
            }.getOrNull()

        /** NetEase CDN shards and resize variants refer to the same original image. */
        fun originalUrl(raw: String?): String? =
            url(raw)?.let {
                val uri = URI(it)
                val query =
                    uri.rawQuery
                        ?.split('&')
                        ?.filterNot { part -> part.substringBefore('=').equals("param", ignoreCase = true) }
                        ?.joinToString("&")
                        ?.takeIf(String::isNotEmpty)
                "https://${uri.host}${uri.rawPath}${query?.let { value -> "?$value" }.orEmpty()}"
            }

        fun sharedUrl(raw: String?): String? =
            originalUrl(raw)?.let {
                val uri = URI(it)
                "https://p1.music.126.net${uri.rawPath}${uri.rawQuery?.let { value -> "?$value" }.orEmpty()}"
            }

        /** Oversized originals still get a cover: a bounded CDN resize keeps the same cache key. */
        fun scaledUrl(raw: String?): String? =
            originalUrl(raw)?.let { base ->
                val separator = if (base.contains('?')) '&' else '?'
                url("$base${separator}param=800y800")
            }
    }

    private val closer = Dispatchers.IO.limitedParallelism(1)

    suspend fun download(raw: String): ByteArray =
        withContext(Dispatchers.IO) {
            val connection = openConnection(requireNotNull(url(raw)) { "不支持的封面地址" })
            suspendCancellableCoroutine { response ->
                val worker =
                    launch {
                        try {
                            connection.connectTimeout = 5000
                            connection.readTimeout = 5000
                            connection.instanceFollowRedirects = false
                            connection.requestMethod = "GET"
                            connection.setRequestProperty("Accept", "image/*")
                            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("封面服务暂不可用")
                            if (connection.contentType?.substringBefore(';')?.startsWith("image/", ignoreCase = true) != true) {
                                throw IOException("封面响应不是图片")
                            }
                            if (connection.contentLengthLong > MAX_BYTES) throw ArtworkTooLarge()
                            val deadline = System.nanoTime() + 15_000_000_000L
                            val bytes =
                                connection.inputStream.use { input ->
                                    val output = ByteArrayOutputStream()
                                    val buffer = ByteArray(8192)
                                    while (true) {
                                        ensureActive()
                                        if (System.nanoTime() > deadline) throw SocketTimeoutException("封面下载超时")
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        if (output.size() + count > MAX_BYTES) throw ArtworkTooLarge()
                                        output.write(buffer, 0, count)
                                    }
                                    output.toByteArray().also { if (it.isEmpty()) throw IOException("封面图片为空") }
                                }
                            response.resume(bytes)
                        } catch (error: Exception) {
                            response.resumeWithException(error)
                        } finally {
                            connection.disconnect()
                        }
                    }
                response.invokeOnCancellation {
                    worker.cancel()
                    closer.dispatch(EmptyCoroutineContext) { runCatching { connection.disconnect() } }
                }
            }
        }
}
