package com.longzhousz.ami.core

import org.junit.Assert.*
import org.junit.Test

class WordLyricsTest {
    @Test fun realWordsKeepExistingLineTextTimesAndTranslationIndices() {
        val raw = "[00:00]Credits\n[00:01]Light returns\n[00:04]Another line"
        val timed = "[1000,2000](1000,700,0)Light (1700,1300,0)returns"
        val ordinary = SongLyrics(raw)
        val words = SongLyrics(raw, revision = "same", wordTimed = timed)
        assertEquals(ordinary.lines.map { it.time to it.text }, words.lines.map { it.time to it.text })
        assertTrue(words.lines[0].words.isEmpty())
        assertEquals(listOf(LyricWord(1000, 1700, 0, 6), LyricWord(1700, 3000, 6, 13)), words.lines[1].words)
        assertTrue(words.estimatedBytes > ordinary.estimatedBytes)
    }

    @Test fun repeatedPhrasesChooseTheirOwnTimestampsAndUnicodeUsesTextOffsets() {
        val raw = "[00:01]光🌙\n[00:12]光🌙"
        val timed = "[12000,2000](12000,800,0)光(12800,1200,0)🌙\n[1000,2000](1000,800,0)光(1800,1200,0)🌙"
        val lines = SongLyrics(raw, wordTimed = timed).lines
        assertEquals(1000, lines[0].words.first().start)
        assertEquals(12000, lines[1].words.first().start)
        assertEquals(3, lines[1].words.last().until)
        assertEquals("🌙", lines[0].text.substring(lines[0].words.last().from, lines[0].words.last().until))
    }

    @Test fun invalidOrUnrelatedTimingFallsBackWithoutInventingWordDurations() {
        val raw = "[00:01]Keep this line"
        val malformed = listOf("[1000,100](900,500,0)Keep this line", "[1000,100](1000,500,0)Keep this line",
            "[1000,100]Keep this line", "[1000,100](1000,100,0)Different", "[7000,100](7000,100,0)Keep this line",
            "[999999999,100](999999999,100,0)Keep this line", "x".repeat(500001))
        malformed.forEach { assertEquals(Lyrics.parse(raw), SongLyrics(raw, wordTimed = it).lines) }
    }

    @Test fun whitespaceAndZeroDurationWordsRemainSafeAndSeekKeepsLineSelection() {
        val lines = SongLyrics("[00:01]风 来\n[00:05]下一句", wordTimed = "[1000,2000](1000,0,0)  风 (1000,2000,0)来  ").lines
        assertEquals(listOf(LyricWord(1000, 1000, 0, 2), LyricWord(1000, 3000, 2, 3)), lines[0].words)
        assertEquals(0, Lyrics.active(lines, 2500))
        assertEquals(1, Lyrics.active(lines, 5000))
    }
}
