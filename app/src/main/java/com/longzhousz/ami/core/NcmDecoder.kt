package com.longzhousz.ami.core

import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** Streaming decoder for the classic CTENFDAM container. Never changes the input. */
object NcmDecoder {
    data class Header(val metadata: String?, val cover: ByteArray)

    fun decode(input: InputStream, output: OutputStream, headerOnly: Boolean = false, checkCancelled: () -> Unit = {}): Header {
        fun read(n: Int): ByteArray {
            require(n in 0..(16 * 1024 * 1024)) { "NCM 区块过大" }
            return ByteArray(n).also { data ->
                var pos = 0
                while (pos < n) {
                    val count = input.read(data, pos, n - pos)
                    require(count > 0) { "NCM 文件不完整" }
                    pos += count
                }
            }
        }
        fun int(): Int = read(4).let { bytes -> bytes.indices.fold(0) { v, i -> v or ((bytes[i].toInt() and 255) shl (8 * i)) } }
        // The legacy container mandates ECB; new app secrets use Keystore AES-GCM.
        @android.annotation.SuppressLint("GetInstance")
        fun decrypt(bytes: ByteArray, key: String): ByteArray = Cipher.getInstance("AES/ECB/PKCS5Padding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key.toByteArray(Charsets.US_ASCII), "AES")); doFinal(bytes)
        }
        require(read(8).contentEquals("CTENFDAM".toByteArray())) { "不是支持的 NCM 文件" }
        read(2)
        val keyLength = int().also { require(it in 1..4096) { "NCM 密钥长度无效" } }
        val keyData = read(keyLength).also { bytes -> bytes.indices.forEach { bytes[it] = (bytes[it].toInt() xor 0x64).toByte() } }
        val key = decrypt(keyData, "hzHRAmso5kInbaxW").drop(17).toByteArray()
        require(key.isNotEmpty()) { "NCM 密钥为空" }
        val box = IntArray(256) { it }
        var last = 0
        for (i in 0..255) {
            val c = (box[i] + last + (key[i % key.size].toInt() and 255)) and 255
            val tmp = box[i]; box[i] = box[c]; box[c] = tmp; last = c
        }
        val metaLength = int().also { require(it in 0..2097152) { "NCM 元数据过大" } }
        val meta = read(metaLength).also { bytes -> bytes.indices.forEach { bytes[it] = (bytes[it].toInt() xor 0x63).toByte() } }
        val metadata = if (meta.isEmpty()) null else {
            require(meta.size > 22) { "NCM 元数据无效" }
            val encrypted = Base64.getDecoder().decode(meta.copyOfRange(22, meta.size))
            decrypt(encrypted, "#14ljk_!\\]&0U<'(").toString(Charsets.UTF_8).substringAfter("music:")
        }
        read(4) // CRC
        read(5) // reserved fields
        val cover = read(int())
        if (headerOnly) return Header(metadata, cover)
        val buffer = ByteArray(64 * 1024)
        var offset = 0L
        var written = 0L
        while (true) {
            checkCancelled()
            val count = input.read(buffer)
            if (count < 0) break
            for (i in 0 until count) {
                val j = ((offset + i + 1) and 255).toInt()
                val k = box[(box[j] + box[(box[j] + j) and 255]) and 255]
                buffer[i] = (buffer[i].toInt() xor k).toByte()
            }
            output.write(buffer, 0, count)
            offset += count; written += count
        }
        require(written > 0) { "NCM 音频为空" }
        return Header(metadata, cover)
    }
}
