package com.longzhousz.ami.core

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class MusicCoreTest {
    @Test fun lrcHandlesBomOffsetRepeatedTimesAndPlainText() {
        val lines = Lyrics.parse("\uFEFF[ar:Artist]\n[offset:-250]\n[01:02.3][02:03.456]Echo\n[00:00.10]Start\nPlain words")
        assertEquals(listOf(0L, 62050L, 123206L, null), lines.map { it.time })
        assertEquals(listOf("Start", "Echo", "Echo", "Plain words"), lines.map { it.text })
        assertEquals(-1, Lyrics.active(Lyrics.parse("[00:10]Hello"), 9999))
        assertEquals(1, Lyrics.active(lines, 62050))
    }
    @Test fun cueUses75FramesAndPreservesMultipleFiles() {
        val sheet = Cue.parse("""
            TITLE "Album"
            PERFORMER "Various"
            FILE "disc one.flac" WAVE
              TRACK 01 AUDIO
                TITLE "First"
                INDEX 01 00:00:00
              TRACK 02 AUDIO
                PERFORMER "Guest"
                TITLE "Second"
                INDEX 00 03:00:00
                INDEX 01 03:01:37
            FILE "disc two.flac" WAVE
              TRACK 03 AUDIO
                TITLE "Third"
                INDEX 01 00:00:00
        """.trimIndent())
        assertEquals("Album", sheet.title)
        assertEquals(181493L, sheet.tracks[1].start)
        assertEquals("Various", sheet.tracks[0].artist)
        assertEquals("Guest", sheet.tracks[1].artist)
        assertEquals("disc two.flac", sheet.tracks[2].file)
        assertEquals(0L, sheet.tracks[2].start)
    }
    @Test(expected = IllegalArgumentException::class) fun cueRejectsMissingIndex() { Cue.parse("FILE \"a.flac\" WAVE\nTRACK 01 AUDIO\nTITLE \"Lost\"") }
    @Test(expected = IllegalArgumentException::class) fun cueRejectsDescendingTimes() { Cue.parse("FILE a.flac WAVE\nTRACK 01 AUDIO\nINDEX 01 00:10:00\nTRACK 02 AUDIO\nINDEX 01 00:05:00") }
    @Test fun albumsKeepCompilationsTogetherAndSeparateUnrelatedFolders() {
        fun song(id: String, artist: String, folder: String, albumArtist: String = "") = Song(id, "content://$id", folder = folder, title = id, artist = artist, album = "Album", albumArtist = albumArtist)
        val grouped = albumsOf(listOf(song("1", "A", "folder-a"), song("2", "B", "folder-a"), song("3", "A", "folder-b")))
        assertEquals(2, grouped.size)
        assertEquals("A、B", grouped.first { it.songs.size == 2 }.artist)
        assertEquals(1, albumsOf(listOf(song("1", "A", "a/CD1", "Artist"), song("2", "B", "b/CD2", "Artist"))).size)
    }
    @Test fun manualOrderWinsOverDiscTrackTags() {
        val a = Song("a", "a", folder = "f", title = "A", track = 1, disc = 1, order = 1)
        val b = Song("b", "b", folder = "f", title = "B", track = 9, disc = 2, order = 0)
        assertEquals(listOf("b", "a"), albumsOf(listOf(a, b)).single().songs.map { it.id })
    }
    @Test fun classicNcmDecodesAcrossStreamChunksWithMetadataAndArtwork() {
        val audio = ByteArray(150003) { ((it * 17 + 9) % 256).toByte() }
        val cover = byteArrayOf(1, 3, 7, 9)
        val fixture = ncm(audio, cover)
        val output = ByteArrayOutputStream()
        val shortReads = object : java.io.FilterInputStream(ByteArrayInputStream(fixture)) {
            override fun read(bytes: ByteArray, offset: Int, length: Int) = super.read(bytes, offset, minOf(length, 137))
        }
        val result = NcmDecoder.decode(shortReads, output)
        assertArrayEquals(audio, output.toByteArray())
        assertArrayEquals(cover, result.cover)
        assertEquals("{\"musicName\":\"Test\"}", result.metadata)
    }
    @Test(expected = IllegalArgumentException::class) fun ncmRejectsTruncatedInput() { NcmDecoder.decode(ByteArrayInputStream("CTENFDAM".toByteArray()), ByteArrayOutputStream()) }
    @Test(expected = IllegalArgumentException::class) fun ncmRejectsUnboundedAllocation() {
        val bytes = "CTENFDAM".toByteArray() + byteArrayOf(0, 0, -1, -1, -1, 127)
        NcmDecoder.decode(ByteArrayInputStream(bytes), ByteArrayOutputStream())
    }
    @Test fun ncmHonorsCancellationDuringStreaming() {
        var cancelled = false
        try { NcmDecoder.decode(ByteArrayInputStream(ncm(ByteArray(100000), byteArrayOf())), ByteArrayOutputStream()) { throw InterruptedException() } }
        catch (_: InterruptedException) { cancelled = true }
        assertTrue(cancelled)
    }
    @Test fun ncmCanRefreshMetadataWithoutRewritingCachedAudio() {
        val output = ByteArrayOutputStream()
        val header = NcmDecoder.decode(ByteArrayInputStream(ncm(ByteArray(100000), byteArrayOf(4, 5))), output, headerOnly = true)
        assertArrayEquals(byteArrayOf(4, 5), header.cover)
        assertEquals(0, output.size())
    }

    private fun ncm(audio: ByteArray, cover: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        fun int(value: Int) { repeat(4) { output.write((value ushr (8 * it)) and 255) } }
        fun encrypt(bytes: ByteArray, key: String) = Cipher.getInstance("AES/ECB/PKCS5Padding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(), "AES")); doFinal(bytes)
        }
        output.write("CTENFDAM".toByteArray()); output.write(byteArrayOf(0, 0))
        val seed = "test-key-material".toByteArray()
        val keyData = encrypt("neteasecloudmusic".toByteArray() + seed, "hzHRAmso5kInbaxW").map { (it.toInt() xor 0x64).toByte() }.toByteArray()
        int(keyData.size); output.write(keyData)
        val meta = "163 key(Don't modify):".toByteArray() + Base64.getEncoder().encode(encrypt("music:{\"musicName\":\"Test\"}".toByteArray(), "#14ljk_!\\]&0U<'("))
        val encoded = meta.map { (it.toInt() xor 0x63).toByte() }.toByteArray()
        int(encoded.size); output.write(encoded); output.write(ByteArray(9)); int(cover.size); output.write(cover)
        val state = IntArray(256) { it }
        var j = 0
        repeat(256) { i -> j = (j + state[i] + (seed[i % seed.size].toInt() and 255)) and 255; val old = state[i]; state[i] = state[j]; state[j] = old }
        audio.forEachIndexed { index, b -> val x = (index + 1) and 255; output.write(b.toInt() xor state[(state[x] + state[(state[x] + x) and 255]) and 255]) }
        return output.toByteArray()
    }
}
