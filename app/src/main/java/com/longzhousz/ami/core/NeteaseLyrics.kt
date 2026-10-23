package com.longzhousz.ami.core

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

data class LocalLyrics(val original: String, val translated: String = "")

/** Formats used by exported NetEase LrcDownload files; filenames are song IDs. */
object NeteaseLyrics {
    private val id = Regex("[1-9][0-9]{0,19}")
    private val yrcLine = Regex("^\\[(\\d+),(\\d+)](.*)$")
    private val yrcWord = Regex("\\(\\d+,\\d+,\\d+\\)")

    fun songId(value: String?): String? = value?.trim()?.takeIf { id.matches(it) }
    fun idFromFilename(name: String): String? {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val stem = if (extension in setOf("lrc", "json", "txt", "lyric", "yrc")) name.substringBeforeLast('.') else name
        return songId(stem)
    }
    fun idFromMetadata(json: String?): String? = json?.let {
        runCatching { songId(JSONObject(it).opt("musicId")?.toString()) }.getOrNull()
    }
    fun text(bytes: ByteArray): String {
        require(bytes.size <= 2 * 1024 * 1024) { "歌词文件超过 2 MB" }
        val charset = when {
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> Charsets.UTF_16LE
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> Charsets.UTF_16BE
            else -> null
        }
        return (charset?.let { bytes.toString(it) } ?: runCatching {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        }.getOrElse { bytes.toString(charset("GB18030")) }).removePrefix("\uFEFF").trim()
    }
    fun decode(bytes: ByteArray): LocalLyrics = decode(text(bytes))
    fun decode(raw: String): LocalLyrics {
        require(raw.length <= 2 * 1024 * 1024) { "歌词文件过大" }
        val body = raw.removePrefix("\uFEFF").trim()
        require(body.isNotEmpty() && !body.contains('\u0000')) { "歌词文件为空或不是文本" }
        checkNesting(body)
        if (body.startsWith('{')) {
            val root = runCatching { JSONObject(body) }.getOrNull()
            fun lyric(node: Any?): String? = when (node) {
                is String -> node
                is JSONObject -> node.opt("lyric") as? String
                else -> null
            }?.takeIf { it.isNotBlank() }
            if (root != null) {
                val original = lyric(root.opt("lrc")) ?: lyric(root.opt("lyric")) ?: lyric(root.opt("yrc")) ?: lyric(root.opt("karaokeLyric"))
                if (original != null) {
                    val translated = lyric(root.opt("tlyric")) ?: lyric(root.opt("translatedLyric")) ?: lyric(root.opt("translateLyric"))
                        ?: lyric(root.optJSONObject("lrc")?.opt("tlyric"))
                    return LocalLyrics(normalize(original), translated?.let(::normalize).orEmpty())
                }
            }
            return LocalLyrics(normalize(body))
        }
        require(!body.startsWith("[{")) { "无法识别歌词缓存格式" }
        return LocalLyrics(normalize(body))
    }
    private fun normalize(raw: String): String {
        checkNesting(raw)
        val lines = raw.replace("\r\n", "\n").replace('\r', '\n').removePrefix("\uFEFF").lines()
        require(lines.size <= 20000) { "歌词行数过多" }
        val normalized = lines.joinToString("\n") { line ->
            val trimmed = line.trim()
            val match = yrcLine.matchEntire(trimmed)
            if (trimmed.startsWith('{')) {
                // Credit lines (often -1000/-500 ms) can precede ordinary LRC lines.
                val node = runCatching { JSONObject(trimmed) }.getOrNull() ?: error("无法识别歌词缓存格式")
                val time = node.opt("t")?.toString()?.toLongOrNull() ?: error("歌词时间无效")
                val parts = node.optJSONArray("c") ?: error("无法识别歌词缓存格式")
                val text = (0 until parts.length()).mapNotNull { parts.optJSONObject(it)?.opt("tx") as? String }.joinToString("")
                stamp(time.coerceAtLeast(0)) + text
            } else if (match != null) {
                val time = match.groupValues[1].toLongOrNull() ?: error("歌词时间无效")
                stamp(time) + yrcWord.replace(match.groupValues[3], "")
            } else line
        }.trim()
        require(normalized.isNotBlank() && Lyrics.parse(normalized).isNotEmpty()) { "没有可显示的歌词" }
        require(!normalized.startsWith('{')) { "无法识别歌词缓存格式" }
        return normalized
    }
    private fun stamp(ms: Long) = String.format(Locale.ROOT, "[%02d:%02d.%03d]", ms / 60000, ms / 1000 % 60, ms % 1000)
    private fun checkNesting(text: String) {
        var quoted = false; var escaped = false; var depth = 0
        text.forEach { c ->
            if (quoted) {
                when { escaped -> escaped = false; c == '\\' -> escaped = true; c == '"' -> quoted = false }
            } else when (c) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 32) { "歌词数据嵌套过深" } }
                '}', ']' -> depth--
            }
        }
    }
    /** Align existing cache translations by timestamps, never by potentially missing line indices. */
    fun translations(original: List<LyricLine>, raw: String): List<String>? {
        if (raw.isBlank()) return null
        val translated = Lyrics.parse(raw).filter { it.time != null }.groupBy { it.time }
        val result = original.map { line -> line.time?.let { translated[it]?.joinToString(" / ") { entry -> entry.text } }.orEmpty() }
        return result.takeIf { it.any(String::isNotBlank) }
    }
}
