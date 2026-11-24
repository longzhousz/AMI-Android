package com.longzhousz.ami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudLibraryMergeTest {
    private fun playlist(
        id: String,
        kind: String,
    ) = CloudPlaylist(id, id, null, 1, kind)

    @Test fun playlistsPublishCreatedAndLikedWithoutAwaitingAlbums() {
        val state = CloudLibraryState().withPlaylists(listOf(playlist("liked", "liked"), playlist("mine", "created")))
        assertEquals(
            setOf("created:mine"),
            state.library.collections
                .map { it.key }
                .toSet(),
        )
        assertEquals("liked:liked", state.library.liked?.key)
        assertFalse(state.loaded)
        assertTrue(state.playlistsLoaded)
    }

    @Test fun albumsMergeWithoutLosingTheCreatedOrLikedPlaylists() {
        val afterPlaylists = CloudLibraryState().withPlaylists(listOf(playlist("liked", "liked"), playlist("mine", "created")))
        val merged = afterPlaylists.withAlbums(listOf(playlist("album", "album")))
        assertEquals(
            setOf("created:mine", "album:album"),
            merged.library.collections
                .map { it.key }
                .toSet(),
        )
        assertEquals("liked:liked", merged.library.liked?.key)
        assertTrue(merged.albumsLoaded)
        assertTrue(merged.loaded)
    }

    @Test fun aFailedPlaylistPageDoesNotMarkTheLibraryLoaded() {
        val state = CloudLibraryState(playlistsError = "歌单暂时无法刷新", error = "曲库暂时无法刷新")
        assertFalse(state.loaded)
    }

    @Test fun defaultOrderPreservesTheServerSequenceAcrossIndependentResponses() {
        val albums = listOf("38", "2", "17").map { playlist(it, "album") }
        val created = listOf("2", "1").map { playlist(it, "created") }
        val albumsResponse = albums + albums.first()
        val playlistsResponse = created + created.last()
        val albumsFirst = CloudLibraryState().withAlbums(albumsResponse).withPlaylists(playlistsResponse)
        val playlistsFirst = CloudLibraryState().withPlaylists(playlistsResponse).withAlbums(albumsResponse)
        assertEquals(albums + created, albumsFirst.library.collections)
        assertEquals(albumsFirst.library, playlistsFirst.library)
        val updated = listOf(albums[2], albums[0], albums[1])
        assertEquals(updated + created, albumsFirst.withAlbums(updated).library.collections)
    }

    @Test fun randomSelectionMovesTheFirstAlbumAndKeepsThatOrderWhenTheSameAlbumsReturn() {
        val albums = listOf("38", "2", "17").map { playlist(it, "album") }
        val created = listOf("2", "1").map { playlist(it, "created") }
        val original = CloudLibraryState().withPlaylists(created).withAlbums(albums)
        val shuffled = original.withAlbumOrder(OnlineAlbumOrder.Random)
        val reshuffled = shuffled.withAlbumOrder(OnlineAlbumOrder.Random)
        assertNotEquals(original.library.collections.first(), shuffled.library.collections.first())
        assertNotEquals(shuffled.library.collections.first(), reshuffled.library.collections.first())
        assertEquals(
            albums.toSet(),
            reshuffled.library.collections
                .take(albums.size)
                .toSet(),
        )
        assertEquals(created, reshuffled.library.collections.takeLast(created.size))
        val refreshedAlbums = albums.reversed().map { it.copy(count = 5) }
        val refreshed = reshuffled.withAlbums(refreshedAlbums, OnlineAlbumOrder.Random).withPlaylists(created)
        assertEquals(reshuffled.library.collections.map { it.key }, refreshed.library.collections.map { it.key })
        assertEquals(
            List(albums.size) { 5 },
            refreshed.library.collections
                .take(albums.size)
                .map { it.count },
        )
        assertEquals(refreshedAlbums + created, refreshed.withAlbumOrder(OnlineAlbumOrder.Default).library.collections)
    }
}
