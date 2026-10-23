package com.longzhousz.ami.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class NeteaseLyricsTest {
    @Test fun filenamesAndNcmMetadataUseExactSongIdsWithout32BitTruncation() {
        assertEquals("1234567890123", NeteaseLyrics.idFromFilename("1234567890123"))
        assertEquals("1234567890123", NeteaseLyrics.idFromFilename("1234567890123.LRC"))
        assertEquals("1234567890123", NeteaseLyrics.idFromMetadata("{\"musicId\":1234567890123}"))
        listOf("Track 01", "123x", "000123", "-123", "123.mp3", "0").forEach { assertNull(NeteaseLyrics.idFromFilename(it)) }
    }
    @Test fun userCacheWithNegativeJsonCreditsAndOrdinaryLrcDecodesAndAlignsTranslation() {
        val original = """
            {"t":-1000,"c":[{"tx":"作词: "},{"tx":"n-buna","li":"http://example.com/cover.jpg","or":"orpheus://nm/artist/home?id=981185"}]}
            {"t":-500,"c":[{"tx":"作曲: "},{"tx":"n-buna"}]}
            [00:00.00]
            [00:00.430]最低限の生活で小さな部屋の六畳で
            [00:05.680]君と暮らせれば良かった
        """.trimIndent()
        val json = JSONObject().put("briefDesc", "").put("karaokeLyric", "").put("karaokeVersion", 0)
            .put("lrc", original).put("translatedLyric", "[00:00.430]在小小房间里生活\n[00:05.680]若能与你生活就好").toString()
        val doc = NeteaseLyrics.decode(json.toByteArray())
        val lines = Lyrics.parse(doc.original)
        assertEquals(listOf(0L, 0L, 430L, 5680L), lines.map { it.time })
        assertEquals(listOf("作词: n-buna", "作曲: n-buna"), lines.take(2).map { it.text })
        assertEquals(listOf("", "", "在小小房间里生活", "若能与你生活就好"), NeteaseLyrics.translations(lines, doc.translated))
        assertFalse(doc.original.contains("orpheus://"))
    }
    @Test fun nestedAndDirectLyricJsonAndStandaloneJsonLinesAreAccepted() {
        val lrc = "[00:01.230]Hello"
        val nested = JSONObject().put("lrc", JSONObject().put("lyric", lrc)).put("tlyric", JSONObject().put("lyric", "[00:01.230]你好"))
        assertEquals(lrc, NeteaseLyrics.decode(nested.toString()).original)
        assertEquals("[00:01.230]你好", NeteaseLyrics.decode(nested.toString()).translated)
        assertEquals(lrc, NeteaseLyrics.decode(JSONObject().put("lyric", lrc).toString()).original)
        assertEquals("[00:01.230]Hello world", NeteaseLyrics.decode("{\"t\":1230,\"c\":[{\"tx\":\"Hello \"},{\"tx\":\"world\"}]}").original)
    }
    @Test fun plainLrcHandlesUtf16AndLegacyChineseEncoding() {
        assertEquals("[00:01]你好", NeteaseLyrics.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "[00:01]你好".toByteArray(Charsets.UTF_16LE)).original)
        assertEquals("[00:01]你好", NeteaseLyrics.decode("[00:01]你好".toByteArray(charset("GB18030"))).original)
        assertEquals("[00:01]Hello", NeteaseLyrics.decode("\uFEFF[00:01]Hello\r\n").original)
    }
    @Test fun wordTimedLyricsFallBackToAccurateLineTimestamps() {
        val doc = NeteaseLyrics.decode("[1230,800](1230,400,0)Hello (1630,400,0)world")
        assertEquals(listOf(LyricLine(1230, "Hello world")), Lyrics.parse(doc.original))
    }
    @Test fun invalidOrOversizedCachesDoNotBecomeVisibleLyrics() {
        listOf("", "{\"code\":200}", "{broken json", "{\"lrc\":\"{broken\"}", "[ar:Artist]", "[00:00]a\u0000b", "[".repeat(33)).forEach {
            assertTrue("Must reject $it", runCatching { NeteaseLyrics.decode(it) }.isFailure)
        }
        assertTrue(runCatching { NeteaseLyrics.decode(ByteArray(2097153)) }.isFailure)
    }
    @Test fun id3CommentsDecodeNeteaseKeysForUtf8Utf16AndV24() {
        val comment = key("1234567890123")
        for (version in listOf(3, 4)) for (encoding in listOf(1, 3)) {
            val text = ("\u0000" + comment).toByteArray(if (encoding == 1) Charsets.UTF_16 else Charsets.UTF_8)
            val payload = byteArrayOf(encoding.toByte()) + "eng".toByteArray() + text
            val frame = "COMM".toByteArray() + size(payload.size, version == 4) + byteArrayOf(0, 0) + payload
            val tag = "ID3".toByteArray() + byteArrayOf(version.toByte(), 0, 0) + size(frame.size, true) + frame
            assertEquals("1234567890123", NeteaseMetadata.read(ByteArrayInputStream(tag)))
        }
    }
    @Test fun flacVorbisCommentsFindEncryptedKeysAndSkipArtwork() {
        val comment = ("COMMENT=" + key("9876543210")).toByteArray()
        val block = ByteArrayOutputStream().apply {
            write(le(0)); write(le(1)); write(le(comment.size)); write(comment)
        }.toByteArray()
        val artwork = ByteArray(3000000)
        val flac = "fLaC".toByteArray() + byteArrayOf(6) + size(artwork.size, false).takeLast(3).toByteArray() + artwork +
            byteArrayOf(0x84.toByte()) + size(block.size, false).takeLast(3).toByteArray() + block
        assertEquals("9876543210", NeteaseMetadata.read(ByteArrayInputStream(flac)))
    }
    @Test fun malformedAudioCommentsStayUnknownAndUrlsRequireNeteaseProvenance() {
        assertNull(NeteaseMetadata.fromComment("track=123456"))
        assertNull(NeteaseMetadata.fromComment("163 key(Don't modify):badbase64"))
        assertNull(NeteaseMetadata.read(ByteArrayInputStream("fLaC".toByteArray())))
        assertEquals("123456", NeteaseMetadata.fromComment("https://music.163.com/#/song?id=123456&userid=1"))
    }
    private fun key(id: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec("#14ljk_!\\]&0U<'(".toByteArray(), "AES"))
        return "163 key(Don't modify):" + Base64.getEncoder().encodeToString(cipher.doFinal("music:{\"musicId\":$id}".toByteArray()))
    }
    private fun size(value: Int, safe: Boolean): ByteArray = ByteArray(4) { index -> ((value ushr ((3 - index) * if (safe) 7 else 8)) and if (safe) 127 else 255).toByte() }
    private fun le(value: Int) = ByteArray(4) { index -> (value ushr (index * 8)).toByte() }
}
