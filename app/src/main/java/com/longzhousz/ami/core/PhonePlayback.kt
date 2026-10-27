package com.longzhousz.ami.core

import java.net.URI
import java.text.Normalizer
import java.util.Locale

/** Android's media clock uses elapsed realtime, not wall time. */
fun phonePosition(position: Long, updatedAt: Long, now: Long, speed: Float, playing: Boolean, duration: Long): Long {
    val elapsed = if (playing && speed.isFinite() && speed > 0 && updatedAt > 0) (now - updatedAt).coerceAtLeast(0) * speed.toDouble() else 0.0
    return (position.coerceAtLeast(0).toDouble() + elapsed).coerceIn(0.0, if (duration > 0) duration.toDouble() else Long.MAX_VALUE.toDouble()).toLong()
}

fun phoneSongId(raw: String?): String? {
    NeteaseLyrics.songId(raw)?.let { return it }
    return runCatching {
        val uri = URI(raw.orEmpty())
        when {
            uri.scheme == "orpheus" && uri.host == "song" -> NeteaseLyrics.songId(uri.path.removePrefix("/"))
            uri.scheme == "https" && uri.host == "music.163.com" && uri.path == "/song" ->
                uri.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("id=") }?.removePrefix("id=")?.let(NeteaseLyrics::songId)
            else -> null
        }
    }.getOrNull()
}

private fun lyricName(raw: String) = Normalizer.normalize(raw, Normalizer.Form.NFKC).lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
fun phoneLyricsMatch(song: Song, title: String, artist: String, duration: Long): Boolean = song.title.isNotBlank() && song.artist.isNotBlank() &&
    lyricName(song.title) == lyricName(title) && lyricName(song.artist) == lyricName(artist) &&
    song.duration > 0 && duration > 0 && kotlin.math.abs(song.duration - duration) <= 2000
