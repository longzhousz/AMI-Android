package com.longzhousz.ami.core

import java.io.InputStream
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** Reads ID3 comments and FLAC Vorbis comments without buffering audio or embedded artwork. */
object NeteaseMetadata {
    private const val marker = "163 key(Don't modify):"
    private val songUrl = Regex("https?://music\\.163\\.com/(?:#/)?song\\?(?:[^\\s\u0000]*&)?id=([1-9][0-9]{0,19})(?:[^0-9]|$)")
    fun fromComment(comment: String): String? {
        if (comment.length > 2 * 1024 * 1024) return null
        val start = comment.indexOf(marker)
        if (start >= 0) return runCatching {
            val payload = comment.substring(start + marker.length).takeWhile { it.isLetterOrDigit() || it in "+/=" }
            val encrypted = Base64.getDecoder().decode(payload)
            @android.annotation.SuppressLint("GetInstance")
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec("#14ljk_!\\]&0U<'(".toByteArray(Charsets.US_ASCII), "AES"))
            val raw = cipher.doFinal(encrypted).toString(Charsets.UTF_8)
            require(raw.startsWith("music:"))
            NeteaseLyrics.idFromMetadata(raw.removePrefix("music:"))
        }.getOrNull()
        return songUrl.find(comment)?.groupValues?.get(1)?.let(NeteaseLyrics::songId)
    }
    fun read(input: InputStream): String? = runCatching {
        val header = input.readExact(4)
        when {
            header.contentEquals("fLaC".toByteArray()) -> flac(input)
            header.copyOfRange(0, 3).contentEquals("ID3".toByteArray()) -> id3(input, header[3].toInt() and 255)
            else -> null
        }
    }.getOrNull()
    private fun id3(input: InputStream, version: Int): String? {
        if (version !in 2..4) return null
        val rest = input.readExact(6)
        val flags = rest[1].toInt() and 255
        var remaining = synchsafe(rest.copyOfRange(2, 6))
        require(remaining in 0..(128 * 1024 * 1024))
        // Unsupported global unsynchronization/compression: leave ID unknown rather than guessing.
        if (flags and 0x80 != 0 || version == 2 && flags and 0x40 != 0) return null
        if (version >= 3 && flags and 0x40 != 0) {
            val sizeBytes = input.readExact(4)
            val size = if (version == 4) synchsafe(sizeBytes) else be(sizeBytes)
            val skip = if (version == 4) size - 4 else size
            require(skip >= 0 && skip <= remaining - 4)
            input.skipExact(skip); remaining -= skip + 4
        }
        val headerSize = if (version == 2) 6 else 10
        while (remaining >= headerSize) {
            val frame = input.readExact(headerSize); remaining -= headerSize
            if (frame[0].toInt() == 0) break
            val nameSize = if (version == 2) 3 else 4
            val name = frame.copyOfRange(0, nameSize).toString(Charsets.US_ASCII)
            val sizeBytes = frame.copyOfRange(nameSize, nameSize + if (version == 2) 3 else 4)
            val size = if (version == 4) synchsafe(sizeBytes) else be(sizeBytes)
            require(size in 0..remaining)
            val frameFlags = if (version == 2) 0 else frame[9].toInt() and 255
            val unsupported = if (version == 3) frameFlags and 0xE0 != 0 else frameFlags and 0x4D != 0
            if (name in setOf("COMM", "COM", "TXXX", "TXX", "WXXX", "WXX") && size in 1..2097152 && !unsupported) {
                var bytes = input.readExact(size)
                if (version == 4 && frameFlags and 2 != 0) bytes = bytes.filterIndexed { i, b -> !(b.toInt() == 0 && i > 0 && bytes[i - 1] == 0xFF.toByte()) }.toByteArray()
                val offset = if (name == "COMM" || name == "COM") 4 else 1
                if (bytes.size > offset) {
                    val charset = when (bytes[0].toInt()) { 1 -> Charsets.UTF_16; 2 -> Charsets.UTF_16BE; 3 -> Charsets.UTF_8; else -> Charsets.ISO_8859_1 }
                    fromComment(bytes.copyOfRange(offset, bytes.size).toString(charset))?.let { return it }
                }
            } else input.skipExact(size)
            remaining -= size
        }
        return null
    }
    private fun flac(input: InputStream): String? {
        repeat(128) {
            val header = input.readExact(4)
            val type = header[0].toInt() and 127
            val size = be(header.copyOfRange(1, 4))
            if (type == 4 && size <= 2097152) {
                val bytes = input.readExact(size)
                var offset = 0
                fun length(): Int {
                    require(offset + 4 <= bytes.size)
                    var value = 0L
                    repeat(4) { index -> value = value or ((bytes[offset++].toLong() and 255) shl (8 * index)) }
                    require(value <= bytes.size)
                    return value.toInt()
                }
                val vendorLength = length(); require(vendorLength <= bytes.size - offset); offset += vendorLength
                val count = length(); require(count <= 10000)
                repeat(count) {
                    val length = length(); require(length <= bytes.size - offset)
                    val comment = bytes.copyOfRange(offset, offset + length).toString(Charsets.UTF_8); offset += length
                    fromComment(comment)?.let { return it }
                }
            } else input.skipExact(size)
            if (header[0].toInt() and 128 != 0) return null
        }
        return null
    }
    private fun be(bytes: ByteArray): Int = bytes.fold(0L) { value, b -> (value shl 8) or (b.toLong() and 255) }.also { require(it <= Int.MAX_VALUE) }.toInt()
    private fun synchsafe(bytes: ByteArray): Int { require(bytes.all { it.toInt() and 128 == 0 }); return bytes.fold(0) { v, b -> (v shl 7) or (b.toInt() and 127) } }
    private fun InputStream.readExact(size: Int): ByteArray = ByteArray(size).also { bytes ->
        var offset = 0
        while (offset < size) { val count = read(bytes, offset, size - offset); require(count > 0); offset += count }
    }
    private fun InputStream.skipExact(size: Int) {
        var remaining = size.toLong()
        while (remaining > 0) { val skipped = skip(remaining); if (skipped > 0) remaining -= skipped else { require(read() >= 0); remaining-- } }
    }
}
