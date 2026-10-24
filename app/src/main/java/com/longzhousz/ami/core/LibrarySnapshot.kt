package com.longzhousz.ami.core

import java.util.Locale

class LibrarySnapshot(val songs: List<Song>) {
    val albums = albumsOf(songs)
    val songsById = songs.associateBy { it.id }
    val albumsByKey = albums.associateBy { it.key }
    private val songTerms = songs.associate { it.id to "${it.title} ${it.album} ${it.artist}".lowercase(Locale.ROOT) }
    private val albumTerms = albums.associate { it.key to "${it.title} ${it.artist} ${it.songs.joinToString { song -> song.title }}".lowercase(Locale.ROOT) }
    private val favorites = songs.filter { it.favorite }
    private val recentSongs by lazy { songs.sortedByDescending { it.importedAt } }
    private val recentAlbums by lazy { albums.sortedByDescending { album -> album.songs.maxOf { it.importedAt } } }
    fun search(query: String, onlyFavorites: Boolean, recent: Boolean): LibrarySearchResult {
        val term = query.trim().lowercase(Locale.ROOT)
        val source = if (recent) recentSongs else if (onlyFavorites) favorites else songs
        val tracks = if (term.isEmpty() && (!onlyFavorites || !recent)) source else source.filter {
            (!onlyFavorites || it.favorite) && (term.isEmpty() || songTerms.getValue(it.id).contains(term))
        }
        val sourceAlbums = if (recent) recentAlbums else albums
        val matchedAlbums = if (term.isEmpty()) sourceAlbums else sourceAlbums.filter { albumTerms.getValue(it.key).contains(term) }
        return LibrarySearchResult(tracks, matchedAlbums)
    }
}

class LibrarySearchResult(val songs: List<Song>, val albums: List<Album>)
