package com.longzhousz.ami

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longzhousz.ami.core.LocalLyrics
import com.longzhousz.ami.core.albumsOf
import com.longzhousz.ami.data.LibraryScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CueUpgradeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = "content://com.longzhousz.ami.test.fixtures/tree/root"

    @Test fun addingACueToAnImportedWholeTrackKeepsFavoriteOrderAndManualLyrics() =
        runBlocking(Dispatchers.IO) {
            checkNotNull(context.packageManager.resolveContentProvider("com.longzhousz.ami.test.fixtures", 0))
            context.contentResolver.getType(Uri.parse("content://com.longzhousz.ami.test.fixtures/document/single.wav"))
            val repo = (context.applicationContext as AmiApplication).repository
            val cue = File(context.cacheDir, "saf-fixtures/album.cue")
            val cueText = cue.readText()

            fun clean() {
                repo.db
                    .songs()
                    .filter { it.sourceUri.startsWith("content://com.longzhousz.ami.test.fixtures/") }
                    .forEach { repo.db.remove(it.id) }
                repo.db
                    .scanRecords()
                    .keys
                    .filter { it.contains("fixtures") }
                    .forEach(repo.db::removeScanRecord)
                repo.reload()
            }
            try {
                clean()
                cue.delete()
                val scanner = LibraryScanner(context, repo)
                scanner.scan(setOf(root))
                val whole = repo.db.songs().single { it.sourceUri.endsWith("/album.wav") }
                repo.db.favorite(whole.id, true)
                repo.db.reorder(listOf(whole.id))
                repo.db.setLyrics(whole.id, LocalLyrics("[00:00.00]Manual album lyric", "[00:00.00]手动歌词翻译"), "album.lrc")

                cue.writeText(cueText)
                scanner.scan(setOf(root))
                FixtureDocumentsProvider.opened.clear()
                scanner.scan(setOf(root))
                assertEquals("An unchanged CUE must not reopen its sources", 0, FixtureDocumentsProvider.opened.values.sumOf { it.get() })

                val tracks =
                    albumsOf(repo.db.songs())
                        .single { it.title == "Fixture Album" }
                        .songs
                assertEquals(listOf("First", "Second"), tracks.map { it.title })
                assertTrue("The whole-track row must be replaced by its CUE tracks", repo.db.songs().none { it.id == whole.id })
                assertTrue("Both CUE tracks keep the favorite", tracks.all { it.favorite })
                assertTrue("Both CUE tracks keep the manual lyric", tracks.all { it.manualLyrics })
                assertEquals("Both CUE tracks keep the manual position", listOf(0, 0), tracks.map { it.order })
                assertEquals(listOf("album.lrc", "album.lrc"), tracks.map { it.lyricsSource })
                assertEquals(
                    listOf("[00:00.00]Manual album lyric", "[00:00.00]Manual album lyric"),
                    tracks.map { repo.db.songLyrics(it.id).original },
                )
                assertEquals(
                    listOf("[00:00.00]手动歌词翻译", "[00:00.00]手动歌词翻译"),
                    tracks.map { repo.db.songLyrics(it.id).translated },
                )
            } finally {
                cue.writeText(cueText)
                FixtureDocumentsProvider.unknownModified = false
                FixtureDocumentsProvider.failOnce.set(null)
                clean()
            }
        }
}
