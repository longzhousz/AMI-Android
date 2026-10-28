package com.longzhousz.ami.data

import com.longzhousz.ami.core.NeteaseForm
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class NeteaseLoginExpired : IllegalStateException("网易云登录或访问权限已失效，请重新登录")

/** Fixed first-party API host. Cookies never reach CDN, redirect, or third-party servers. */
internal class NeteaseTransport(private val webForm: (String) -> String = { NeteaseForm.encode(it) },
    private val open: (String) -> HttpURLConnection = { URI(it).toURL().openConnection() as HttpURLConnection }) {
    private val closer = Dispatchers.IO.limitedParallelism(1)
    private fun connection(path: String, cookie: String): HttpURLConnection {
        require(path.matches(Regex("[a-z0-9/]+")) && !path.contains("//"))
        val csrf = cookie.split(';').map { it.trim() }.firstOrNull { it.startsWith("__csrf=") }?.substringAfter('=').orEmpty()
        return open("https://music.163.com/weapi/$path?csrf_token=${URLEncoder.encode(csrf, "UTF-8")}")
    }
    suspend fun post(path: String, data: JSONObject, cookie: String): JSONObject = withContext(Dispatchers.IO) {
        val http = connection(path, cookie)
        suspendCancellableCoroutine { continuation ->
            val worker = CoroutineScope(continuation.context).launch {
                try { val result = read(http, data, cookie); if (continuation.isActive) continuation.resume(result) }
                catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) }
                finally { http.disconnect() }
            }
            continuation.invokeOnCancellation { worker.cancel(); closer.dispatch(EmptyCoroutineContext) { runCatching { http.disconnect() } } }
        }
    }
    fun blockingPost(path: String, data: JSONObject, cookie: String): JSONObject {
        val http = connection(path, cookie)
        return try { read(http, data, cookie) } finally { http.disconnect() }
    }
    fun blockingAudio(data: JSONObject, cookie: String): JSONObject {
        val path = "song/enhance/player/url/v1"
        val http = open("https://interface.music.163.com/eapi/$path")
        return try { read(http, data, cookie, "/api/$path") } finally { http.disconnect() }
    }
    private fun read(http: HttpURLConnection, data: JSONObject, cookie: String, clientPath: String? = null): JSONObject {
        require(cookie.length <= 16384 && cookie.none { it == '\r' || it == '\n' })
        val csrf = cookie.split(';').map { it.trim() }.firstOrNull { it.startsWith("__csrf=") }?.substringAfter('=').orEmpty()
        val payload = JSONObject(data.toString()).put("csrf_token", csrf)
        if (clientPath != null) {
            val header = JSONObject().put("os", "android").put("appver", com.longzhousz.ami.BuildConfig.VERSION_NAME)
                .put("requestId", "${System.currentTimeMillis()}_${java.util.concurrent.ThreadLocalRandom.current().nextInt(1000)}")
            cookie.split(';').forEach { part ->
                val name = part.substringBefore('=').trim()
                if (name in setOf("MUSIC_U", "__csrf", "NMTID")) header.put(name, part.substringAfter('=').trim())
            }
            payload.put("header", header).put("e_r", false)
        }
        http.connectTimeout = 7000; http.readTimeout = 12000; http.instanceFollowRedirects = false
        http.requestMethod = "POST"; http.doOutput = true
        http.setRequestProperty("User-Agent", "AMI/${com.longzhousz.ami.BuildConfig.VERSION_NAME}")
        http.setRequestProperty("Referer", "https://music.163.com/")
        http.setRequestProperty("Accept", "application/json")
        http.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        if (cookie.isNotBlank()) http.setRequestProperty("Cookie", cookie)
        val form = if (clientPath == null) webForm(payload.toString()) else NeteaseForm.encodeClient(clientPath, payload.toString())
        http.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
        val status = http.responseCode
        if (status in setOf(401, 403)) throw NeteaseLoginExpired()
        check(status == 200) { "网易云暂时无法连接，请稍后重试（$status）" }
        val deadline = System.nanoTime() + 15_000_000_000L
        val text = http.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            val body = StringBuilder(); val buffer = CharArray(4096)
            while (true) {
                check(System.nanoTime() < deadline) { "网易云请求超时，请重试" }
                val count = reader.read(buffer); if (count < 0) break
                require(body.length + count <= 2_000_000) { "网易云响应过大" }
                body.append(buffer, 0, count)
            }
            body.toString()
        }
        val result = try { JSONObject(text) } catch (_: Exception) { error("网易云返回异常，请稍后重试") }
        when (result.optInt("code")) {
            200 -> return result
            301, 302, 401 -> throw NeteaseLoginExpired()
            else -> error("网易云暂时无法完成请求（${result.optInt("code")}）")
        }
    }
}
