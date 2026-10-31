package com.longzhousz.ami.core

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class SmartShuffleTest {
    @Test fun anchoredPermutationDelaysRecentSongsAndHandlesSmallOrHomogeneousQueues() {
        val tracks = List(1000) { ShuffleTrack("$it", "artist${it / 20}", "album${it / 10}") }
        val recent = (1..8).map(Int::toString)
        repeat(20) { seed ->
            val result = balancedShuffle(tracks, 321, recent, Random(seed))
            assertEquals(321, result.first())
            assertEquals(tracks.indices.toSet(), result.toSet())
            assertFalse(result.slice(1..8).any { tracks[it].id in recent })
        }
        for (count in 0..30) {
            val same = List(count) { ShuffleTrack("$it", "same", "same") }
            assertEquals(same.indices.toSet(), balancedShuffle(same, 0, emptyList(), Random(count)).toSet())
            val unknown = same.map { it.copy(artist = "", album = "") }
            assertEquals(unknown.indices.toSet(), balancedShuffle(unknown, 0, recent, Random(count)).toSet())
        }
    }

    @Test fun mixedQueuesHaveFewerArtistAndAlbumRunsWithoutChangingSourceOrder() {
        val tracks = List(120) { ShuffleTrack("$it", "artist${it / 12}", "album${it / 6}") }
        val original = tracks.toList()
        var balanced = 0; var ordinary = 0
        fun runs(order: List<Int>) = order.zipWithNext().count { (a, b) ->
            tracks[a].artist == tracks[b].artist || tracks[a].album == tracks[b].album
        }
        repeat(40) { seed ->
            balanced += runs(balancedShuffle(tracks, 0, emptyList(), Random(seed)).toList())
            ordinary += runs(tracks.indices.shuffled(Random(seed)))
        }
        assertTrue("Clumped neighbors should be substantially reduced: $balanced / $ordinary", balanced * 3 < ordinary)
        assertEquals(original, tracks)
    }
}
