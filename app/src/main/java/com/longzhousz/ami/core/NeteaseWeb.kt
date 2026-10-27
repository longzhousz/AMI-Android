package com.longzhousz.ami.core

import java.net.URI
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

fun neteaseId(raw: String): String = raw.also {
    require(it.matches(Regex("[1-9][0-9]{0,19}"))) { "网易云标识无效" }
}

/** Only this app's official web login cookies are accepted; never read another app's storage. */
fun neteaseCookies(raw: String): String {
    require(raw.length <= 16384 && raw.none { it == '\r' || it == '\n' || it.code < 32 }) { "登录信息无效" }
    val allowed = setOf("MUSIC_U", "__csrf", "NMTID")
    val values = linkedMapOf<String, String>()
    raw.split(';').forEach { part ->
        val name = part.substringBefore('=').trim()
        val value = part.substringAfter('=', "").trim()
        if (name in allowed && value.isNotEmpty()) values[name] = value
    }
    require(!values["MUSIC_U"].isNullOrBlank()) { "请先在官方页面完成登录" }
    return values.entries.joinToString("; ") { "${it.key}=${it.value}" }
}

fun neteaseLoginUrl(raw: String): Boolean = runCatching {
    val uri = URI(raw)
    val host = uri.host?.lowercase().orEmpty()
    uri.scheme == "https" && uri.userInfo == null && uri.port in setOf(-1, 443) &&
        (host == "163.com" || host.endsWith(".163.com") || host == "netease.com" || host.endsWith(".netease.com"))
}.getOrDefault(false)

/** Public website protocol parameters, not user credentials or a platform private key. */
internal object NeteaseForm {
    private val random = SecureRandom()
    private const val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    private const val publicKey = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDgtQn2JZ34ZC28NWYpAUd98iZ37BUrX/aKzmFbt7clFSs6sXqHauqKWqdtLkF2KexO40H1YTX8z2lSgBBOAxLsvaklV8k4cBFK9snQXE9/DDaFt6Rr7iVZMldczhC0JNgTz+SHXT6CBHuX3e9SdB1Ua44oncaTWz7OBGLbCiK45wIDAQAB"
    fun encode(json: String, key: String = buildString { repeat(16) { append(alphabet[random.nextInt(alphabet.length)]) } }): String {
        require(key.length == 16 && key.all { it in alphabet })
        fun aes(text: String, secret: String): String = Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "AES"), IvParameterSpec("0102030405060708".toByteArray()))
            Base64.getEncoder().encodeToString(doFinal(text.toByteArray(Charsets.UTF_8)))
        }
        val params = aes(aes(json, "0CoJUm6Qyw8W8jud"), key)
        val rsa = Cipher.getInstance("RSA/ECB/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(publicKey))))
            doFinal(key.reversed().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        return "params=${URLEncoder.encode(params, "UTF-8")}&encSecKey=$rsa"
    }
}
