package com.longzhousz.ami

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longzhousz.ami.core.LocalLyrics
import com.longzhousz.ami.core.Lyrics
import com.longzhousz.ami.core.NeteaseLyrics
import com.longzhousz.ami.data.LibraryDatabase
import com.longzhousz.ami.data.LibraryScanner
import com.longzhousz.ami.data.LyricImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NeteaseImportTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun ncmIdMatchesExtensionlessJsonLyricsAndManualOverrideSurvivesRescanning() = runBlocking(Dispatchers.IO) {
        val repo = (context.applicationContext as AmiApplication).repository
        val musicRoot = "content://com.longzhousz.ami.test.fixtures/tree/netease"
        val lyricRoot = "content://com.longzhousz.ami.test.fixtures/tree/lyrics"
        fun imported() = repo.db.songs().single { it.sourceUri.startsWith(musicRoot) }
        fun document(name: String) = DocumentsContract.buildDocumentUriUsingTree(Uri.parse(lyricRoot), "lyrics/$name")
        repo.db.writableDatabase.delete("netease_lyrics", "music_id=?", arrayOf("1234567890123"))
        try {
            LibraryScanner(context, repo).scan(setOf(musicRoot))
            val initial = imported()
            assertEquals("1234567890123", initial.neteaseId)
            assertTrue(initial.lyrics.isBlank())
            repo.db.favorite(initial.id, true); repo.db.reorder(listOf(initial.id))

            LibraryScanner(context, repo).scan(setOf(musicRoot), setOf(lyricRoot))
            val matched = imported()
            assertTrue(repo.importState.value.errors.toString(), repo.importState.value.errors.isEmpty())
            assertEquals(listOf(0L, 0L, 430L, 1200L), Lyrics.parse(matched.lyrics).map { it.time })
            assertEquals(listOf("", "", "第一行", "第二行"), NeteaseLyrics.translations(Lyrics.parse(matched.lyrics), matched.lyricTranslation))
            assertTrue(matched.lyricsSource.contains("1234567890123"))
            assertTrue(matched.favorite); assertEquals(0, matched.order)
            assertEquals(initial.importedAt, matched.importedAt)

            LyricImporter(context, repo).attach(matched.id, document("manual.lrc"))
            assertTrue(imported().manualLyrics)
            assertEquals("Manual lyric", Lyrics.parse(imported().lyrics).single().text)
            assertTrue(runCatching { LyricImporter(context, repo).attach(matched.id, document("invalid.txt")) }.isFailure)
            assertEquals("Manual lyric", Lyrics.parse(imported().lyrics).single().text)
            LibraryScanner(context, repo).scan(setOf(musicRoot), setOf(lyricRoot))
            assertEquals("Manual lyric", Lyrics.parse(imported().lyrics).single().text)
            repo.db.clearLyrics(matched.id)
            assertFalse(imported().manualLyrics)
            assertEquals(4, Lyrics.parse(imported().lyrics).size)

            // Catalog copies remain usable after the source lyric folder is no longer scanned.
            LibraryScanner(context, repo).scan(setOf(musicRoot))
            assertEquals(4, Lyrics.parse(imported().lyrics).size)
        } finally {
            repo.db.songs().filter { it.sourceUri.startsWith(musicRoot) }.forEach { repo.db.remove(it.id) }
            repo.db.writableDatabase.delete("netease_lyrics", "music_id=?", arrayOf("1234567890123"))
            repo.reload()
        }
    }

    @Test fun upgradingExistingVersionOneDatabasePreservesFavoritesOrderLyricsAndTranslations() {
        val name = "migration-netease-test.sqlite3"
        context.deleteDatabase(name)
        try {
            val path = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
            SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
                db.execSQL("""CREATE TABLE songs (id TEXT PRIMARY KEY, uri TEXT NOT NULL, source TEXT NOT NULL, folder TEXT NOT NULL,
                    title TEXT NOT NULL, artist TEXT NOT NULL, album TEXT NOT NULL, album_artist TEXT NOT NULL,
                    track INTEGER NOT NULL, disc INTEGER NOT NULL, duration INTEGER NOT NULL, format TEXT NOT NULL,
                    cover TEXT, lyrics TEXT NOT NULL, start INTEGER NOT NULL, end INTEGER, user_order INTEGER,
                    favorite INTEGER NOT NULL DEFAULT 0, imported_at INTEGER NOT NULL)""")
                db.execSQL("CREATE TABLE translations (cache_key TEXT PRIMARY KEY, body TEXT NOT NULL)")
                db.execSQL("CREATE TABLE actions (id INTEGER PRIMARY KEY AUTOINCREMENT, time INTEGER NOT NULL, name TEXT NOT NULL, source TEXT NOT NULL, status TEXT NOT NULL)")
                db.execSQL("INSERT INTO songs VALUES ('old','content://old','content://old','folder','Song','Artist','Album','Artist',2,1,1000,'FLAC',NULL,'[00:00]Old lyric',0,NULL,7,1,12345)")
                db.execSQL("INSERT INTO translations VALUES (?,?)", arrayOf("saved-key", "saved-translation"))
                db.version = 1
            }
            LibraryDatabase(context, name).use { db ->
                val song = db.songs().single()
                assertTrue(song.favorite); assertEquals(7, song.order); assertEquals(12345L, song.importedAt)
                assertEquals("[00:00]Old lyric", song.lyrics)
                assertEquals("saved-translation", db.translation("saved-key"))
                assertNull(song.neteaseId)
                db.upsert(song.copy(neteaseId = "1234567890123"))
                db.cacheLyrics("1234567890123", LocalLyrics("[00:00]Cache lyric"), "1234567890123", "fixture")
                assertEquals("[00:00]Old lyric", db.songs().single().lyrics)
                db.setLyrics(song.id, LocalLyrics("[00:00]Manual"), "manual.lrc")
                assertEquals("[00:00]Manual", db.songs().single().lyrics)
            }
            LibraryDatabase(context, name).use { db ->
                assertEquals("[00:00]Manual", db.songs().single().lyrics)
                assertTrue(db.songs().single().favorite)
                db.remove("old")
                db.readableDatabase.rawQuery("SELECT COUNT(*) FROM lyric_overrides", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
            }
        } finally { context.deleteDatabase(name) }
    }
}
