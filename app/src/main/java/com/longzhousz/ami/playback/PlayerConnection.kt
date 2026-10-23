package com.longzhousz.ami.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.longzhousz.ami.core.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

data class PlaybackState(
    val connected: Boolean = false, val id: String? = null, val playing: Boolean = false,
    val position: Long = 0, val duration: Long = 0, val buffering: Boolean = false,
    val shuffle: Boolean = false, val repeat: Int = Player.REPEAT_MODE_ALL,
    val queue: List<String> = emptyList(), val error: String? = null,
)

class PlayerConnection(context: Context, scope: CoroutineScope) {
    val state = MutableStateFlow(PlaybackState())
    private val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
    private var controller: MediaController? = null
    private var poll: Job? = null
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = update()
    }
    init {
        future.addListener({
            runCatching {
                controller = future.get().also { it.addListener(listener) }
                update()
                poll = scope.launch { while (true) { update(); delay(350) } }
            }.onFailure { state.value = state.value.copy(error = "无法连接播放服务，请重启应用") }
        }, ContextCompat.getMainExecutor(context))
    }
    private fun update() {
        val c = controller ?: return
        val playRequested = c.playWhenReady && c.playbackState != Player.STATE_ENDED && c.playerError == null
        state.value = PlaybackState(true, c.currentMediaItem?.mediaId, playRequested, c.currentPosition.coerceAtLeast(0),
            c.duration.coerceAtLeast(0), c.playbackState == Player.STATE_BUFFERING, c.shuffleModeEnabled, c.repeatMode,
            (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId },
            c.playerError?.let { "无法播放此音频（${it.errorCodeName}），请检查文件授权或格式" })
    }
    fun play(songs: List<Song>, index: Int = 0) {
        val c = controller ?: error("播放服务正在连接，请稍后再试")
        require(songs.isNotEmpty() && index in songs.indices)
        c.setMediaItems(songs.map { it.mediaItem() }, index, 0); c.prepare(); c.play()
    }
    fun toggle() { controller?.let {
        if (it.playWhenReady && it.playbackState != Player.STATE_ENDED && it.playerError == null) it.pause() else resume()
    } }
    fun pause() { controller?.pause() }
    fun resume() { controller?.let {
        if (it.playbackState == Player.STATE_ENDED) it.seekToDefaultPosition()
        if (it.playbackState == Player.STATE_IDLE) it.prepare()
        it.play()
    } }
    fun next() { controller?.seekToNextMediaItem() }
    fun previous() { controller?.let { if (it.currentPosition > 3000) it.seekTo(0) else it.seekToPreviousMediaItem() } }
    fun seek(ms: Long) { controller?.seekTo(ms.coerceAtLeast(0)) }
    // MediaController interpolates this locally. The visible lyric page can read an accurate
    // clock without increasing the library's state-flow polling or keeping a background timer.
    fun currentPosition(): Long = controller?.currentPosition?.coerceAtLeast(0) ?: state.value.position
    fun shuffle() { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }
    fun repeat() { controller?.let { it.repeatMode = when (it.repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF } } }
    fun jump(index: Int) { controller?.let { it.seekTo(index, 0); it.play() } }
    fun remove(id: String) {
        controller?.let { c -> (c.mediaItemCount - 1 downTo 0).filter { c.getMediaItemAt(it).mediaId == id }.forEach { c.removeMediaItem(it) } }
    }
    fun release() { poll?.cancel(); controller?.removeListener(listener); MediaController.releaseFuture(future) }
}
