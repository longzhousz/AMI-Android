package com.longzhousz.ami.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.EmptyCoroutineContext

internal interface DeepSeekApi {
    suspend fun models(apiKey: String): String
    suspend fun complete(apiKey: String, body: JSONObject): String
}

/** Credentials only travel to the official origin; redirects are never followed. */
internal class DeepSeekHttpApi(private val openConnection: (String) -> HttpURLConnection = { path ->
    URI("https://api.deepseek.com$path").toURL().openConnection() as HttpURLConnection
}) : DeepSeekApi {
    private val closer = Dispatchers.IO.limitedParallelism(1)
    override suspend fun models(apiKey: String) = request(apiKey, "/models", null)
    override suspend fun complete(apiKey: String, body: JSONObject): String =
        JSONObject(request(apiKey, "/chat/completions", body)).getJSONArray("choices")
            .getJSONObject(0).getJSONObject("message").getString("content")

    private suspend fun request(apiKey: String, path: String, body: JSONObject?): String = withContext(Dispatchers.IO) {
        val connection = openConnection(path)
        suspendCancellableCoroutine { response ->
            val worker=launch {
                try { response.resume(read(connection,apiKey,body)) }
                catch (error: Exception) { response.resumeWithException(error) }
                finally { connection.disconnect() }
            }
            response.invokeOnCancellation {
                worker.cancel()
                // Closing a blocked socket must never wait on the main/UI thread.
                closer.dispatch(EmptyCoroutineContext) { runCatching { connection.disconnect() } }
            }
        }
    }
    private fun read(connection: HttpURLConnection, apiKey: String, body: JSONObject?): String {
        connection.connectTimeout = if (body == null) 5000 else 20000
        connection.readTimeout = if (body == null) 5000 else 90000
        connection.instanceFollowRedirects = false
        connection.requestMethod = if (body == null) "GET" else "POST"
        connection.setRequestProperty("Authorization", "Bearer $apiKey")
        connection.setRequestProperty("Accept", "application/json")
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        }
        val code = connection.responseCode
        check(code in 200..299) { when (code) {
            401, 403 -> "API Key 无效或没有权限，请检查后重新保存"
            402 -> "DeepSeek 余额不足"
            429 -> "请求过于频繁，请稍后再试"
            else -> "DeepSeek 请求失败（HTTP $code）"
        } }
        return connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            val buffer = CharArray(4096)
            val result = StringBuilder()
            while (true) {
                val n = reader.read(buffer)
                if (n < 0) break
                check(result.length + n <= 2_000_000) { "DeepSeek 响应过大" }
                result.append(buffer, 0, n)
            }
            result.toString()
        }
    }
}
