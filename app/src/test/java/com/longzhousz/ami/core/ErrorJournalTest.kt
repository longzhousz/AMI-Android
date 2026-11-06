package com.longzhousz.ami.core

import org.junit.Assert.*
import org.junit.Test

class ErrorJournalTest {
    @Test fun oldestEntriesAreEvictedAndNeitherMessagesNorCausesSurvive() {
        var clock = 1000L
        val journal = ErrorJournal(now = { clock++ })
        repeat(60) { journal.record(ErrorCode.PLAYER_COMMAND, IllegalStateException("Cookie=secret https://private/?token=x /private/path", Exception("API key"))) }
        assertEquals(50, journal.snapshot().size)
        assertEquals(1010L, journal.snapshot().first().time)
        assertEquals(1059L, journal.snapshot().last().time)
        val report = journal.recent()
        assertEquals(5, report.lines().size)
        assertTrue(report.contains("PLAYER_COMMAND IllegalStateException"))
        listOf("secret", "private", "token", "API key", "Cookie").forEach { assertFalse(report.contains(it)) }
        val snapshot = journal.snapshot()
        journal.clear()
        assertTrue(journal.snapshot().isEmpty())
        assertEquals(50, snapshot.size)
    }

    @Test fun concurrentWritersStayBounded() {
        val journal = ErrorJournal()
        val writers = List(8) { Thread { repeat(100) { journal.record(ErrorCode.ONLINE_QUEUE, java.io.IOException("untrusted")) } }.apply { start() } }
        writers.forEach { it.join() }
        assertEquals(50, journal.snapshot().size)
        assertTrue(journal.snapshot().all { it.type == "IOException" })
    }

    @Test fun anonymousExceptionNamesHaveAFixedFallbackAndZeroLimitIsEmpty() {
        val journal = ErrorJournal()
        journal.record(ErrorCode.LYRICS_READ, object : Exception("private") {})
        assertEquals("UnknownException", journal.snapshot().single().type)
        assertEquals("", journal.recent(0))
    }
}
