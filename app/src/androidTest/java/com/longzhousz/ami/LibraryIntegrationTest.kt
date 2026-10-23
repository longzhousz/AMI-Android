package com.longzhousz.ami

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longzhousz.ami.core.Song
import com.longzhousz.ami.data.LibraryDatabase
import com.longzhousz.ami.data.SecretStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LibraryIntegrationTest {
    private val target get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun database(): LibraryDatabase {
        val databaseName = "test-${System.nanoTime()}.sqlite3"
        val context = object : ContextWrapper(target) {
            override fun getDatabasePath(name: String): File = File(super.getDatabasePath(name).parentFile, databaseName)
        }
        return LibraryDatabase(context)
    }
    @Test fun rescanningPreservesFavoritesAndManualTrackOrderAcrossReopen() {
        val database = database()
        val a = Song("a", "content://a", folder = "f", title = "Before", track = 1)
        val b = Song("b", "content://b", folder = "f", title = "Second", track = 2)
        try {
            database.upsert(a); database.upsert(b)
            database.favorite("a", true); database.reorder(listOf("b", "a"))
            database.upsert(a.copy(title = "Updated metadata", duration = 12345))
            database.close()
            val read = database.songs().first { it.id == "a" }
            assertEquals("Updated metadata", read.title)
            assertTrue(read.favorite)
            assertEquals(1, read.order)
            assertEquals(a.importedAt, read.importedAt)
            assertEquals(2, database.songs().size)
            database.remove("a")
            assertEquals(listOf("b"), database.songs().map { it.id })
        } finally { val path = database.readableDatabase.path; database.close(); target.deleteDatabase(path) }
    }
    @Test fun keystoreRoundTripsReplacesAndDeletesWithoutPlaintextOnDisk() {
        val name = "test-${System.nanoTime()}"
        val store = SecretStore(target, name)
        try {
            store.save("sk-test-only-not-a-real-key")
            assertEquals("sk-test-only-not-a-real-key", store.read())
            assertFalse(File(target.noBackupFilesDir, "$name.enc").readBytes().toString(Charsets.ISO_8859_1).contains("sk-test-only"))
            store.save("replacement")
            assertEquals("replacement", SecretStore(target, name).read())
            store.save("")
            assertFalse(store.configured())
            assertNull(store.read())
        } finally { store.save("") }
    }
    @Test fun cleanupKeepsReferencedDecodedAudioAndNeverTouchesSourceFiles() {
        val app = target.applicationContext as AmiApplication
        val repo = app.repository
        val source = File(target.cacheDir, "original-test.wav").apply { writeText("original") }
        val cached = File(repo.audioDir, "test-${System.nanoTime()}.audio").apply { writeText("decoded") }
        val orphan = File(repo.coverDir, "orphan-${System.nanoTime()}.jpg").apply { writeText("unused") }
        val song = Song("test-cleanup", Uri.fromFile(cached).toString(), sourceUri = Uri.fromFile(source).toString(), folder = "test", title = "test")
        try {
            repo.db.upsert(song)
            repo.cleanup()
            assertTrue(source.exists()); assertTrue(cached.exists()); assertFalse(orphan.exists())
        } finally { repo.db.remove(song.id); repo.reload(); source.delete(); cached.delete(); orphan.delete() }
    }
}
