package com.longzhousz.ami.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI

internal interface DeepSeekApi {
    fun models(apiKey: String): String
    fun complete(apiKey: String, body: JSONObject): String
}

/** Credentials only travel to the official origin; redirects are never followed. */
internal class DeepSeekHttpApi : DeepSeekApi {
    override fun models(apiKey: String) = request(apiKey, "/models", null)
    override fun complete(apiKey: String, body: JSONObject): String =
        JSONObject(request(apiKey, "/chat/completions", body)).getJSONArray("choices")
            .getJSONObject(0).getJSONObject("message").getString("content")

    private fun request(apiKey: String, path: String, body: JSONObject?): String {
        val connection = URI("https://api.deepseek.com$path").toURL().openConnection() as HttpURLConnection
        try {
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
        } finally { connection.disconnect() }
    }
}
