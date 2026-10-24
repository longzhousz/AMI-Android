package com.longzhousz.ami.core

/** Immutable queue identity is shared by position-only checkpoints. */
data class PlaybackSnapshot(
    val queue: List<String> = emptyList(),
    val index: Int = 0,
    val position: Long = 0,
    val repeat: Int = 2,
    val shuffle: Boolean = false,
) {
    /** Preserve the selected occurrence when a missing song before it is removed. */
    fun restore(songs: List<Song>): Pair<List<Song>, Pair<Int, Long>> {
        val byId = songs.associateBy { it.id }
        val available = queue.mapIndexedNotNull { original, id -> byId[id]?.let { original to it } }
        if (available.isEmpty()) return emptyList<Song>() to (0 to 0L)
        val savedIndex = index.coerceIn(queue.indices)
        val restored = available.indexOfFirst { it.first == savedIndex }
        val target = if (restored >= 0) restored else available.indexOfFirst { it.first > savedIndex }.takeIf { it >= 0 } ?: available.lastIndex
        val selected = available[target].second
        val position = if (restored < 0) 0L else position.coerceAtLeast(0).let { if (selected.duration > 0) it.coerceAtMost((selected.duration - 1).coerceAtLeast(0)) else it }
        return available.map { it.second } to (target to position)
    }
}
