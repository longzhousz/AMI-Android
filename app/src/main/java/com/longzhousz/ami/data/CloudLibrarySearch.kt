package com.longzhousz.ami.data

import com.longzhousz.ami.core.CloudLikes
import com.longzhousz.ami.core.CloudPlaylist
import com.longzhousz.ami.core.CloudTrack
import com.longzhousz.ami.core.ErrorCode
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

private const val MAX_SEARCH_TRACKS = 20_000

internal data class CloudLibrarySearchResult(
    val tracks: PersistentList<CloudTrack> = persistentListOf(),
    val collections: PersistentList<CloudPlaylist> = persistentListOf(),
    val loading: Boolean = true,
    val tracksLimited: Boolean = false,
    val failedOffsets: PersistentMap<String, Int> = persistentMapOf(),
)

@Suppress("TooGenericExceptionCaught") // A failed collection can be retried without discarding other matches.
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
    val matches = SearchMatches(result)
    emit(result)

    val reading = if (previous == null) sources else sources.filter { it.key in previous.failedOffsets }
    for ((index, collection) in reading.withIndex()) {
        if (!matches.needsTracks(result, collection)) continue
        var offset = previous?.failedOffsets?.get(collection.key) ?: 0
        try {
            scanCollectionTracks(collection, favorites, offset, session) { page ->
                requireSearchSession(session)
                val updated = matches.addPage(result, page.tracks, collection, term, likes.value.takeIf { favorites })
                if (updated !== result) {
                    result = updated
                    emit(result)
                }
                offset = page.nextOffset
                matches.needsTracks(result, collection)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            requireSearchSession(session)
            AppDiagnostics.errors.record(if (favorites) ErrorCode.ONLINE_FAVORITES else ErrorCode.ONLINE_LIBRARY, failure)
            var failed = result.failedOffsets.put(collection.key, offset)
            if (failure is NeteaseLoginExpired) {
                val remainingCollections = reading.subList(index + 1, reading.size).filter { matches.needsTracks(result, it) }
                for (remaining in remainingCollections) {
                    failed = failed.put(remaining.key, previous?.failedOffsets?.get(remaining.key) ?: 0)
                }
                result = result.copy(failedOffsets = failed)
                break
            }
            result = result.copy(failedOffsets = failed)
        }
    }
    requireSearchSession(session)
    emit(result.copy(loading = false))
}.flowOn(Dispatchers.IO).buffer(0)

private class SearchMatches(
    initial: CloudLibrarySearchResult,
) {
    private val songIds = initial.tracks.mapTo(HashSet()) { it.id }
    private val collectionKeys = initial.collections.mapTo(HashSet()) { it.key }

    fun needsTracks(
        result: CloudLibrarySearchResult,
        collection: CloudPlaylist,
    ): Boolean = !result.tracksLimited || (collection.kind == "album" && collection.key !in collectionKeys)

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
