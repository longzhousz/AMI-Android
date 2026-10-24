package com.longzhousz.ami

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longzhousz.ami.data.LibraryRepository
import com.longzhousz.ami.data.LibraryScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class IncrementalScanTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = "content://com.longzhousz.ami.test.fixtures/tree/root"
    private fun scenario(block: suspend (LibraryRepository, LibraryScanner) -> Unit) = runBlocking(Dispatchers.IO) {
        checkNotNull(context.packageManager.resolveContentProvider("com.longzhousz.ami.test.fixtures", 0))
        context.contentResolver.getType(android.net.Uri.parse("content://com.longzhousz.ami.test.fixtures/document/single.wav"))
        val repo = (context.applicationContext as AmiApplication).repository
        val original = File(context.cacheDir, "saf-fixtures/single.lrc")
        val bytes = original.readBytes()
        val modified = original.lastModified()
        fun clean() { repo.db.songs().filter { it.sourceUri.startsWith("content://com.longzhousz.ami.test.fixtures/") }.forEach { repo.db.remove(it.id) }; repo.reload() }
        clean(); FixtureDocumentsProvider.opened.clear(); FixtureDocumentsProvider.unknownModified = false
        try { block(repo, LibraryScanner(context, repo)) }
        finally {
            original.writeBytes(bytes); original.setLastModified(modified)
            FixtureDocumentsProvider.unknownModified = false; FixtureDocumentsProvider.failOnce.set(null)
            clean()
        }
    }
    @Test fun unchangedRescanOpensNoAudioCueCoverOrLyricFilesAndKeepsUserChanges() = scenario { repo, scanner ->
        scanner.scan(setOf(root))
        val single = repo.db.songs().single { it.title == "single" }
        repo.db.favorite(single.id, true); repo.db.reorder(listOf(single.id))
        assertTrue(FixtureDocumentsProvider.opened.values.sumOf { it.get() } > 0)
        FixtureDocumentsProvider.opened.clear()
        scanner.scan(setOf(root))
        assertEquals(0, FixtureDocumentsProvider.opened.values.sumOf { it.get() })
        val restored = repo.db.songs().single { it.id == single.id }
        assertTrue(restored.favorite); assertEquals(0, restored.order)
        assertEquals(2, repo.db.songs().count { it.album == "Fixture Album" })
    }
    @Test fun sidecarChangeOnlyReopensItsAudioAndDoesNotRescanUnrelatedCue() = scenario { repo, scanner ->
        scanner.scan(setOf(root))
        val lyric = File(context.cacheDir, "saf-fixtures/single.lrc")
        lyric.writeText("[00:00.00]Updated lyric"); lyric.setLastModified(System.currentTimeMillis() + 2000)
        FixtureDocumentsProvider.opened.clear()
        scanner.scan(setOf(root))
        assertTrue(repo.db.songs().single { it.title == "single" }.lyrics.contains("Updated lyric"))
        assertTrue((FixtureDocumentsProvider.opened["single.wav"]?.get() ?: 0) > 0)
        assertEquals(0, FixtureDocumentsProvider.opened["album.wav"]?.get() ?: 0)
        assertEquals(0, FixtureDocumentsProvider.opened["album.cue"]?.get() ?: 0)
    }
    @Test fun unknownModificationTimesAlwaysRecheckSourceFiles() = scenario { _, scanner ->
        FixtureDocumentsProvider.unknownModified = true
        scanner.scan(setOf(root))
        FixtureDocumentsProvider.opened.clear()
        scanner.scan(setOf(root))
        assertTrue(FixtureDocumentsProvider.opened.values.sumOf { it.get() } > 0)
    }
    @Test fun removingACachedTrackAndItsCoverRepairsTheLibraryOnRescan() = scenario { repo, scanner ->
        scanner.scan(setOf(root))
        val single = repo.db.songs().single { it.title == "single" }
        val image = File(single.cover!!)
        val backup = image.readBytes()
        try {
            repo.db.remove(single.id)
            assertTrue(image.delete())
            scanner.scan(setOf(root))
            val restored = repo.db.songs().single { it.id == single.id }
            assertTrue(File(restored.cover!!).isFile)
            assertTrue(restored.lyrics.isNotBlank())
        } finally { if (!image.exists()) image.writeBytes(backup) }
    }
    @Test fun transientLyricReadFailureIsRetriedWithoutChangingFileMetadata() = scenario { repo, scanner ->
        FixtureDocumentsProvider.failOnce.set("single.lrc")
        scanner.scan(setOf(root))
        assertTrue(repo.db.songs().single { it.title == "single" }.lyrics.isBlank())
        assertTrue(repo.importState.value.errors.isNotEmpty())
        scanner.scan(setOf(root))
        assertTrue(repo.db.songs().single { it.title == "single" }.lyrics.contains("First line"))
    }
}
