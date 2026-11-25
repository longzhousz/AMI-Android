package com.longzhousz.ami.data

import com.longzhousz.ami.core.CloudLikes
import com.longzhousz.ami.core.CloudPlaylist
import com.longzhousz.ami.core.CloudTrack
import com.longzhousz.ami.core.CloudTracks
import com.longzhousz.ami.core.ErrorCode
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.takeWhile

private const val MAX_SEARCH_TRACKS = 20_000

internal data class CloudLibrarySearchResult(
    val tracks: PersistentList<CloudTrack> = persistentListOf(),
    val collections: PersistentList<CloudPlaylist> = persistentListOf(),
    val loading: Boolean = true,
    val tracksLimited: Boolean = false,
    val failedOffsets: PersistentMap<String, Int> = persistentMapOf(),
)

internal fun CloudMusic.searchPersonalLibrary(
    collections: List<CloudPlaylist>,
    term: String,
    session: Long,
    favorites: Boolean,
    previous: CloudLibrarySearchResult? = null,
) = flow {
    require(direct && term.isNotBlank())
    requireSearchSession(session)
    val sources = collections.distinctBy { it.key }
    var result =
        previous?.copy(loading = true, failedOffsets = persistentMapOf()) ?: CloudLibrarySearchResult(
            collections = if (favorites) persistentListOf() else sources.filter { it.matches(term) }.toPersistentList(),
        )
    val initial = result
    val matches = SearchMatches(initial)
    emit(initial)

    val reading = if (previous == null) sources else sources.filter { it.key in previous.failedOffsets }
    val remaining = mutableListOf<SearchSource>()
    for (collection in reading) {
        currentCoroutineContext().ensureActive()
        if (!result.needsTracks(collection)) continue
        val start = previous?.failedOffsets?.get(collection.key) ?: 0
        val next =
            scanCachedSearchTracks(collection, favorites, start, session) { page ->
                result = matches.addPage(result, page.tracks, collection, term, likes.value.takeIf { favorites })
                result.needsTracks(collection)
            }
        if (next != null) remaining.add(SearchSource(collection, next))
    }
    if (result !== initial) emit(result)
    if (remaining.isNotEmpty()) {
        // Typing only delays network reads. Previously read tracks can match on each edit.
        if (previous == null) delay(150)
        result = scanMissingSearchTracks(remaining, result, matches, term, session, favorites) { emit(it) }
    }

    requireSearchSession(session)
    emit(result.copy(loading = false))
}.flowOn(Dispatchers.IO).buffer(0)

private data class SearchSource(
    val collection: CloudPlaylist,
    val offset: Int,
)

private sealed interface SearchRead {
    val collection: CloudPlaylist

    data class Page(
        override val collection: CloudPlaylist,
        val value: CloudTracks,
    ) : SearchRead

    data class Complete(
        override val collection: CloudPlaylist,
        val failure: Exception? = null,
    ) : SearchRead
}

