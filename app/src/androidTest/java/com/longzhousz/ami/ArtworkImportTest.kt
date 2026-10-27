package com.longzhousz.ami

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longzhousz.ami.core.PlaybackSnapshot
import com.longzhousz.ami.core.ScanFingerprint
import com.longzhousz.ami.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

@RunWith(AndroidJUnit4::class)
class ArtworkImportTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val imageUrl = "http://p2.music.126.net/artwork-${System.nanoTime()}.jpg"
    private val image by lazy {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFE04525.toInt()) }
        try { ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray() }
        finally { bitmap.recycle() }
    }

    private fun scenario(block: suspend (LibraryRepository, File, String) -> Unit) = runBlocking(Dispatchers.IO) {
        context.contentResolver.getType(Uri.parse("content://com.longzhousz.ami.test.fixtures/document/single.wav"))
        val name = "artwork-${System.nanoTime()}"
        val directory = File(context.cacheDir, "saf-fixtures/$name").apply { mkdirs() }
        val databaseName = "$name.sqlite3"
        val isolated = object : ContextWrapper(context) {
            override fun getDatabasePath(name: String) = File(super.getDatabasePath(name).parentFile, databaseName)
        }
        val repo = LibraryRepository(isolated)
        fun cacheFiles() = listOf(repo.audioDir, repo.coverDir).flatMap { it.listFiles()?.toList().orEmpty() }
        val priorFiles = cacheFiles().map { it.path }.toSet()
        try { block(repo, directory, "content://com.longzhousz.ami.test.fixtures/tree/$name") }
        finally {
            repo.scope.cancel(); repo.db.close(); context.deleteDatabase(databaseName)
            directory.deleteRecursively()
            cacheFiles().filter { it.path !in priorFiles }.forEach { it.delete() }
            FixtureDocumentsProvider.opened.clear()
        }
    }

    @Test fun legacyMissingCoverIsBackfilledWithoutLosingUserStateAndThenReusesDiskCache() = scenario { repo, directory, root ->
        val source = fixture(directory, "track.ncm", imageUrl)
        File(directory, "track.lrc").writeText(JSONObject().put("lrc", "[00:00.00]Original")
            .put("translatedLyric", "[00:00.00]译文").toString())
        val originalBytes = source.readBytes()
        var requests = 0
        val scanner = LibraryScanner(context, repo) { requests++; if (requests == 1) throw IOException("Temporary failure") else image }
        scanner.scan(setOf(root))
        val before = repo.db.songs().single()
        assertNull(before.cover)
        assertTrue(repo.importState.value.errors.isNotEmpty())
        val entries = entries(root)
        val audio = entries.single { it.extension == "ncm" }
        val key = "audio:" + DocumentFiles.identity(audio.uri)
        repo.db.saveScanRecord(ScanRecord(key, ScanFingerprint.of("audio-scan-v1", entries.map { it.stamp() })!!,
            listOf(before.id), artifacts = listOf(Uri.parse(before.uri).path!!)))
        repo.db.favorite(before.id, true); repo.db.reorder(listOf(before.id))
        repo.db.cacheTranslation("artwork-test", "[\"saved translation\"]")
        val playback = PlaybackSnapshot(listOf(before.id), 0, 750, 2, false)
        repo.db.savePlayback(playback, true)
        scanner.scan(setOf(root))
        val after = repo.db.songs().single()
        assertEquals(32, BitmapFactory.decodeFile(after.cover!!).width)
        assertTrue(after.favorite); assertEquals(0, after.order); assertEquals(before.importedAt, after.importedAt)
        assertEquals(before.lyrics, after.lyrics); assertEquals(before.lyricTranslation, after.lyricTranslation)
        assertEquals("[\"saved translation\"]", repo.db.translation("artwork-test"))
        assertEquals(playback, repo.db.playbackSnapshot()); assertArrayEquals(originalBytes, source.readBytes())
        FixtureDocumentsProvider.opened.clear()
        scanner.scan(setOf(root))
        assertEquals(0, FixtureDocumentsProvider.opened.values.sumOf { it.get() })
        assertEquals(2, requests)
        repo.db.removeScanRecord(key)
        LibraryScanner(context, repo) { error("Existing artwork must work offline") }.scan(setOf(root))
        assertEquals(after.cover, repo.db.songs().single().cover)
    }

    @Test fun cachedCueTracksBackfillTheirNcmCoverAndKeepTheSourceExcluded() = scenario { repo, directory, root ->
        fixture(directory, "track.ncm", imageUrl)
        File(directory, "track.cue").writeText("TITLE \"Artwork Cue\"\nFILE \"track.ncm\" WAVE\nTRACK 01 AUDIO\nTITLE \"One\"\nINDEX 01 00:00:00\nTRACK 02 AUDIO\nTITLE \"Two\"\nINDEX 01 00:01:00\n")
        LibraryScanner(context, repo) { throw IOException("Offline") }.scan(setOf(root))
        val before = repo.db.songs()
        assertEquals(2, before.size); assertTrue(before.all { it.cover == null })
        val entries = entries(root)
        val cue = entries.single { it.extension == "cue" }
        val source = entries.single { it.extension == "ncm" }
        repo.db.saveScanRecord(ScanRecord("audio:" + DocumentFiles.identity(cue.uri),
            ScanFingerprint.of("cue-scan-v1", entries.map { it.stamp() })!!, before.map { it.id },
            listOf(DocumentFiles.identity(source.uri)), before.map { Uri.parse(it.uri).path!! }.distinct()))
        var requests = 0
        val scanner = LibraryScanner(context, repo) { requests++; image }
        scanner.scan(setOf(root))
        val after = repo.db.songs()
        assertEquals(before.map { it.id }, after.map { it.id })
        assertTrue(after.all { it.cover != null && it.sourceUri == cue.uri.toString() })
        assertEquals(1, requests)
        FixtureDocumentsProvider.opened.clear()
        scanner.scan(setOf(root))
        assertEquals(0, FixtureDocumentsProvider.opened.values.sumOf { it.get() })
        assertEquals(1, requests)
    }

    @Test fun localPicturesWinDamagedPicturesFallBackAndNoUrlRemainsIncremental() = scenario { repo, directory, root ->
        fixture(directory, "local.ncm", imageUrl, image)
        fixture(directory, "damaged.ncm", imageUrl, byteArrayOf(1, 2, 3))
        fixture(directory, "shared.ncm", imageUrl)
        fixture(directory, "no-url.ncm", null)
        var requests = 0
        val scanner = LibraryScanner(context, repo) { url ->
            assertTrue(url.startsWith("https://p2.music.126.net/")); requests++; image
        }
        scanner.scan(setOf(root))
        val songs = repo.db.songs().associateBy { it.title }
        assertEquals(4, songs.size)
        assertNotNull(songs.getValue("local").cover)
        assertFalse(File(songs.getValue("local").cover!!).name.startsWith("netease-"))
        assertEquals(songs.getValue("damaged").cover, songs.getValue("shared").cover)
        assertNotNull(songs.getValue("damaged").cover); assertNull(songs.getValue("no-url").cover)
        assertEquals(1, requests)
        FixtureDocumentsProvider.opened.clear()
        scanner.scan(setOf(root))
        assertEquals(0, FixtureDocumentsProvider.opened.values.sumOf { it.get() })
        assertEquals(1, requests)
    }

    @Test fun anOfflineHostDoesNotBlockOtherHostsAndMissingCoversRetryOnTheNextScan() = scenario { repo, directory, root ->
        fixture(directory, "offline-a.ncm", imageUrl)
        fixture(directory, "offline-b.ncm", imageUrl.replace(".jpg", "-other.jpg"))
        fixture(directory, "working.ncm", imageUrl.replace("p2.", "p1."))
        var requests = 0
        LibraryScanner(context, repo) { url ->
            requests++
            if (url.contains("//p2.")) throw java.net.UnknownHostException("Offline fixture host")
            image
        }.scan(setOf(root))
        assertEquals(2, requests)
        assertEquals(1, repo.db.songs().count { it.cover != null })
        LibraryScanner(context, repo) { requests++; image }.scan(setOf(root))
        assertEquals(4, requests)
        assertTrue(repo.db.songs().all { it.cover != null })
    }

    private suspend fun entries(root: String) = mutableListOf<DocumentEntry>().also {
        DocumentFiles(context).walk(Uri.parse(root), android.provider.DocumentsContract.getTreeDocumentId(Uri.parse(root)), "", it)
    }

    /** Replace only metadata/artwork in the existing NCM fixture; keep its encoded audio intact. */
    private fun fixture(directory: File, name: String, url: String?, cover: ByteArray = byteArrayOf()): File {
        val original = File(context.cacheDir, "saf-fixtures/netease/track.ncm").readBytes()
        val buffer = ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN).apply { position(10) }
        val keySize = buffer.int; buffer.position(buffer.position() + keySize)
        val metadataAt = buffer.position()
        val oldSize = buffer.int; buffer.position(buffer.position() + oldSize)
        val reserved = ByteArray(9).also { buffer.get(it) }
        val oldCoverSize = buffer.int; buffer.position(buffer.position() + oldCoverSize)
        val audioAt = buffer.position()
        val json = JSONObject().put("musicName", name.substringBeforeLast('.')).put("musicId", 1234567890123L).put("format", "wav")
        url?.let { json.put("albumPic", it) }
        val encrypted = Cipher.getInstance("AES/ECB/PKCS5Padding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec("#14ljk_!\\]&0U<'(".toByteArray(), "AES"))
            doFinal("music:$json".toByteArray())
        }
        val metadata = ("163 key(Don't modify):".toByteArray() + Base64.getEncoder().encode(encrypted)).map { (it.toInt() xor 0x63).toByte() }.toByteArray()
        fun littleInt(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
        return File(directory, name).apply { writeBytes(original.copyOfRange(0, metadataAt) + littleInt(metadata.size) + metadata +
            reserved + littleInt(cover.size) + cover + original.copyOfRange(audioAt, original.size)) }
    }
}
