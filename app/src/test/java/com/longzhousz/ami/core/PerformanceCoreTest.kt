package com.longzhousz.ami.core

import org.junit.Assert.*
import org.junit.Test

class PerformanceCoreTest {
    @Test fun digestOptimizationPreservesExistingSongAndCacheIdentifiers() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", digest("abc"))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", digest(byteArrayOf()))
        assertEquals(digest("音乐\n"), digest("音乐\n".toByteArray(Charsets.UTF_8)))
    }
    @Test fun fingerprintsCoverContentMetadataAndProviderLocation() {
        val source = SourceStamp("content://provider/tree/music/document/song", 100, 42)
        val fingerprint = ScanFingerprint.of("v1", listOf(source))
        assertNotNull(fingerprint)
        assertNotEquals(fingerprint, ScanFingerprint.of("v1", listOf(source.copy(modified = 101))))
        assertNotEquals(fingerprint, ScanFingerprint.of("v1", listOf(source.copy(size = 43))))
        assertNotEquals(fingerprint, ScanFingerprint.of("v1", listOf(source.copy(location = "another grant"))))
        assertNotEquals(fingerprint, ScanFingerprint.of("v2", listOf(source)))
    }
    @Test fun fingerprintsAreStableAcrossProviderEnumerationOrder() {
        val items = listOf(SourceStamp("audio", 1, 100), SourceStamp("cover", 2, 20), SourceStamp("lyric", 3, 40))
        assertEquals(ScanFingerprint.of("v1", items), ScanFingerprint.of("v1", items.reversed()))
        assertNotEquals(ScanFingerprint.of("v1", items), ScanFingerprint.of("v1", items.dropLast(1)))
    }
    @Test fun unreliableProviderMetadataNeverProducesAReusableFingerprint() {
        assertNull(ScanFingerprint.of("v1", listOf(SourceStamp("audio", 0, 123))))
        assertNull(ScanFingerprint.of("v1", listOf(SourceStamp("audio", 123, -1))))
        assertNull(ScanFingerprint.of("v1", emptyList()))
    }
    @Test fun restoreKeepsTheCurrentSongWhenAnEarlierQueueEntryDisappears() {
        val b = Song("b", "b", folder = "f", title = "B", duration = 5000)
        val c = b.copy(id = "c")
        val (songs, location) = PlaybackSnapshot(listOf("missing", "b", "c"), 1, 1250).restore(listOf(c, b))
        assertEquals(listOf("b", "c"), songs.map { it.id })
        assertEquals(0 to 1250L, location)
    }
    @Test fun restorePreservesDuplicateQueueOccurrencesAndResetsRemovedTracksPosition() {
        val a = Song("a", "a", folder = "f", title = "A", duration = 5000)
        val b = a.copy(id = "b")
        assertEquals(2 to 800L, PlaybackSnapshot(listOf("a", "b", "a"), 2, 800).restore(listOf(a, b)).second)
        assertEquals(1 to 0L, PlaybackSnapshot(listOf("a", "missing", "b"), 1, 4500).restore(listOf(a, b)).second)
        assertTrue(PlaybackSnapshot(listOf("missing"), 0, 100).restore(emptyList()).first.isEmpty())
    }
    @Test fun restoreBoundsCorruptIndicesAndPositions() {
        val a = Song("a", "a", folder = "f", title = "A", duration = 3000)
        assertEquals(0 to 0L, PlaybackSnapshot(listOf("a"), -99, -10).restore(listOf(a)).second)
        assertEquals(0 to 2999L, PlaybackSnapshot(listOf("a"), 999, 99999).restore(listOf(a)).second)
    }
    private fun songs() = (0 until 10_000).map { n -> Song("$n", "file:///$n", folder = "f", title = "Track $n", artist = if (n == 8123) "Needle Artist" else "Other",
        album = "Album ${n / 100}", favorite = n % 10 == 0, importedAt = n.toLong()) }
    @Test fun largeLibrarySearchFindsSongArtistAndAlbumWithExistingOrdering() {
        val library = LibrarySnapshot(songs())
        assertEquals(listOf("8123"), library.search(" NEEDLE artist ", false, false).songs.map { it.id })
        assertEquals(100, library.search("Album 81", false, false).songs.size)
        assertEquals(listOf("0", "1", "2"), library.search("", false, false).songs.take(3).map { it.id })
        assertTrue(library.search("Track 8123", false, false).albums.any { it.songs.any { song -> song.id == "8123" } })
    }
    @Test fun favoriteAndRecentFiltersRemainConsistentForLargeCollections() {
        val library = LibrarySnapshot(songs())
        val result = library.search("", true, true)
        assertEquals(1000, result.songs.size)
        assertTrue(result.songs.all { it.favorite })
        assertEquals(listOf("9990", "9980", "9970"), result.songs.take(3).map { it.id })
        assertTrue(library.search("does not exist", false, false).songs.isEmpty())
    }
}
