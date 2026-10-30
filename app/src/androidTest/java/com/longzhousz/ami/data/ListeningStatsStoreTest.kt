package com.longzhousz.ami.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longzhousz.ami.core.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class ListeningStatsStoreTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "qa-listening-${System.nanoTime()}.db"
    private lateinit var db: LibraryDatabase
    private lateinit var job: Job
    private lateinit var store: ListeningStatsStore
    private val day = LocalDate.of(2026, 10, 1)
    private val a = Song("source-a", "file:///a", folder = "qa", title = "A", artist = "甲 / 乙", album = "唱片 A", neteaseId = "100",
        primaryArtistId = "200", primaryArtistName = "甲", albumId = "300").listeningTrack()
    private val b = a.copy(key = "netease:101", title = "B", artistKey = "netease-artist:201", artist = "乙")

    @Before fun prepare() { db = LibraryDatabase(context, name); job = SupervisorJob(); store = ListeningStatsStore(db, CoroutineScope(job + Dispatchers.IO)) }
    @After fun cleanup() { runBlocking { job.cancelAndJoin() }; db.close(); context.deleteDatabase(name) }

    @Test fun dailyRankingsShareDenominatorsAndRemainAfterSourceDeletion() = runBlocking {
        store.write(listOf(ListeningSlice(b, day.minusDays(1), 23, 1000), ListeningSlice(a, day, 22, 60_000),
            ListeningSlice(b, day, 22, 120_000), ListeningSlice(a, day.plusDays(1), 1, 120_000)))
        val period = ListeningPeriod(ListeningScale.MONTH, day)
        val report = store.report(period, day.plusDays(20))
        assertEquals(300_000L, report.totalMillis); assertEquals(2, report.songs)
        assertEquals(2, report.activeDays); assertEquals(2, report.longestStreak)
        assertEquals(listOf(a.key, b.key), report.topSongs.map { it.key })
        assertEquals(180_000L, report.newMillis)
        assertEquals(report.totalMillis, report.artists.sumOf { it.millis })
        assertEquals(report.totalMillis, report.hours.sum())
        assertEquals(31, report.days.size); assertNull(report.previousMillis)
        val imported = Song("source-a", "file:///a", folder = "qa", title = "A", favorite = true)
        db.upsert(imported); db.remove(imported.id)
        assertEquals(report, store.report(period, day.plusDays(20)))
        // Missing optional credits on an old restored queue cannot undo known primary IDs.
        store.write(listOf(ListeningSlice(a.copy(artistKey = "artist:legacy", artist = "甲 / 乙", albumKey = "album:legacy"), day, 22, 1000)))
        assertEquals("甲", store.report(period, day.plusDays(20)).artists.first().title)
    }

    @Test fun comparisonUsesMatchingCalendarDaysAndUnknownHistoryStaysUnknown() = runBlocking {
        store.write(listOf(ListeningSlice(a, day.minusMonths(1), 9, 60_000), ListeningSlice(a, day.minusMonths(1).plusDays(1), 9, 120_000),
            ListeningSlice(a, day, 9, 90_000), ListeningSlice(a, day.plusDays(1), 9, 100_000)))
        val report = store.report(ListeningPeriod(ListeningScale.MONTH, day), day.plusDays(1))
        assertEquals(180_000L, report.previousMillis)
        assertEquals(0L, report.newMillis)
        assertFalse(report.days.last().recorded)
        assertTrue(report.days.first().recorded)
        assertNull(store.report(ListeningPeriod(ListeningScale.YEAR, day), day).previousMillis)
    }

    @Test fun realVersionFourUpgradePreservesFavoritesQueueLyricsAndTranslation() = runBlocking {
        db.close()
        legacyLibrary(context, name, 4) { old ->
            old.execSQL("""INSERT INTO songs(id,uri,source,folder,title,artist,album,album_artist,track,disc,duration,format,
                lyrics,start,favorite,imported_at,has_lyrics,lyric_revision) VALUES('old','file:///old','file:///old','qa','保留','艺人','专辑','',1,1,6000,'FLAC','',0,1,1,1,'saved')""")
            old.execSQL("INSERT INTO song_lyrics VALUES('old','[00:00]原文','[\"译文\"]')")
            old.execSQL("INSERT INTO translations VALUES('saved-translation','[\"保留译文\"]')")
            old.execSQL("INSERT INTO playback_queue VALUES(0,'old')")
            old.execSQL("INSERT INTO playback_checkpoint VALUES(1,0,2300,2,0)")
        }
        db = LibraryDatabase(context, name); store = ListeningStatsStore(db, CoroutineScope(job + Dispatchers.IO))
        assertEquals(5, db.readableDatabase.version)
        assertTrue(db.songs().single().favorite); assertEquals("[00:00]原文", db.songs().single().lyrics)
        assertEquals("[\"保留译文\"]", db.translation("saved-translation"))
        assertEquals(2300L, db.playbackSnapshot()!!.position)
        assertEquals(0L, store.report(ListeningPeriod(ListeningScale.YEAR, day)).totalMillis)
        store.write(listOf(ListeningSlice(a, day, 10, 1000))); store.clear()
        assertEquals(1, db.songs().size); assertNotNull(db.translation("saved-translation")); assertNotNull(db.playbackSnapshot())
    }

    @Test fun boundedPendingDataAndClearCannotResurrectAPreviousFragment() = runBlocking {
        val wall = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        for (n in 0..300) store.sample(a.copy(key = "track:$n"), true, n * 1000L, wall + n * 1000L, ZoneOffset.UTC)
        assertTrue(store.pendingCount <= 256)
        assertTrue(store.report(ListeningPeriod(ListeningScale.MONTH, day), day).incomplete)
        store.clear()
        store.sample(a, true, 1_000_000, wall + 1_000_000, ZoneOffset.UTC)
        assertEquals(0L, store.report(ListeningPeriod(ListeningScale.MONTH, day), day).totalMillis)
        store.sample(a, false, 1_002_000, wall + 1_002_000, ZoneOffset.UTC)
        val after = store.report(ListeningPeriod(ListeningScale.MONTH, day), day)
        assertEquals(2000L, after.totalMillis); assertFalse(after.incomplete)
    }

    @Test fun threeYearsKeepReportsBoundedAndStorageSmall() = runBlocking {
        val start = LocalDate.of(2023, 1, 1); val end = LocalDate.of(2026, 1, 1)
        val tracks = (0 until 3000).map { index -> a.copy(key = "scale:$index", title = "合成歌曲 $index",
            artistKey = "artist:${index % 100}", artist = "合成艺人 ${index % 100}", albumKey = "album:${index / 12}", album = "合成唱片 ${index / 12}") }
        val before = db.readableDatabase.pageSize * db.readableDatabase.rawQuery("PRAGMA page_count", null).use { it.moveToFirst(); it.getLong(0) }
        db.transaction {
            var date = start; var index = 0
            while (date < end) {
                store.write((0 until 50).map { n -> ListeningSlice(tracks[(index * 50 + n) % tracks.size], date, 18 + n % 6, 180_000) })
                date = date.plusDays(1); index++
            }
        }
        val began = System.nanoTime()
        val report = store.report(ListeningPeriod(ListeningScale.YEAR, LocalDate.of(2025, 6, 1)), end)
        val queryMillis = (System.nanoTime() - began) / 1e6
        assertEquals(365, report.days.size); assertEquals(168, report.hours.size)
        assertEquals(10, report.topSongs.size); assertEquals(6, report.artists.size); assertEquals(6, report.albums.size)
        assertEquals(365L * 50 * 180_000, report.totalMillis)
        assertEquals(report.totalMillis, report.hours.sum()); assertEquals(report.totalMillis, report.artists.sumOf { it.millis })
        val bytes = db.readableDatabase.pageSize * db.readableDatabase.rawQuery("PRAGMA page_count", null).use { it.moveToFirst(); it.getLong(0) } - before
        assertTrue("Three years of numeric aggregates must stay compact: $bytes", bytes < 12 * 1024 * 1024)
        File(context.filesDir, "qa/performance/listening-stats-scale.json").apply { parentFile!!.mkdirs() }.writeText(JSONObject()
            .put("years", 3).put("songsPerDay", 50).put("metadataTracks", tracks.size).put("additionalDatabaseBytes", bytes)
            .put("yearQueryMillis", queryMillis).put("reportDays", report.days.size).put("reportHours", report.hours.size)
            .put("topSongs", report.topSongs.size).toString(2))
    }
}
