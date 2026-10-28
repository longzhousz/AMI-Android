package com.longzhousz.ami.core

/** Counts audible time, not seeks, buffering or a restored playback position. */
internal class ListeningCounter {
    private var id: String? = null
    private var at = 0L
    private var playing = false
    private var audible = 0L
    private var counted = false
    private var threshold = 30_000L

    fun update(song: String?, duration: Long, isPlaying: Boolean, now: Long, restart: Boolean = false): String? {
        if (playing) audible += (now - at).coerceAtLeast(0)
        if (song == id && duration > 0) threshold = (duration / 2).coerceIn(3_000, 30_000)
        val qualified = id?.takeIf { !counted && audible >= threshold }
        if (qualified != null) counted = true
        if (song != id || restart) {
            id = song; audible = 0; counted = false
            threshold = if (duration > 0) (duration / 2).coerceIn(3_000, 30_000) else 30_000
        }
        playing = isPlaying; at = now
        return qualified
    }
}
