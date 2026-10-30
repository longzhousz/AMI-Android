package com.longzhousz.ami.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

class ListeningStatsTest {
    private val utc = ZoneId.of("UTC")
    private val song = Song("a", "file:///a.flac", folder = "music", title = "A", artist = "Singer", album = "Album").listeningTrack()

    @Test fun elapsedTimeCountsFragmentsAndNeverPausedBufferingOrWallClockJumps() {
        val counter = ListeningAccumulator(); val slices = mutableListOf<ListeningSlice>()
        val wall = Instant.parse("2026-10-30T12:00:00Z").toEpochMilli()
        counter.update(song, false, 0, wall, utc, slices::add)
        counter.update(song, true, 60_000, wall + 60_000, utc, slices::add)
        counter.update(song, true, 70_000, wall - 3_600_000, utc, slices::add)
        counter.update(song, false, 75_000, wall - 3_595_000, utc, slices::add)
        counter.update(song, false, 500_000, wall + 1_000_000, utc, slices::add)
        assertEquals(15_000L, slices.sumOf { it.millis })
        assertEquals(listOf(12, 11), slices.map { it.hour })
    }

    @Test fun switchingRepeatingAndEndingAttributeEveryMillisecondToThePreviousSong() {
        val counter = ListeningAccumulator(); val slices = mutableListOf<ListeningSlice>()
        val other = song.copy(key = "b")
        counter.update(song, true, 0, 0, utc, slices::add)
        counter.update(song, true, 3000, 3000, utc, slices::add)
        counter.update(other, true, 6000, 6000, utc, slices::add)
        counter.update(other, true, 6000, 6000, utc, slices::add)
        counter.update(null, false, 10_000, 10_000, utc, slices::add)
        counter.update(null, false, 90_000, 90_000, utc, slices::add)
        assertEquals(6000L, slices.filter { it.track.key == song.key }.sumOf { it.millis })
        assertEquals(4000L, slices.filter { it.track.key == "b" }.sumOf { it.millis })
    }

    @Test fun midnightAndNewYearSplitRatherThanMoveTheWholeSong() {
        val counter = ListeningAccumulator(); val slices = mutableListOf<ListeningSlice>()
        val wall = Instant.parse("2026-12-31T23:59:55Z").toEpochMilli()
        counter.update(song, true, 0, wall, utc, slices::add)
        counter.update(song, false, 20_000, wall + 20_000, utc, slices::add)
        assertEquals(listOf(LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 1)), slices.map { it.day })
        assertEquals(listOf(5000L, 15_000L), slices.map { it.millis })
    }

    @Test fun daylightSavingMissingAndRepeatedHoursKeepTheirActualDuration() {
        val zone = ZoneId.of("America/New_York")
        for ((instant, hours) in listOf("2026-03-08T06:59:55Z" to listOf(1, 3), "2026-11-01T05:59:55Z" to listOf(1, 1))) {
            val counter = ListeningAccumulator(); val slices = mutableListOf<ListeningSlice>()
            val wall = Instant.parse(instant).toEpochMilli()
            counter.update(song, true, 0, wall, zone, slices::add)
            counter.update(song, false, 20_000, wall + 20_000, zone, slices::add)
            assertEquals(hours, slices.map { it.hour })
            assertEquals(20_000L, slices.sumOf { it.millis })
        }
    }

    @Test fun changingTimeZoneOnlyAffectsTheNextFragmentAndResetNeverBackfills() {
        val counter = ListeningAccumulator(); val slices = mutableListOf<ListeningSlice>()
        val wall = Instant.parse("2026-10-30T23:59:55Z").toEpochMilli()
        counter.update(song, true, 0, wall, utc, slices::add)
        counter.update(song, true, 3000, wall + 3000, ZoneId.of("Asia/Shanghai"), slices::add)
        counter.update(song, true, 5000, wall + 5000, ZoneId.of("Asia/Shanghai"), slices::add)
        assertEquals(listOf(30, 31), slices.map { it.day.dayOfMonth })
        counter.reset()
        counter.update(song, true, 100_000, wall + 100_000, utc, slices::add)
        assertEquals(5000L, slices.sumOf { it.millis })
    }

    @Test fun periodsUseMondayCalendarMonthsAndLeapYears() {
        val week = ListeningPeriod(ListeningScale.WEEK, LocalDate.of(2027, 1, 1))
        assertEquals(LocalDate.of(2026, 12, 28), week.start)
        assertEquals(LocalDate.of(2027, 1, 4), week.end)
        val month = ListeningPeriod(ListeningScale.MONTH, LocalDate.of(2028, 3, 31)).move(-1)
        assertEquals(LocalDate.of(2028, 2, 29), month.anchor)
        assertEquals(LocalDate.of(2028, 3, 1), month.end)
        val year = ListeningPeriod(ListeningScale.YEAR, month.anchor)
        assertEquals(366L, ChronoUnit.DAYS.between(year.start, year.end))
    }

    @Test fun cacheAndImportedNcmShareAnIdentityButDifferentRecordingsDoNot() {
        val original = Song("file-a", "file:///a", folder = "m", title = "同名曲", neteaseId = "123",
            primaryArtistId = "456", primaryArtistName = "主艺人", artist = "主艺人 / 合作艺人", albumId = "789")
        val online = original.copy(id = "netease:123", uri = "ami-cloud://song/123")
        assertEquals(original.listeningTrack().key, online.listeningTrack().key)
        assertEquals(original.listeningTrack().key, online.copy(neteaseId = null).listeningTrack().key)
        assertNotEquals(original.listeningTrack().key, online.copy(neteaseId = "124").listeningTrack().key)
        assertEquals("netease-artist:456", original.listeningTrack().artistKey)
        assertEquals("主艺人", original.listeningTrack().artist)
        assertEquals("netease-album:789", original.listeningTrack().albumKey)
        assertNotEquals(original.copy(neteaseId = null).listeningTrack().key, original.copy(id = "other", neteaseId = null).listeningTrack().key)
    }

    @Test fun roundedLegendAlwaysAddsToOneHundredAndHandlesEmptyData() {
        assertEquals(listOf(334, 333, 333), listeningPercentages(listOf(1L, 1L, 1L)))
        assertEquals(1000, listeningPercentages(listOf(19L, 17L, 13L, 11L, 7L, 1L)).sum())
        assertEquals(emptyList<Int>(), listeningPercentages(emptyList()))
        assertEquals(listOf(0, 0), listeningPercentages(listOf(0L, 0L)))
        assertEquals("1 小时 2 分", listeningTimeLabel(3_720_000))
    }
}
