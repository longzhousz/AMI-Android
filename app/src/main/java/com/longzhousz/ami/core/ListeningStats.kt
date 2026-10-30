package com.longzhousz.ami.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

enum class ListeningScale(val label: String) { WEEK("周"), MONTH("月"), YEAR("年") }

data class ListeningPeriod(val scale: ListeningScale, val anchor: LocalDate) {
    val start: LocalDate get() = when (scale) {
        ListeningScale.WEEK -> anchor.minusDays(anchor.dayOfWeek.value - 1L)
        ListeningScale.MONTH -> anchor.withDayOfMonth(1)
        ListeningScale.YEAR -> anchor.withDayOfYear(1)
    }
    val end: LocalDate get() = when (scale) {
        ListeningScale.WEEK -> start.plusWeeks(1)
        ListeningScale.MONTH -> start.plusMonths(1)
        ListeningScale.YEAR -> start.plusYears(1)
    }
    fun move(amount: Long) = copy(anchor = when (scale) {
        ListeningScale.WEEK -> anchor.plusWeeks(amount)
        ListeningScale.MONTH -> anchor.plusMonths(amount)
        ListeningScale.YEAR -> anchor.plusYears(amount)
    })
    val label: String get() = when (scale) {
        ListeningScale.WEEK -> "${start.year}.${start.monthValue}.${start.dayOfMonth} — ${if (start.year == end.minusDays(1).year) "" else "${end.minusDays(1).year}."}${end.minusDays(1).monthValue}.${end.minusDays(1).dayOfMonth}"
        ListeningScale.MONTH -> "${anchor.year} 年 ${anchor.monthValue} 月"
        ListeningScale.YEAR -> "${anchor.year} 年"
    }
}

/** A compact identity, never an audio URL or a copy of the whole playback queue. */
data class ListeningTrack(
    val key: String, val title: String, val artistKey: String, val artist: String,
    val albumKey: String, val album: String, val cover: String? = null,
)

private val musicNumber = Regex("[1-9][0-9]{0,19}")
private fun normalizedCredit(value: String) = value.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

fun Song.listeningTrack(): ListeningTrack {
    val mainArtist = (primaryArtistName?.trim()?.takeIf { it.isNotEmpty() } ?: artist.trim().ifBlank { "未知艺人" }).take(200)
    val albumName = album.trim().ifBlank { "未知专辑" }.take(200)
    val artistId = primaryArtistId?.takeIf { musicNumber.matches(it) }
    val recordId = albumId?.takeIf { musicNumber.matches(it) }
    return ListeningTrack(
        (neteaseId?.takeIf { musicNumber.matches(it) } ?: id.removePrefix("netease:").takeIf { id.startsWith("netease:") && musicNumber.matches(it) })
            ?.let { "netease:$it" } ?: "local:${digest(id)}",
        title.trim().ifBlank { "未知歌曲" }.take(200),
        artistId?.let { "netease-artist:$it" } ?: "artist:${digest(normalizedCredit(mainArtist))}", mainArtist.take(200),
        recordId?.let { "netease-album:$it" } ?: if (albumName == "未知专辑") "album:unknown" else
            "album:${digest(normalizedCredit(albumName) + "\n" + normalizedCredit(albumArtist.ifBlank { mainArtist }.take(200)))}",
        albumName.take(200), cover?.takeIf { it.length <= 2048 },
    )
}

data class ListeningSlice(val track: ListeningTrack, val day: LocalDate, val hour: Int, val millis: Long)

/** Wall time selects calendar buckets; only monotonic elapsed time can increase a total. */
internal class ListeningAccumulator {
    private var track: ListeningTrack? = null
    private var playing = false
    private var elapsed = 0L
    private var wall = 0L
    private var zone = ZoneId.of("UTC")

    fun reset() { track = null; playing = false }

    fun update(next: ListeningTrack?, isPlaying: Boolean, now: Long, wallTime: Long, timeZone: ZoneId,
        record: (ListeningSlice) -> Unit) {
        val previous = track
        if (playing && previous != null && now > elapsed) {
            var cursor = Instant.ofEpochMilli(wall)
            val end = cursor.plusMillis(now - elapsed)
            while (cursor < end) {
                val local = cursor.atZone(zone)
                val boundary = local.withMinute(0).withSecond(0).withNano(0).plusHours(1).toInstant()
                val until = minOf(boundary, end)
                record(ListeningSlice(previous, local.toLocalDate(), local.hour, until.toEpochMilli() - cursor.toEpochMilli()))
                cursor = until
            }
        }
        track = next; playing = isPlaying && next != null; elapsed = now; wall = wallTime; zone = timeZone
    }
}

data class ListeningDay(val date: LocalDate, val millis: Long, val recorded: Boolean)
data class ListeningRank(val key: String, val title: String, val millis: Long, val subtitle: String = "", val cover: String? = null)
data class ListeningReport(
    val period: ListeningPeriod, val firstDay: LocalDate?, val totalMillis: Long,
    val songs: Int, val days: List<ListeningDay>, val topSongs: List<ListeningRank>,
    val artists: List<ListeningRank>, val albums: List<ListeningRank>, val hours: List<Long>,
    val newMillis: Long, val previousMillis: Long?, val incomplete: Boolean = false,
) {
    val activeDays get() = days.count { it.millis > 0 }
    val longestStreak: Int get() {
        var longest = 0; var current = 0
        days.forEach { current = if (it.millis > 0) current + 1 else 0; longest = maxOf(longest, current) }
        return longest
    }
}

fun listeningTimeLabel(millis: Long): String {
    val minutes = millis.coerceAtLeast(0) / 60_000
    return when {
        millis <= 0 -> "0 分钟"
        minutes == 0L -> if (millis < 1000) "不足 1 秒" else "${millis / 1000} 秒"
        minutes < 60 -> "$minutes 分钟"
        minutes % 60 == 0L -> "${minutes / 60} 小时"
        else -> "${minutes / 60} 小时 ${minutes % 60} 分"
    }
}

/** Tenths of a percent; the legend always adds to 100%, including rounded small slices. */
fun listeningPercentages(values: List<Long>): List<Int> {
    val total = values.sum().coerceAtLeast(1)
    val raw = values.map { it.toDouble() / total * 1000 }
    val result = raw.map { it.toInt() }.toMutableList()
    if (values.sum() > 0) raw.indices.sortedByDescending { raw[it] - result[it] }.take(1000 - result.sum()).forEach { result[it]++ }
    return result
}
