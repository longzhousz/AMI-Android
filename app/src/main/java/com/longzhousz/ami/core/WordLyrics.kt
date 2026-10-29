package com.longzhousz.ami.core

import kotlin.math.abs

/** Attach genuine YRC word timing without changing LRC text, order or translation indices. */
internal object WordLyrics {
    private val linePattern = Regex("^\\[(\\d{1,9}),(\\d{1,9})](.*)$")
    private val wordPattern = Regex("\\((\\d{1,9}),(\\d{1,9}),-?\\d{1,6}\\)")

    fun enrich(lines: List<LyricLine>, raw: String): List<LyricLine> {
        if (raw.isBlank() || raw.length > 500_000) return lines
        val byText = raw.lineSequence().take(20_000).mapNotNull(::parse)
            .sortedBy { it.time }.groupBy { it.text }
        return lines.map { line ->
            val time = line.time ?: return@map line
            val candidates = byText[line.text] ?: return@map line
            var low = 0; var high = candidates.size
            while (low < high) {
                val middle = (low + high).ushr(1)
                if (candidates[middle].time!! < time) low = middle + 1 else high = middle
            }
            val match = listOfNotNull(candidates.getOrNull(low - 1), candidates.getOrNull(low))
                .minByOrNull { abs(it.time!! - time) }?.takeIf { abs(it.time!! - time) <= 1000 }
            if (match == null) line else line.copy(words = match.words)
        }
    }

    private fun parse(raw: String): LyricLine? = runCatching {
        val match = linePattern.matchEntire(raw.trim()) ?: return null
        val start = match.groupValues[1].toLong()
        val end = start + match.groupValues[2].toLong()
        if (end > 86_400_000 || end <= start) return null
        val body = match.groupValues[3]
        val tokens = wordPattern.findAll(body).toList()
        if (tokens.isEmpty() || tokens.size > 1024 || body.substring(0, tokens.first().range.first).isNotBlank()) return null
        val text = StringBuilder()
        val words = tokens.mapIndexed { index, token ->
            val from = text.length
            text.append(body.substring(token.range.last + 1, tokens.getOrNull(index + 1)?.range?.first ?: body.length))
            val at = token.groupValues[1].toLong()
            val until = at + token.groupValues[2].toLong()
            require(at >= start && until <= end && until >= at)
            LyricWord(at, until, from, text.length)
        }
        require(words.zipWithNext().all { (a, b) -> a.start <= b.start })
        val leading = text.length - text.toString().trimStart().length
        val clean = text.toString().trim()
        if (clean.isEmpty()) return null
        LyricLine(start, clean, words.mapNotNull {
            val from = (it.from - leading).coerceIn(0, clean.length)
            val until = (it.until - leading).coerceIn(0, clean.length)
            if (until <= from) null else it.copy(from = from, until = until)
        })
    }.getOrNull()
}
