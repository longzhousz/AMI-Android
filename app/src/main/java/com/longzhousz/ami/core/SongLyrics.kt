package com.longzhousz.ami.core

/** A song's payload is held separately from the collection's lightweight metadata. */
data class SongLyrics(val original: String = "", val translated: String = "", val revision: String = "") {
    val lines: List<LyricLine> by lazy { Lyrics.parse(original) }
    val embeddedTranslation: List<String>? by lazy { NeteaseLyrics.translations(lines, translated) }
    val estimatedBytes: Int by lazy {
        ((original.length.toLong() + translated.length) * 4 + lines.size * 96L + 256).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}

data class CurrentLyrics(
    val songId: String? = null,
    val revision: String = "",
    val document: SongLyrics? = null,
    val translation: List<String>? = null,
    val translating: Boolean = false,
)
