package com.longzhousz.ami.core

data class CueTrack(val file: String, val number: Int, val title: String, val artist: String, val start: Long)
data class CueSheet(val title: String, val artist: String, val tracks: List<CueTrack>)

object Cue {
    fun parse(text: String): CueSheet {
        var album = ""
        var albumArtist = ""
        var file = ""
        var number = 0
        var title = ""
        var artist = ""
        var start: Long? = null
        val tracks = mutableListOf<CueTrack>()
        fun flush() {
            if (number > 0) {
                require(file.isNotBlank() && start != null) { "CUE 曲目缺少 FILE 或 INDEX 01" }
                tracks += CueTrack(file.replace('\\', '/'), number, title.ifBlank { "Track %02d".format(number) }, artist.ifBlank { albumArtist }, start!!)
            }
        }
        fun value(raw: String): String = if (raw.startsWith('"')) raw.substringAfter('"').substringBeforeLast('"') else raw.trim()
        text.removePrefix("\uFEFF").lineSequence().forEach { raw ->
            val line = raw.trim()
            val command = line.substringBefore(' ').uppercase()
            val rest = line.substringAfter(' ', "").trim()
            when (command) {
                "FILE" -> {
                    flush(); number = 0; start = null
                    file = if (rest.startsWith('"')) rest.substringAfter('"').substringBefore('"') else rest.substringBefore(' ')
                }
                "TRACK" -> {
                    flush(); number = rest.substringBefore(' ').toIntOrNull() ?: error("CUE 曲号无效")
                    require(rest.endsWith("AUDIO", ignoreCase = true)) { "仅支持音频 CUE" }
                    title = ""; artist = ""; start = null
                }
                "TITLE" -> if (number == 0) album = value(rest) else title = value(rest)
                "PERFORMER" -> if (number == 0) albumArtist = value(rest) else artist = value(rest)
                "INDEX" -> if (rest.startsWith("01 ")) {
                    val parts = rest.substringAfter(' ').trim().split(':').map { it.toLongOrNull() ?: error("CUE 时间无效") }
                    require(parts.size == 3 && parts[0] >= 0 && parts[1] in 0..59 && parts[2] in 0..74) { "CUE 时间无效" }
                    start = parts[0] * 60000 + parts[1] * 1000 + parts[2] * 1000 / 75
                }
            }
        }
        flush()
        require(tracks.isNotEmpty()) { "CUE 中没有音轨" }
        require(tracks.map { it.number }.distinct().size == tracks.size) { "CUE 曲号重复" }
        tracks.groupBy { it.file }.values.forEach { group ->
            require(group.zipWithNext().all { (a, b) -> b.start > a.start }) { "CUE 时间必须递增" }
        }
        return CueSheet(album, albumArtist, tracks)
    }
}
