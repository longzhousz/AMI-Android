package com.longzhousz.ami.core

import org.junit.Assert.*
import org.junit.Test

class CloudMusicTest {
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