@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun CloudMusic.scanMissingSearchTracks(
    sources: List<SearchSource>,
    initial: CloudLibrarySearchResult,
    matches: SearchMatches,
    term: String,
    session: Long,
    favorites: Boolean,
    onMatch: suspend (CloudLibrarySearchResult) -> Unit,
): CloudLibrarySearchResult {
    // A single collector owns result order, deduplication and the offsets reported on failure.
    val result = MutableStateFlow(initial)
    val pending = sources.associate { it.collection to it.offset }.toMutableMap()
    val errorCode = if (favorites) ErrorCode.ONLINE_FAVORITES else ErrorCode.ONLINE_LIBRARY
    sources
        .asFlow()
        .flatMapMerge(concurrency = 3) { source ->
            readSearchSource(source, favorites, session) { result.value.needsTracks(source.collection) }
        }.buffer(0)
        .takeWhile { read ->
            requireSearchSession(session)
            val failure = (read as? SearchRead.Complete)?.failure
            if (failure != null) AppDiagnostics.errors.record(errorCode, failure)
            failure !is NeteaseLoginExpired
        }.collect { read ->
            val collection = read.collection
            val current = result.value
            when (read) {
                is SearchRead.Page -> {
                    if (read.value.more) pending[collection] = read.value.nextOffset else pending.remove(collection)
                    val updated = matches.addPage(current, read.value.tracks, collection, term, likes.value.takeIf { favorites })
                    if (updated !== current) {
                        result.value = updated
                        onMatch(updated)
                    }
                }

                is SearchRead.Complete -> {
                    val offset = pending.remove(collection)
                    if (read.failure != null && offset != null) {
                        result.value = current.copy(failedOffsets = current.failedOffsets.put(collection.key, offset))
                    }
                }
            }
        }
    // An expired login stops the in-flight reads. Retry their last unread offsets after login.
    val current = result.value
    var failed = current.failedOffsets
    for ((collection, offset) in pending) {
        if (current.needsTracks(collection)) failed = failed.put(collection.key, offset)
    }
    return current.copy(failedOffsets = failed)
}

@Suppress("TooGenericExceptionCaught") // A failed collection can be retried without discarding other matches.
private fun CloudMusic.readSearchSource(
    source: SearchSource,
    favorites: Boolean,
    session: Long,
    needsTracks: () -> Boolean,
) = flow {
    val collection = source.collection
    val failure =
        try {
            if (needsTracks()) {
                scanCollectionTracks(collection, favorites, source.offset, session) { page ->
                    emit(SearchRead.Page(collection, page))
                    needsTracks()
                }
            }
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            error
        }
    emit(SearchRead.Complete(collection, failure))
}

private fun CloudLibrarySearchResult.needsTracks(collection: CloudPlaylist): Boolean = !tracksLimited || (collection.kind == "album" && collections.none { it.key == collection.key })

private class SearchMatches(
    initial: CloudLibrarySearchResult,
) {
    private val songIds = initial.tracks.mapTo(HashSet()) { it.id }
    private val collectionKeys = initial.collections.mapTo(HashSet()) { it.key }

    suspend fun addPage(
        result: CloudLibrarySearchResult,
        tracks: List<CloudTrack>,
        collection: CloudPlaylist,
        term: String,
        likes: CloudLikes?,
    ): CloudLibrarySearchResult {
        val isAlbum = collection.kind == "album"
        val collectionTitleMatches = isAlbum && collection.title.contains(term, ignoreCase = true)
        val additions = mutableListOf<CloudTrack>()
        var matched = false
        var limited = result.tracksLimited
        for (track in tracks) {
            currentCoroutineContext().ensureActive()
            if (!track.matches(term, collectionTitleMatches) || !likes.includes(track)) continue
            matched = true
            if (limited || track.id in songIds) continue
            if (songIds.size == MAX_SEARCH_TRACKS) {
                limited = true
            } else {
                songIds.add(track.id)
                additions.add(track)
            }
        }
        val newAlbum = isAlbum && matched && collectionKeys.add(collection.key)
        if (additions.isEmpty() && !newAlbum && limited == result.tracksLimited) return result
        return result.copy(
            tracks = result.tracks.addAll(additions),
            collections = if (newAlbum) result.collections.add(collection) else result.collections,
            tracksLimited = limited,
        )
    }
}

private fun CloudPlaylist.matches(term: String): Boolean = title.contains(term, ignoreCase = true) || artist.contains(term, ignoreCase = true)

private fun CloudTrack.matches(
    term: String,
    collectionTitleMatches: Boolean,
): Boolean =
    title.contains(term, ignoreCase = true) || artist.contains(term, ignoreCase = true) ||
        album.contains(term, ignoreCase = true) || (album.isBlank() && collectionTitleMatches)

private fun CloudLikes?.includes(track: CloudTrack): Boolean = this == null || !ready || track.originalId in ids || track.originalId in pending
