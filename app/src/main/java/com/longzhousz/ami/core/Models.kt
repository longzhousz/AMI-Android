package com.longzhousz.ami.core

import java.security.MessageDigest
import java.text.Collator
import java.util.Locale

data class Song(
    val id: String,
    val uri: String,
    val sourceUri: String = uri,
    val folder: String,
    val title: String,
    val artist: String = "未知艺人",
    val album: String = "未知专辑",
    val albumArtist: String = "",
    val track: Int = 0,
    val disc: Int = 1,
    val duration: Long = 0,
    val format: String = "",
    val cover: String? = null,
    val lyrics: String = "",
    val start: Long = 0,
    val end: Long? = null,
    val order: Int? = null,
    val favorite: Boolean = false,
    val importedAt: Long = System.currentTimeMillis(),
    val neteaseId: String? = null,
    val lyricTranslation: String = "",
    val lyricsSource: String = "",
    val manualLyrics: Boolean = false,
    val lyricsAvailable: Boolean? = null,
    val lyricsVersion: String = "",
    val primaryArtistId: String? = null,
    val primaryArtistName: String? = null,
    val albumId: String? = null,
) {
    val hasLyrics: Boolean get() = lyricsAvailable ?: lyrics.isNotBlank()
}

data class Album(val key: String, val title: String, val artist: String, val songs: List<Song>) {
    val cover get() = songs.firstNotNullOfOrNull { it.cover }
    val duration get() = songs.sumOf { it.duration }
}

fun albumsOf(songs: List<Song>): List<Album> {
    fun norm(s: String) = s.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
    fun scope(s: Song) = s.folder.replace(Regex("(?i)/((cd|disc|disk)\\s*\\d+)$"), "")
    val grouped = songs.groupBy {
        val artist = if (it.albumArtist.isNotBlank()) "artist:${norm(it.albumArtist)}" else "folder:${scope(it)}"
        "${norm(it.album)}::$artist"
    }
    val collator = Collator.getInstance(Locale.CHINA)
    return grouped.map { (key, tracks) ->
        val artists = tracks.map { it.albumArtist.ifBlank { it.artist } }.distinct()
        Album(key, tracks.first().album, if (artists.size > 3) "群星" else artists.joinToString("、"),
            tracks.sortedWith(compareBy<Song> { it.order ?: (it.disc * 10000 + if (it.track > 0) it.track else 9999) }
                .thenBy { it.importedAt }.thenBy { it.id }))
    }.sortedWith { a, b -> collator.compare(a.title, b.title) }
}

fun digest(value: String): String = digest(value.toByteArray(Charsets.UTF_8))
fun digest(value: ByteArray): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(value)
    val hex = "0123456789abcdef"
    return CharArray(bytes.size * 2).also { chars ->
        bytes.forEachIndexed { index, byte ->
            val n = byte.toInt() and 255
            chars[index * 2] = hex[n ushr 4]
            chars[index * 2 + 1] = hex[n and 15]
        }
    }.concatToString()
}

fun durationLabel(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return "%d:%02d".format(Locale.ROOT, seconds / 60, seconds % 60)
}
