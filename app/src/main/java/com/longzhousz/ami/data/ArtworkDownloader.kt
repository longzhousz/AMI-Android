package com.longzhousz.ami.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.SocketTimeoutException
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** NCM artwork is untrusted input. Only NetEase image hosts are contacted, without credentials. */
internal class ArtworkDownloader(private val openConnection: (String) -> HttpURLConnection = {
    URI(it).toURL().openConnection() as HttpURLConnection
}) {
    companion object {
        internal const val MAX_BYTES = 8 * 1024 * 1024
        fun url(raw: String?): String? = runCatching {
            if (raw.isNullOrBlank() || raw.length > 2048) return null
            val uri = URI(raw.trim())
            val host = uri.host?.lowercase(java.util.Locale.ROOT) ?: return null
            if (uri.scheme?.lowercase(java.util.Locale.ROOT) !in setOf("http", "https") ||
                !host.matches(Regex("p[0-9]+\\.music\\.126\\.net")) || uri.userInfo != null ||
                uri.port !in setOf(-1, 443) || uri.rawPath.isNullOrEmpty()) return null
            URI("https://$host${uri.rawPath}${uri.rawQuery?.let { "?$it" }.orEmpty()}").toASCIIString()
        }.getOrNull()
    }

    private val closer = Dispatchers.IO.limitedParallelism(1)
    suspend fun download(raw: String): ByteArray = withContext(Dispatchers.IO) {
        val connection = openConnection(requireNotNull(url(raw)) { "不支持的封面地址" })
        suspendCancellableCoroutine { response ->
            val worker = launch {
                try {
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    connection.instanceFollowRedirects = false
                    connection.requestMethod = "GET"
                    connection.setRequestProperty("Accept", "image/*")
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("封面服务暂不可用")
                    if (connection.contentType?.substringBefore(';')?.startsWith("image/", ignoreCase = true) != true)
                        throw IOException("封面响应不是图片")
                    if (connection.contentLengthLong > MAX_BYTES) throw IOException("封面图片过大")
                    val deadline = System.nanoTime() + 15_000_000_000L
                    val bytes = connection.inputStream.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            ensureActive()
                            if (System.nanoTime() > deadline) throw SocketTimeoutException("封面下载超时")
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (output.size() + count > MAX_BYTES) throw IOException("封面图片过大")
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray().also { if (it.isEmpty()) throw IOException("封面图片为空") }
                    }
                    response.resume(bytes)
                } catch (error: Exception) { response.resumeWithException(error) }
                finally { connection.disconnect() }
            }
            response.invokeOnCancellation {
                worker.cancel()
                closer.dispatch(EmptyCoroutineContext) { runCatching { connection.disconnect() } }
            }
        }
    }
}
