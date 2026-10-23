package com.longzhousz.ami.core

data class LyricLine(val time: Long?, val text: String)

object Lyrics {
    private val timestamp = Regex("\\[(\\d+):(\\d{1,2})(?:[.:](\\d{1,3}))?]")
    private val header = Regex("^\\[(ar|ti|al|by|re|ve|length|offset):.*]$", RegexOption.IGNORE_CASE)

    fun parse(raw: String): List<LyricLine> {
        val offset = Regex("\\[offset:([+-]?\\d+)]", RegexOption.IGNORE_CASE)
            .find(raw)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        return raw.removePrefix("\uFEFF").lineSequence().flatMap { line ->
            val trimmed = line.trim()
            val matches = timestamp.findAll(trimmed).toList()
            val text = timestamp.replace(trimmed, "").trim()
            when {
                header.matches(trimmed) || text.isBlank() -> emptySequence()
                matches.isEmpty() -> sequenceOf(LyricLine(null, text))
                else -> matches.asSequence().map { match ->
                    val (min, sec, fraction) = match.destructured
                    val millis = fraction.padEnd(3, '0').take(3).toLongOrNull() ?: 0
                    LyricLine((min.toLong() * 60000 + sec.toLong() * 1000 + millis + offset).coerceAtLeast(0), text)
                }
            }
        }.sortedBy { it.time ?: Long.MAX_VALUE }.toList()
    }

    fun active(lines: List<LyricLine>, position: Long): Int = lines.indexOfLast { it.time != null && it.time <= position }
}
