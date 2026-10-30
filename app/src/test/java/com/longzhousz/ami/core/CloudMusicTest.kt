package com.longzhousz.ami.core

import org.junit.Assert.*
import org.junit.Test

class CloudMusicTest {
    @Test fun heartChangesPatchTheLoadedFavoritesPrefixWithoutLosingPaginationOrChangingTheQueue() {
        fun track(id: String) = CloudTrack(id, id, id, "artist", "album", 1000, null, true)
        val queue = (1..30).map { track(it.toString()) }
        val original = CloudTracks(queue, 30, true)
        val added = original.withLike(track("99"), true)
        assertEquals(listOf("99") + queue.map { it.id }, added.tracks.map { it.id })
        assertEquals(31, added.nextOffset); assertTrue(added.more)
        val removed = added.withLike(track("15"), false)
        assertEquals(30, removed.nextOffset); assertFalse(removed.tracks.any { it.id == "15" })
        assertEquals(removed, removed.withLike(track("200"), false))
        assertEquals(30, removed.withLike(track("99"), true).nextOffset)
        assertSame(queue, original.tracks); assertEquals(30, queue.size)
        assertEquals((1..30).map(Int::toString), queue.map { it.id })
    }
    @Test fun aFullFavoritesPrefixStaysBoundedWhenAnotherSongIsLiked() {
        val tracks = (1..20000).map { CloudTrack("$it", "$it", "$it", "", "", 0, null, true) }
        val page = CloudTracks(tracks, 20000, false).withLike(tracks.first().copy(id = "99999", originalId = "99999"), true)
        assertEquals(20000, page.tracks.size); assertEquals(20000, page.nextOffset)
        assertEquals("99999", page.tracks.first().id); assertFalse(page.more)
    }
    @Test fun everyShelfRefreshChangesItsFirstCardAndPreservesAllCards() {
        var cards = (1..12).map { CloudPlaylist("$it", "$it", null, 1, "album") }
        repeat(100) {
            val next = shuffledShelf(cards, cards)
            assertNotEquals(cards.first().key, next.first().key)
            assertEquals(cards.toSet(), next.toSet())
            cards = next
        }
    }
    @Test fun qualityLabelsDescribeTheDeliveredSourceRatherThanTheRequestedTier() {
        assertEquals("极高 · 320 kbps", AudioQuality("a", "exhigh", "mp3", 320000).label)
        assertEquals("无损", AudioQuality("a", "lossless", "flac", 1200000, cached = true).label)
        assertEquals("待确认", AudioQuality("a", null, "unknown").label)
    }
    @org.junit.Test fun shelfRefreshChangesCardsWithoutMergingAlbumAndPlaylistIds() {
        val album = CloudPlaylist("1234", "专辑", null, 2, "album")
        val playlist = CloudPlaylist("1234", "歌单", null, 3, "created")
        val previous = listOf(album, playlist)
        val fresh = shuffledShelf(previous + album, previous)
        org.junit.Assert.assertEquals(previous.reversed(), fresh)
        org.junit.Assert.assertEquals(listOf(album, playlist), previous)
        org.junit.Assert.assertEquals(emptyList<CloudPlaylist>(), shuffledShelf(emptyList(), emptyList()))
    }
    private val token = "A".repeat(43)
    @Test fun pairingAcceptsPrivateLanAndHttpsWithoutLeakingTokenIntoBaseUrl() {
        listOf("http://192.168.1.2:17863", "http://10.0.2.2:17863", "http://172.16.1.2:17863", "http://localhost:17863", "https://bridge.example.test").forEach { origin ->
            val parsed = CloudConnection.parse("$origin/#$token")
            assertEquals(origin, parsed.baseUrl); assertEquals(token, parsed.token)
        }
    }
    @Test fun pairingRejectsPublicCleartextRedirectsMissingTokensAndHeaderInjection() {
        listOf("http://8.8.8.8/#$token", "http://172.32.1.2/#$token", "http://my-pc.example/#$token", "https://name:password@host/#$token",
            "https://host/path/#$token", "https://host/?token=x#$token", "https://host/", "http://10.300.0.1/#$token", "http://192.168.1.2.example.test/#$token",
            "http://010.0.0.1/#$token", "https://host/#$token%0D%0A").forEach {
            assertTrue(it, runCatching { CloudConnection.parse(it) }.isFailure)
        }
    }
    @Test fun cdnValidationPreservesSignedQueryButUpgradesHttpsAndRejectsOtherOrigins() {
        assertEquals("https://iot202.music.126.net/a.mp3?sign=a%2Bb", cloudMediaUrl("http://iot202.music.126.net/a.mp3?sign=a%2Bb"))
        assertEquals("https://p1.music.126.net/a.jpg", cloudMediaUrl("http://p1.music.126.net/a.jpg", true))
        listOf("https://p1.music.126.net.evil/a", "https://user@p1.music.126.net/a", "file:///a", "https://p1.music.126.net:8080/a").forEach {
            assertTrue(runCatching { cloudMediaUrl(it) }.isFailure)
        }
        assertTrue(runCatching { cloudMediaUrl("https://iot202.music.126.net/a", true) }.isFailure)
    }
    @Test fun onlineQueueUsesStableResourceIdsInsteadOfExpiringPlaybackUrls() {
        val track = CloudTrack("A".repeat(32), "1234", "歌曲", "艺人", "专辑", 180000, null, true)
        val song = track.song()
        assertTrue(song.isCloudSong()); assertEquals("1234", song.neteaseId)
        assertEquals("ami-cloud://song/" + "A".repeat(32), song.uri)
        assertEquals(song.uri, song.sourceUri); assertTrue(song.hasLyrics)
    }
}
