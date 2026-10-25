package com.longzhousz.ami.core

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import java.util.Locale

/** Immutable views share metadata/search/order indexes across favorite changes. */
@Immutable
class LibrarySnapshot private constructor(private val data: SnapshotData) {
    constructor(songs: List<Song>) : this(snapshotData(songs))
    val songs: PersistentList<Song> get() = data.songs
    val albums: PersistentList<Album> get() = data.albums
    val songsById: PersistentMap<String, Song> get() = data.songsById
    val albumsByKey: PersistentMap<String, Album> get() = data.albumsByKey
    private val recentSongs by lazy { data.index.recentSongIds.map(songsById::getValue).toPersistentList() }
    private val recentAlbums by lazy { data.index.recentAlbumKeys.map(albumsByKey::getValue).toPersistentList() }

    fun withFavorite(id: String, favorite: Boolean): LibrarySnapshot {
        val before = songsById[id] ?: return this
        if (before.favorite == favorite) return this
        val song = before.copy(favorite = favorite)
        val position = data.index.songPositions.getValue(id)
        val (albumPosition, trackPosition) = data.index.albumTracks.getValue(id)
        val oldAlbum = albums[albumPosition]
        val album = oldAlbum.copy(songs = oldAlbum.songs.toPersistentList().set(trackPosition, song))
        val favoritePosition = data.favorites.binarySearch { data.index.songPositions.getValue(it.id).compareTo(position) }
        val favorites = if (favorite) data.favorites.add(-favoritePosition - 1, song) else data.favorites.removeAt(favoritePosition)
        return LibrarySnapshot(data.copy(
            songs = songs.set(position, song), songsById = songsById.put(id, song),
            albums = albums.set(albumPosition, album), albumsByKey = albumsByKey.put(album.key, album), favorites = favorites,
        ))
    }

    fun search(query: String, onlyFavorites: Boolean, recent: Boolean): LibrarySearchResult {
        val term = query.trim().lowercase(Locale.ROOT)
        val source = if (recent) recentSongs else if (onlyFavorites) data.favorites else songs
        val tracks = if (term.isEmpty() && (!onlyFavorites || !recent)) source else source.filter {
            (!onlyFavorites || it.favorite) && (term.isEmpty() || data.index.songTerms.getValue(it.id).contains(term))
        }.toPersistentList()
        val sourceAlbums = if (recent) recentAlbums else albums
        val matchedAlbums = if (term.isEmpty()) sourceAlbums else sourceAlbums.filter { data.index.albumTerms.getValue(it.key).contains(term) }.toPersistentList()
        return LibrarySearchResult(tracks, matchedAlbums)
    }
}

@Immutable
class LibrarySearchResult(val songs: PersistentList<Song>, val albums: PersistentList<Album>)

private data class SnapshotData(
    val songs: PersistentList<Song>, val albums: PersistentList<Album>,
    val songsById: PersistentMap<String, Song>, val albumsByKey: PersistentMap<String, Album>,
    val favorites: PersistentList<Song>, val index: LibraryIndex,
)

private class LibraryIndex(songs: List<Song>, albums: List<Album>) {
    val songPositions = songs.withIndex().associate { it.value.id to it.index }
    val albumTracks = buildMap { albums.forEachIndexed { albumIndex, album -> album.songs.forEachIndexed { trackIndex, song -> put(song.id, albumIndex to trackIndex) } } }
    val songTerms = songs.associate { it.id to "${it.title} ${it.album} ${it.artist}".lowercase(Locale.ROOT) }
    val albumTerms = albums.associate { it.key to "${it.title} ${it.artist} ${it.songs.joinToString { song -> song.title }}".lowercase(Locale.ROOT) }
    val recentSongIds by lazy { songs.sortedByDescending { it.importedAt }.map { it.id } }
    val recentAlbumKeys by lazy { albums.sortedByDescending { album -> album.songs.maxOf { it.importedAt } }.map { it.key } }
}

private fun snapshotData(source: List<Song>): SnapshotData {
    val songs = source.toPersistentList()
    val albums = albumsOf(songs).map { it.copy(songs = it.songs.toPersistentList()) }.toPersistentList()
    return SnapshotData(songs, albums, songs.associateBy { it.id }.toPersistentMap(), albums.associateBy { it.key }.toPersistentMap(),
        songs.filter { it.favorite }.toPersistentList(), LibraryIndex(songs, albums))
}
