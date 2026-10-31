package com.longzhousz.ami.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.longzhousz.ami.core.Song
import kotlinx.coroutines.flow.MutableStateFlow

enum class PlayMode(val label: String, val repeat: Int, val shuffle: Boolean = false) {
    Ordered("顺序播放", Player.REPEAT_MODE_OFF),
    Loop("列表循环", Player.REPEAT_MODE_ALL),
    One("单曲循环", Player.REPEAT_MODE_ONE),
    Shuffle("随机播放", Player.REPEAT_MODE_ALL, true);

    companion object {
        fun from(shuffle: Boolean, repeat: Int): PlayMode = when {
            repeat == Player.REPEAT_MODE_ONE -> One
            shuffle -> Shuffle
            repeat == Player.REPEAT_MODE_ALL -> Loop
            else -> Ordered
        }
    }
}

data class PlaybackState(
    val connected: Boolean = false, val id: String? = null, val playing: Boolean = false,
    val position: Long = 0, val duration: Long = 0, val buffering: Boolean = false,
    val shuffle: Boolean = false, val repeat: Int = Player.REPEAT_MODE_ALL,
    val queue: List<String> = emptyList(), val error: String? = null,
    val advancing: Boolean = false,
    val nextId: String? = null,
)

class PlayerConnection(private val context: Context) {
    val state = MutableStateFlow(PlaybackState())
    private val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
    private var controller: MediaController? = null
    private var queue: List<String> = emptyList()
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = update(events.contains(Player.EVENT_TIMELINE_CHANGED))
    }
    init {
        future.addListener({
            runCatching {
                controller = future.get().also { it.addListener(listener) }
                update(true)
            }.onFailure { state.value = state.value.copy(error = "无法连接播放服务，请重启应用") }
        }, ContextCompat.getMainExecutor(context))
    }
    private fun update(updateQueue: Boolean = false) {
        val c = controller ?: return
        if (updateQueue) {
            val ids = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }
            if (ids != queue) queue = ids
        }
        val cloud = (context.applicationContext as com.longzhousz.ami.AmiApplication).repository.cloud
        val recovering = c.currentMediaItem?.mediaId?.let { cloud.recoveringAudio == it } == true
        val playRequested = c.playWhenReady && c.playbackState != Player.STATE_ENDED && (c.playerError == null || recovering)
        state.value = PlaybackState(true, c.currentMediaItem?.mediaId, playRequested, c.currentPosition.coerceAtLeast(0),
            c.duration.coerceAtLeast(0), c.playbackState == Player.STATE_BUFFERING || recovering, c.shuffleModeEnabled, c.repeatMode,
            queue,
            c.playerError?.takeUnless { recovering }?.let { if (c.currentMediaItem?.mediaId?.startsWith("netease:") == true)
                cloud.playbackFailure?.takeIf { it.first == c.currentMediaItem?.mediaId }?.second ?: "网易云播放失败（${it.errorCode}），请重试"
                else "无法播放此音频（${it.errorCodeName}），请检查文件授权或格式" }, advancing = c.isPlaying,
            nextId = c.nextMediaItemIndex.takeIf { it >= 0 }?.let { c.getMediaItemAt(it).mediaId })
    }
    fun play(songs: List<Song>, index: Int = 0, radio: Boolean = false) {
        val c = controller ?: error("播放服务正在连接，请稍后再试")
        require(songs.isNotEmpty() && index in songs.indices)
        if (radio) { c.repeatMode = Player.REPEAT_MODE_OFF; c.shuffleModeEnabled = false }
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
    // Only player events publish structural state. Visible progress/lyrics read the controller's
    // locally interpolated clock; advancing time must not rebuild the queue or the library UI.
    fun currentPosition(expectedId: String? = state.value.id): Long = controller?.let {
        if (it.currentMediaItem?.mediaId == expectedId) it.currentPosition.coerceAtLeast(0) else 0L
    } ?: state.value.position.takeIf { state.value.id == expectedId } ?: 0L
    fun mode(mode: PlayMode) { controller?.let { it.repeatMode = mode.repeat; it.shuffleModeEnabled = mode.shuffle } }
    fun jump(index: Int) { controller?.let { it.seekTo(index, 0); it.play() } }
    fun remove(id: String) {
        controller?.let { c -> (c.mediaItemCount - 1 downTo 0).filter { c.getMediaItemAt(it).mediaId == id }.forEach { c.removeMediaItem(it) } }
    }
    fun release() { controller?.removeListener(listener); MediaController.releaseFuture(future) }
}
