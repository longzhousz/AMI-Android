package com.longzhousz.ami.core

/** Unknown modification times cannot safely support incremental metadata reuse. */
data class SourceStamp(val location: String, val modified: Long, val size: Long)

object ScanFingerprint {
    fun of(revision: String, stamps: List<SourceStamp>): String? {
        if (stamps.isEmpty() || stamps.any { it.modified <= 0 || it.size < 0 }) return null
        return digest(buildString {
            append(revision.length).append(':').append(revision)
            stamps.sortedBy { it.location }.forEach {
                append('|').append(it.location.length).append(':').append(it.location)
                append(':').append(it.modified).append(':').append(it.size)
            }
        })
    }
}
