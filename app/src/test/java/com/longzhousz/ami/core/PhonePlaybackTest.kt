package com.longzhousz.ami.core

import org.junit.Assert.*
import org.junit.Test

class PhonePlaybackTest {
    @Test fun mediaClockUsesElapsedTimeSpeedAndDurationWithoutPolling() {
        assertEquals(4000L,phonePosition(1000,10000,12000,1.5f,true,90000))
        assertEquals(90000L,phonePosition(89000,10000,13000,1f,true,90000))
        assertEquals(1000L,phonePosition(1000,10000,12000,1f,false,90000))
        assertEquals(1000L,phonePosition(1000,10000,9000,1f,true,90000))
        assertEquals(0L,phonePosition(-1,0,5000,Float.NaN,true,0))
    }
    @Test fun songIdentityOnlyAcceptsKnownNetEaseMediaIdentifiers() {
        assertEquals("1234",phoneSongId("1234"))
        assertEquals("1234",phoneSongId("orpheus://song/1234"))
        assertEquals("1234",phoneSongId("https://music.163.com/song?id=1234&x=1"))
        listOf(null,"user:1234","http://unrelated.test/song?id=1234","https://music.163.com.evil/song?id=1234","0","-1").forEach { assertNull(phoneSongId(it)) }
    }
    @Test fun lyricMatchingRejectsLiveVersionsDifferentArtistsAndUnknownDurations() {
        val song=Song("phone:test","ami-phone://test",folder="phone",title="晴天",artist="周杰伦",duration=269000)
        assertTrue(phoneLyricsMatch(song,"晴天","周杰伦",270000))
        assertFalse(phoneLyricsMatch(song,"晴天 (Live)","周杰伦",269000))
        assertFalse(phoneLyricsMatch(song,"晴天","其他歌手",269000))
        assertFalse(phoneLyricsMatch(song,"晴天","周杰伦",275000))
        assertFalse(phoneLyricsMatch(song,"晴天","周杰伦",0))
    }
}
