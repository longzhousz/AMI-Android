package com.longzhousz.ami.core

data class LyricWord(val start: Long, val end: Long, val from: Int, val until: Int)
data class LyricLine(val time: Long?, val text: String, val words: List<LyricWord> = emptyList())

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
                else -> matches.mapNotNull { match ->
                    val (min, sec, fraction) = match.destructured
                    val millis = fraction.padEnd(3, '0').take(3).toLongOrNull() ?: 0
                    val minutes = min.toLongOrNull() ?: return@mapNotNull null
                    val seconds = sec.toLongOrNull() ?: return@mapNotNull null
                    runCatching { Math.addExact(Math.addExact(Math.multiplyExact(minutes,60000L),seconds*1000+millis),offset) }
                        .getOrNull()?.let { LyricLine(it.coerceAtLeast(0),text) }
                }.ifEmpty { listOf(LyricLine(null,text)) }.asSequence()
            }
        }.sortedBy { it.time ?: Long.MAX_VALUE }.toList()
    }

    /** Parser output is time-sorted, with untimed lines last. Called from the frame clock. */
    fun active(lines: List<LyricLine>, position: Long): Int {
        var low=0;var high=lines.lastIndex;var active=-1
        while (low<=high) {
            val middle=(low+high).ushr(1)
            val time=lines[middle].time
            if (time!=null && time<=position) { active=middle;low=middle+1 } else high=middle-1
        }
        return active
    }
}
