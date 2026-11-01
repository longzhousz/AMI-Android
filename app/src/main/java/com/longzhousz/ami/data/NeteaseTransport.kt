package com.longzhousz.ami.data

import com.longzhousz.ami.core.NeteaseForm
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.net.URLDecoder
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class NeteaseLoginExpired : IllegalStateException("网易云登录已过期，请在设置中重新登录")
internal class NeteaseRequestRejected(val code: Int) : IllegalStateException("网易云接口暂时拒绝请求（$code），请稍后重试")

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
    /** Read only session/CSRF cookies from the official homepage, keeping MUSIC_U unchanged. */
    fun preheat(cookie: String): String {
        val http = open("https://music.163.com/")
        try {
            http.connectTimeout = 5000; http.readTimeout = 5000; http.instanceFollowRedirects = false
            http.setRequestProperty("Cookie", cookie)
            http.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0 Safari/537.36")
            if (http.responseCode != 200) return cookie
            val session = http.headerFields.entries.filter { it.key.equals("Set-Cookie", true) }.flatMap { it.value.orEmpty() }
                .map { it.substringBefore(';').trim() }.filter { it.substringBefore('=') in setOf("__csrf", "NMTID") }
            return com.longzhousz.ami.core.neteaseCookies((listOf(cookie) + session).joinToString("; "))
        } finally { http.disconnect() }
    }
    fun blockingAudio(data: JSONObject, cookie: String, desktop: Boolean = false): JSONObject {
        val path = "song/enhance/player/url/v1"
        val host = if (desktop) "interfacepc.music.163.com" else "interface.music.163.com"
        val http = open("https://$host/eapi/$path")
        return try { read(http, data, cookie, "/api/$path", desktop) } finally { http.disconnect() }
    }
    private fun read(http: HttpURLConnection, data: JSONObject, cookie: String, clientPath: String? = null, desktop: Boolean = false): JSONObject {
        require(cookie.length <= 16384 && cookie.none { it == '\r' || it == '\n' })
        val csrf = cookie.split(';').map { it.trim() }.firstOrNull { it.startsWith("__csrf=") }?.substringAfter('=').orEmpty()
        val payload = JSONObject(data.toString()).put("csrf_token", csrf)
        val os = if (desktop) "pc" else "android"
        // These are first-party protocol versions, independently of AMI's own version.
        val version = if (desktop) "3.1.17.204416" else "9.5.61"
        val userAgent = if (clientPath == null) "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
            else if (desktop) "NeteaseMusicDesktop/$version (Windows NT 10.0; Win64; x64)"
            else "NeteaseMusic/$version;Dalvik/2.1.0 (Linux; U; Android 14)"
        val header = if (clientPath != null) {
            JSONObject().put("os", os).put("appver", version).put("osver", if (desktop) "Microsoft-Windows-10-Professional-build-19045-64bit" else "14")
                .put("channel", "netease").put("resolution", "1920x1080").put("__csrf", csrf)
                .put("requestId", "${System.currentTimeMillis()}_${java.util.concurrent.ThreadLocalRandom.current().nextInt(10000).toString().padStart(4, '0')}").also { header ->
                    cookie.split(';').forEach { part ->
                        val name = part.substringBefore('=').trim()
                        if (name in setOf("MUSIC_U", "__csrf", "NMTID")) header.put(name,
                            URLDecoder.decode(part.substringAfter('=').trim().replace("+", "%2B"), "UTF-8"))
                    }
                    payload.put("header", header).put("e_r", false)
                }
        } else null
        http.connectTimeout = 7000; http.readTimeout = 12000; http.instanceFollowRedirects = false
        http.requestMethod = "POST"; http.doOutput = true
        http.setRequestProperty("User-Agent", userAgent)
        http.setRequestProperty("Referer", "https://music.163.com/")
        http.setRequestProperty("Accept", "application/json")
        http.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
        // EAPI sends the same context in its encrypted header and HTTP cookie. Never append
        // anonymous credentials or claim a different account. Web requests retain the web cookie.
        if (header != null) http.setRequestProperty("Cookie", header.keys().asSequence().joinToString("; ") { name ->
            "$name=${URLEncoder.encode(header.getString(name), "UTF-8")}" })
        else if (cookie.isNotBlank()) http.setRequestProperty("Cookie", cookie)
        val form = if (clientPath == null) webForm(payload.toString()) else NeteaseForm.encodeClient(clientPath, payload.toString())
        http.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
        val status = http.responseCode
        if (status == 401) throw NeteaseLoginExpired()
        if (status != 200) throw NeteaseRequestRejected(status)
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
            else -> throw NeteaseRequestRejected(result.optInt("code"))
        }
    }
}
