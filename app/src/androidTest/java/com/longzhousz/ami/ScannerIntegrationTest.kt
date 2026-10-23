package com.longzhousz.ami

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longzhousz.ami.core.Lyrics
import com.longzhousz.ami.data.LibraryScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ScannerIntegrationTest {
    @Test fun safImportLinksLyricsCoverAndCueWithoutDuplicatingTheAudioImage() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        checkNotNull(context.packageManager.resolveContentProvider("com.longzhousz.ami.test.fixtures", 0)) { "Fixture provider must be visible to the debug target" }
        val repo = (context.applicationContext as AmiApplication).repository
        val root = "content://com.longzhousz.ami.test.fixtures/tree/root"
        fun fixture(song: com.longzhousz.ami.core.Song) = song.sourceUri.startsWith("content://com.longzhousz.ami.test.fixtures/")
        try {
            LibraryScanner(context, repo).scan(setOf(root, root.replace("/root", "/alias")))
            val songs = repo.db.songs().filter(::fixture)
            assertEquals(repo.importState.value.errors.toString(), 3, songs.size)
            assertTrue(repo.importState.value.errors.toString(), repo.importState.value.errors.isEmpty())
            val single = songs.single { it.title == "single" }
            assertEquals(2, Lyrics.parse(single.lyrics).size)
            assertTrue(File(single.cover!!).exists())
            val cue = songs.filter { it.album == "Fixture Album" }.sortedBy { it.start }
            assertEquals(listOf("First", "Second"), cue.map { it.title })
            assertEquals(listOf(0L, 3000L), cue.map { it.start })
            assertEquals(listOf(3000L, 3000L), cue.map { it.duration })
            assertTrue(cue.all { Uri.parse(it.uri).scheme == "content" })
            repo.db.favorite(single.id, true)
            LibraryScanner(context, repo).scan(setOf(root.replace("/root", "/alias")))
            val rescanned = repo.db.songs().filter(::fixture)
            assertEquals(3, rescanned.size)
            assertTrue(rescanned.single { it.id == single.id }.favorite)
        } finally {
            repo.db.songs().filter(::fixture).forEach { repo.db.remove(it.id) }
            repo.reload()
        }
    }
}
