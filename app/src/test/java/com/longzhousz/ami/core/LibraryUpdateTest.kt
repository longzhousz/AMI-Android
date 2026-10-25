package com.longzhousz.ami.core

import org.junit.Assert.*
import org.junit.Test

class LibraryUpdateTest {
    private val source=(0 until 10_000).map { n -> Song("$n","file:///$n",folder="f",title="Track $n",album="Album ${n/100}",favorite=n%10==0,importedAt=n.toLong()) }
    @Test fun favoriteUpdatesAllViewsWithoutMutatingThePreviousSnapshot() {
        val before=LibrarySnapshot(source)
        val after=before.withFavorite("8123",true)
        assertFalse(before.songsById.getValue("8123").favorite)
        assertTrue(after.songsById.getValue("8123").favorite)
        assertSame(after.songsById.getValue("8123"),after.songs[8123])
        val album=after.albums.single { it.songs.any { song -> song.id=="8123" } }
        assertTrue(album.songs.single { it.id=="8123" }.favorite)
        assertSame(album,after.albumsByKey[album.key])
        assertSame(before.albums.first(),after.albums.first())
        assertEquals(listOf("8120","8123","8130"),after.search("",true,false).songs.filter { it.id.toInt() in 8120..8130 }.map { it.id })
        assertEquals(listOf("8123"),after.search("Track 8123",true,true).songs.map { it.id })
        val removed=after.withFavorite("8120",false)
        assertFalse(removed.search("",true,false).songs.any { it.id=="8120" })
        assertEquals("9990",removed.search("",true,true).songs.first().id)
    }
    @Test fun rapidFavoriteReversalsPreserveOrderingAndMissingTracksStayMissing() {
        val before=LibrarySnapshot(source)
        var current=before
        repeat(20) { n -> current=current.withFavorite("8123",n%2==0) }
        assertEquals(before.songs,current.songs)
        assertEquals(before.search("",true,true).songs,current.search("",true,true).songs)
        assertSame(current,current.withFavorite("missing",true))
        assertSame(current,current.withFavorite("8123",false))
    }
    @Test fun lyricLookupKeepsDuplicateTimesUntimedTailsAndBoundaryPositionsCorrect() {
        val lines=listOf(LyricLine(0,"a"),LyricLine(1000,"b"),LyricLine(1000,"translation"),LyricLine(4000,"c"),LyricLine(null,"plain"))
        assertEquals(-1,Lyrics.active(lines,-1))
        assertEquals(0,Lyrics.active(lines,999))
        assertEquals(2,Lyrics.active(lines,1000))
        assertEquals(2,Lyrics.active(lines,3999))
        assertEquals(3,Lyrics.active(lines,Long.MAX_VALUE))
        assertEquals(-1,Lyrics.active(emptyList(),0))
        assertEquals(-1,Lyrics.active(listOf(LyricLine(null,"plain")),Long.MAX_VALUE))
    }
}
