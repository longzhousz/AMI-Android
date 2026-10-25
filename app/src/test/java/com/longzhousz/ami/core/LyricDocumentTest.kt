package com.longzhousz.ami.core

import org.junit.Assert.*
import org.junit.Test

class LyricDocumentTest {
    @Test fun metadataAvailabilityDoesNotRequireKeepingTheBody() {
        val song=Song("one","file:///unused",folder="qa",title="One",lyricsAvailable=true,lyricsVersion="revision")
        assertTrue(song.hasLyrics);assertEquals("",song.lyrics)
        assertTrue(song.copy(favorite=true).hasLyrics)
        assertFalse(song.copy(lyricsAvailable=false).hasLyrics)
    }
    @Test fun ordinarySongsKeepDerivedAvailabilityWhenTheirLyricsAreCopied() {
        val song=Song("one","file:///unused",folder="qa",title="One")
        assertFalse(song.hasLyrics)
        assertTrue(song.copy(lyrics="[00:00]Hello").hasLyrics)
    }
    @Test fun documentsKeepAlignedEmbeddedTranslationsAndAccountForParsedRows() {
        val document=SongLyrics("[00:00]Hello\n[00:01]World","[00:00]你好\n[00:01]世界","revision")
        assertEquals(listOf("Hello","World"),document.lines.map { it.text })
        assertEquals(listOf("你好","世界"),document.embeddedTranslation)
        assertTrue(document.estimatedBytes>document.original.length*2)
        assertSame(document.lines,document.lines)
    }
    @Test fun malformedOrOverflowingTimestampsKeepReadableTextWithoutCrashing() {
        val lines=Lyrics.parse("[999999999999999999999999:00]Overflow\n[00:01]Valid")
        assertEquals(listOf("Valid","Overflow"),lines.map { it.text })
        assertEquals(1000L,lines.first().time)
        assertNull(lines.last().time)
    }

}
