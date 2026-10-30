package com.longzhousz.ami.data

import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException

internal val neteaseAudioLevels = listOf("jyeffect", "hires", "lossless", "exhigh")
internal class NeteaseAudioUnavailable(val trial: Boolean = false) : IllegalStateException(
    if (trial) "网易云当前只返回了试听片段，未取得完整音源" else "网易云未返回这首歌的可播放音源")
internal data class NeteaseAudio(val url: String, val level: String?, val codec: String, val bitrate: Int?, val bytes: Long = 0, val md5: String? = null) {
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
    return NeteaseAudio(url, level, codec, row.optInt("br").takeIf { it > 0 }, row.optLong("size").coerceAtLeast(0),
        row.optString("md5").lowercase().takeIf { it.matches(Regex("[a-f0-9]{32}")) })
}

/** At most four quality probes, stopping once the server has already supplied the next tier. */
internal fun bestNeteaseAudio(id: String, duration: Long, request: (String) -> JSONObject): NeteaseAudio {
    var best: NeteaseAudio? = null
    var unavailable: IllegalStateException? = null
    for ((rank, level) in neteaseAudioLevels.withIndex()) {
        best?.takeIf { it.rank <= rank }?.let { return it }
        val candidate = try { neteaseAudio(request(level), id, duration) }
        catch (expired: NeteaseLoginExpired) { throw expired }
        catch (error: NeteaseAudioUnavailable) {
            if ((unavailable as? NeteaseAudioUnavailable)?.trial != true) unavailable = error
            continue
        }
        catch (error: NeteaseRequestRejected) {
            // Quality-specific refusal is not proof that the whole song or account is unusable.
            if (error.code !in setOf(400, 403, 404)) return best ?: throw error
            if (unavailable == null) unavailable = error
            continue
        }
        catch (error: IOException) { return best ?: throw error }
        if (best == null || candidate.rank < best.rank) best = candidate
    }
    return best ?: throw (unavailable ?: NeteaseAudioUnavailable())
}

/** A website session can work on weapi even when the client endpoint rejects that session.
 * Both routes use the same account; never use anonymous, replacement, or trial audio.
 * Disable the client route after its first failure: at most five v1 requests, then one HQ fallback. */
internal fun authorizedNeteaseAudio(id: String, duration: Long, client: (JSONObject) -> JSONObject,
    web: (String, JSONObject) -> JSONObject): NeteaseAudio {
    var clientAvailable = true
    val failure = try {
        return bestNeteaseAudio(id, duration) { level ->
            val data = JSONObject().put("ids", JSONArray().put(id).toString()).put("level", level).put("encodeType", "flac")
            if (clientAvailable) {
                try {
                    val result = client(data)
                    neteaseAudio(result, id, duration)
                    return@bestNeteaseAudio result
                } catch (_: NeteaseLoginExpired) { clientAvailable = false }
                catch (_: NeteaseRequestRejected) { clientAvailable = false }
                catch (_: NeteaseAudioUnavailable) { clientAvailable = false }
                catch (_: IOException) { clientAvailable = false }
            }
            web("song/enhance/player/url/v1", data)
        }
    } catch (expired: NeteaseLoginExpired) { throw expired }
    catch (unavailable: NeteaseAudioUnavailable) { unavailable }
    catch (rejected: NeteaseRequestRejected) {
        if (rejected.code !in setOf(400, 403, 404)) throw rejected
        rejected
    }
    return try {
        neteaseAudio(web("song/enhance/player/url", JSONObject().put("ids", JSONArray().put(id).toString()).put("br", 320000)), id, duration)
    } catch (unavailable: NeteaseAudioUnavailable) {
        throw if ((failure as? NeteaseAudioUnavailable)?.trial == true) failure else unavailable
    }
}
