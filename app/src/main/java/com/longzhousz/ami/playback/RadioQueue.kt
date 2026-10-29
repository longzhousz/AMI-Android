package com.longzhousz.ami.playback

import androidx.media3.common.Player
import com.longzhousz.ami.data.CloudMusic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Service-owned FM continuation. Events trigger small batches; there is no periodic task. */
internal class RadioQueue(private val cloud: CloudMusic, private val player: Player, private val scope: CoroutineScope) {
    private var job: Job? = null
    private var session: String? = null
    private var attempted: String? = null

    fun update() {
        val token = cloud.radio.value
        if (token != session) { job?.cancel(); job = null; attempted = null; session = token }
        if (!player.playWhenReady) {
            if (job?.isActive == true) { job?.cancel(); job = null; attempted = null }
            return
        }
        val count = player.mediaItemCount
        if (token == null || count == 0 || count >= 1000 || player.playerError != null ||
            player.playbackState == Player.STATE_BUFFERING || player.repeatMode == Player.REPEAT_MODE_ONE ||
            player.currentMediaItemIndex < count - 2 || job?.isActive == true) return
        if (cloud.catalog.value[player.getMediaItemAt(0).mediaId] == null || cloud.catalog.value[player.getMediaItemAt(count - 1).mediaId] == null) return
        val marker = "$token:$count:${player.currentMediaItemIndex}"
        if (attempted == marker) return
        attempted = marker
        val expected = (0 until count).map { player.getMediaItemAt(it).mediaId }
        job = scope.launch {
            try {
                val fresh = cloud.discovery("fm")
                if (!matches(token, expected)) return@launch
                val added = cloud.appendRadio(token, expected, fresh)
                if (!matches(token, expected) || added.isEmpty()) return@launch
                val ended = player.playbackState == Player.STATE_ENDED
                player.addMediaItems(added.map { it.mediaItem() })
                if (ended && player.playWhenReady) { player.seekToNextMediaItem(); player.prepare() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Try again at the next natural boundary; leave playback intact. */ }
        }
    }

    private fun matches(token: String, ids: List<String>): Boolean = cloud.radio.value == token &&
        player.mediaItemCount == ids.size && ids.indices.all { player.getMediaItemAt(it).mediaId == ids[it] }
}
