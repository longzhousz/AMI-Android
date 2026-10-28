package com.longzhousz.ami.data

import org.json.JSONObject

internal val neteaseAudioLevels = listOf("jyeffect", "hires", "lossless", "exhigh")
internal data class NeteaseAudio(val url: String, val level: String?, val codec: String, val bitrate: Int?) {
    val rank get() = neteaseAudioLevels.indexOf(level).takeIf { it >= 0 } ?: Int.MAX_VALUE
}

/** The response describes the source. Requested quality is never evidence of delivered quality. */
internal fun neteaseAudio(root: JSONObject, id: String, duration: Long): NeteaseAudio {
    val url = NeteaseAccount.streamUrl(root, id, duration)
    val row = root.getJSONArray("data").getJSONObject(0)
    val codec = row.optString("type").lowercase().takeIf { it in setOf("flac", "alac", "wav", "mp3", "aac", "m4a") } ?: "unknown"
    val reported = row.optString("level").lowercase()
    val level = when {
        codec in setOf("flac", "alac", "wav") -> reported.takeIf { it in neteaseAudioLevels.take(3) } ?: "lossless"
        codec in setOf("mp3", "aac", "m4a") -> reported.takeIf { it in setOf("exhigh", "higher", "standard") }
        else -> null
    }
    return NeteaseAudio(url, level, codec, row.optInt("br").takeIf { it > 0 })
}

/** At most four quality probes, stopping once the server has already supplied the next tier. */
internal fun bestNeteaseAudio(id: String, duration: Long, request: (String) -> JSONObject): NeteaseAudio {
    var best: NeteaseAudio? = null
    var unavailable: IllegalStateException? = null
    for ((rank, level) in neteaseAudioLevels.withIndex()) {
        best?.takeIf { it.rank <= rank }?.let { return it }
        val root = request(level)
        val candidate = try { neteaseAudio(root, id, duration) }
        catch (error: IllegalStateException) { unavailable = error; continue }
        if (best == null || candidate.rank < best.rank) best = candidate
    }
    return best ?: throw (unavailable ?: IllegalStateException("当前账号无法播放这首歌"))
}
