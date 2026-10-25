package com.longzhousz.ami.data

import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longzhousz.ami.core.LocalLyrics
import com.longzhousz.ami.core.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LazyLyricsTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun scenario(block: suspend (LibraryDatabase) -> Unit) = runBlocking(Dispatchers.IO) {
        val name="qa-lazy-${System.nanoTime()}"
        val db=LibraryDatabase(context,name)
        try { block(db) } finally { db.close();context.deleteDatabase(name) }
    }
    @Test fun hugeLyricsStayOutOfTheCollectionAndBypassCursorWindowLimitsOnDemand() = scenario { db ->
        val raw="[00:00.00]"+"很长的歌词".repeat(180_000)
        db.upsert(Song("huge","file:///unused",folder="qa",title="Huge",lyrics=raw))
        val summary=db.songSummaries().single()
        assertTrue(summary.hasLyrics);assertTrue(summary.lyrics.isEmpty());assertTrue(summary.lyricTranslation.isEmpty())
        assertEquals(raw,db.songLyrics(summary.id).original)
        val store=LyricStore(db,128*1024)
        assertEquals(raw,store.load(summary).original)
        assertEquals(0,store.cachedBytes)
    }
    @Test fun cachedDocumentsFollowManualAndNumericLyricsRevisions() = scenario { db ->
        db.upsert(Song("fallback","file:///unused",folder="qa",title="Fallback",neteaseId="123"))
        db.cacheLyrics("123",LocalLyrics("[00:00]Original","[00:00]译文"),"123","fixture")
        val store=LyricStore(db,128*1024)
        var summary=db.songSummaries().single()
        val automatic=store.load(summary)
        assertEquals("[00:00]Original",automatic.original)
        assertTrue(summary.lyricsSource.startsWith("网易云 ·"))
        db.setLyrics(summary.id,LocalLyrics("[00:00]Manual"),"manual.lrc")
        summary=db.songSummaries().single()
        assertTrue(summary.manualLyrics);assertNotEquals(automatic.revision,summary.lyricsVersion)
        assertEquals("[00:00]Manual",store.load(summary).original)
        db.clearLyrics(summary.id)
        summary=db.songSummaries().single()
        assertEquals(automatic,store.load(summary))
        db.cacheLyrics("123",LocalLyrics("[00:00]Updated"),"123","fixture")
        summary=db.songSummaries().single()
        assertEquals("[00:00]Updated",store.load(summary).original)
        assertTrue(store.cachedBytes<=128*1024)
    }
    @Test fun schemaThreeMigrationPreservesLyricsOverridesQueueAndUserState() {
        val name="qa-lazy-upgrade-${System.nanoTime()}"
        val raw="[00:00]"+"Migration lyric ".repeat(500)
        try {
            legacyLibrary(context,name,3) { db ->
                db.execSQL("INSERT INTO songs VALUES ('song','file:///unused','file:///unused','qa','Old','Artist','Album','',0,1,1000,'WAV',NULL,?,0,NULL,9,1,123,'123','[00:00]译文')",arrayOf(raw))
                db.execSQL("INSERT INTO lyric_overrides VALUES ('song','[00:00]Manual','','manual.lrc')")
                db.execSQL("INSERT INTO netease_lyrics VALUES ('123','[00:00]Numeric','','123','fixture')")
                db.execSQL("INSERT INTO translations VALUES ('old','saved')")
                db.execSQL("INSERT INTO playback_queue VALUES (0,'song')")
                db.execSQL("INSERT INTO playback_checkpoint VALUES (1,0,777,1,0)")
            }
            LibraryDatabase(context,name).use { db ->
                val store=LyricStore(db)
                runBlocking {
                    val manual=db.songSummaries().single()
                    assertTrue(manual.favorite);assertEquals(9,manual.order);assertEquals(123L,manual.importedAt)
                    assertEquals("[00:00]Manual",store.load(manual).original)
                    db.clearLyrics(manual.id)
                    val automatic=db.songSummaries().single()
                    assertNotEquals(manual.lyricsVersion,automatic.lyricsVersion)
                    assertEquals(raw,store.load(automatic).original)
                }
                assertEquals("saved",db.translation("old"))
                assertEquals(777L,db.playbackSnapshot()!!.position)
                db.readableDatabase.rawQuery("SELECT lyrics,lyric_translated FROM songs",null).use { c -> c.moveToFirst();assertEquals("",c.getString(0));assertEquals("",c.getString(1)) }
                db.remove("song")
                db.readableDatabase.rawQuery("SELECT COUNT(*) FROM song_lyrics",null).use { c -> c.moveToFirst();assertEquals(0,c.getInt(0)) }
            }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun tenThousandTrackSummariesKeepLyricsOutOfTheSnapshot() = scenario { db ->
        val raw="[00:00]"+"Lyrics ".repeat(80)
        db.transaction { repeat(10_000) { n -> db.upsert(Song("large-$n","file:///unused",folder="qa",title="Track $n",lyrics=raw)) } }
        val start=System.nanoTime()
        val tracks=db.songSummaries()
        assertEquals(10_000,tracks.size)
        assertTrue(tracks.all { it.hasLyrics && it.lyrics.isEmpty() && it.lyricTranslation.isEmpty() })
        val evidence=JSONObject().put("scenario","10000 tracks with lyric bodies").put("tracks",tracks.size)
            .put("retainedLyricCharacters",tracks.sumOf { it.lyrics.length+it.lyricTranslation.length })
            .put("metadataQueryMs",(System.nanoTime()-start)/1_000_000.0)
        record("library-10000",evidence)
    }
    @Test fun captureFullVersusMetadataMemoryOnTheSameFixture() = scenario { db ->
        val raw="[00:00]"+"L".repeat(8192)
        db.transaction { repeat(1000) { db.upsert(Song("memory-$it","file:///unused",folder="qa",title="Track $it",lyrics=raw)) } }
        fun measured(full: Boolean): JSONObject {
            System.gc()
            val start=System.nanoTime()
            val tracks=if (full) db.songs() else db.songSummaries()
            val used=Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()
            val result=JSONObject().put("query",if(full) "full" else "metadata").put("tracks",tracks.size)
                .put("lyricCharacters",tracks.sumOf { it.lyrics.length+it.lyricTranslation.length })
                .put("queryMs",(System.nanoTime()-start)/1_000_000.0).put("javaUsedBytes",used).put("pssKb",Debug.getPss())
            val shell=InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys meminfo ${context.packageName}")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(shell).use { stream ->
                evidenceFile("memory-${if(full) "full" else "metadata"}.txt").outputStream().use { stream.copyTo(it) }
            }
            assertEquals(if(full) raw.length*1000 else 0,tracks.sumOf { it.lyrics.length })
            return result
        }
        record("memory-comparison",JSONObject().put("fixtureTracks",1000).put("lyricCharactersPerTrack",raw.length)
            .put("metadata",measured(false)).put("full",measured(true)).put("caveat","One emulator run, debug build; memory and timing are diagnostic, not phone benchmarks"))
    }
    private fun evidenceFile(name: String)=File(context.filesDir,"qa/performance/$name").apply { parentFile!!.mkdirs() }
    private fun record(name: String,data: JSONObject) { evidenceFile("$name.json").writeText(data.toString(2)) }
}
