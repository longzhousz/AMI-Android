package com.longzhousz.ami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
