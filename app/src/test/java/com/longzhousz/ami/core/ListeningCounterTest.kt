package com.longzhousz.ami.core

import org.junit.Assert.*
import org.junit.Test

class ListeningCounterTest {
    @Test fun pausesBufferingAndSeekPositionsCannotBecomeListens() {
        val counter = ListeningCounter()
        assertNull(counter.update("a", 180000, false, 0))
        assertNull(counter.update("a", 180000, true, 60000))
        assertNull(counter.update("a", 180000, false, 70000))
        assertNull(counter.update("a", 180000, true, 200000))
        assertEquals("a", counter.update("a", 180000, true, 220000))
        assertNull(counter.update("a", 180000, true, 320000))
    }
    @Test fun repeatAndTrackTransitionCountThePreviousSessionOnlyOnce() {
        val counter = ListeningCounter()
        counter.update("a", 6000, true, 0)
        assertEquals("a", counter.update("a", 6000, true, 6000, restart = true))
        assertEquals("a", counter.update("b", 6000, true, 12000))
        assertNull(counter.update("b", 6000, false, 13000))
        assertNull(counter.update(null, 0, false, 60000))
    }
    @Test fun durationDiscoveredAfterPrepareStillQualifiesShortSongs() {
        val counter = ListeningCounter()
        counter.update("a", 0, true, 0)
        assertEquals("a", counter.update("a", 6000, false, 4000))
    }
}
