package com.longzhousz.ami.core

import kotlin.random.Random

internal data class ShuffleTrack(val id: String, val artist: String, val album: String)

/** One permutation, anchored at the current song. Bounded lookahead keeps large queues linear. */
internal fun balancedShuffle(tracks: List<ShuffleTrack>, current: Int, recent: List<String>, random: Random = Random.Default): IntArray {
    if (tracks.isEmpty()) return IntArray(0)
    val anchor = current.coerceIn(tracks.indices)
    val remaining = IntArray(tracks.size - 1) { if (it < anchor) it else it + 1 }.apply { shuffle(random) }
    val order = IntArray(tracks.size)
    order[0] = anchor
    val heard = recent.takeLast(8).toSet()
    var previousArtist = tracks[anchor].artist
    var beforeArtist = ""
    var previousAlbum = tracks[anchor].album
    var count = remaining.size
    for (position in 1 until tracks.size) {
        var best = 0
        var bestScore = Int.MAX_VALUE
        // Search at most 24 candidates; a single-artist queue must still make progress.
        for (candidate in 0 until minOf(count, 24)) {
            val track = tracks[remaining[candidate]]
            val score = (if (position < 9 && track.id in heard) 12 else 0) +
                (if (track.artist.isNotEmpty() && track.artist == previousArtist) 5 else 0) +
                (if (track.artist.isNotEmpty() && track.artist == beforeArtist) 2 else 0) +
                (if (track.album.isNotEmpty() && track.album == previousAlbum) 3 else 0)
            if (score < bestScore) { best = candidate; bestScore = score }
            if (score == 0) break
        }
        val chosen = remaining[best]
        order[position] = chosen
        remaining[best] = remaining[--count]
        beforeArtist = previousArtist
        previousArtist = tracks[chosen].artist
        previousAlbum = tracks[chosen].album
    }
    return order
}
