package com.longzhousz.ami.data

import android.content.ContextWrapper
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longzhousz.ami.core.LocalLyrics
import com.longzhousz.ami.core.Song
import com.longzhousz.ami.core.digest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class CancelledScanBackupLinksTest {
    @Test fun cancelledScanStillRestoresAlreadyImportedBackupUserData() =
        runBlocking(Dispatchers.IO) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val name = "qa-cancelled-scan-${System.nanoTime()}"
            val directory = File(context.cacheDir, "saf-fixtures/$name").apply { mkdirs() }
            val isolated =
                object : ContextWrapper(context) {
                    override fun getDatabasePath(dbName: String) = File(super.getDatabasePath(dbName).parentFile, "$name.sqlite3")
                }
            val repository = LibraryRepository(isolated)
            val audio = File(directory, "single.wav")
            audio.writeBytes(ByteArray(44 * 1024))
            val root = Uri.parse("content://com.longzhousz.ami.test.fixtures/tree/$name")
            val document = Uri.parse("content://com.longzhousz.ami.test.fixtures/document/single.wav")
            val second = Uri.parse("content://com.longzhousz.ami.test.fixtures/document/second.wav")
            val expectedId =
                com.longzhousz.ami.core
                    .digest(
                        com.longzhousz.ami.data.DocumentFiles
                            .identity(document),
                    )
            val files =
                object : DocumentFiles(isolated) {
                    override suspend fun walk(
                        root: Uri,
                        id: String,
                        path: String,
                        result: MutableList<DocumentEntry>,
                        depth: Int,
                    ) {
                        result += DocumentEntry(document, "single.wav", id, "single.wav", audio.lastModified(), audio.length())
                        result += DocumentEntry(second, "second.wav", id, "second.wav", audio.lastModified(), audio.length())
                    }
                }

            fun scanner(beforeAdd: (Int) -> Unit) =
                LibraryScanner(beforeAdd, isolated, repository).also {
                    it::class.java
                        .getDeclaredField("files")
                        .apply { isAccessible = true }
                        .set(it, files)
                }

            try {
                scanner { _ -> }.scan(setOf(root.toString()))
                val imported = repository.db.songSummaries().single { it.id == expectedId }
                repository.db.favorite(imported.id, true)
                repository.db.setLyrics(imported.id, LocalLyrics("[00:00.00]Saved lyric", ""), "关联歌词")
                val bytes = ByteArrayOutputStream().also { PersonalDataBackup(repository.db, directory).write(it, emptyMap()) }.toByteArray()
                repository.db.songSummaries().forEach { repository.db.remove(it.id) }
                repository.db.writableDatabase.execSQL("DELETE FROM scan_cache")
                PersonalDataBackup(repository.db, directory).read(ByteArrayInputStream(bytes)).use { it.merge() }
                repository.db.writableDatabase.execSQL("UPDATE backup_song_links SET applied_to=NULL")

                val failure = runCatching { scanner { index -> if (index == 1) throw CancellationException("test") }.scan(setOf(root.toString())) }
                assertTrue(failure.exceptionOrNull() is CancellationException)

                val summary = repository.db.songSummaries().singleOrNull { it.id == expectedId }
                assertNotNull("The imported song must remain after cancellation", summary)
                summary!!
                assertTrue("Backup favorite must be applied even when the scan is cancelled", summary.favorite)
                assertEquals("[00:00.00]Saved lyric", repository.db.songLyrics(summary.id).original)
            } finally {
                repository.scope.cancel()
                repository.db.close()
                isolated.deleteDatabase("$name.sqlite3")
                directory.deleteRecursively()
            }
        }
}
