package com.longzhousz.ami.playback

import android.os.Handler
import android.os.SystemClock
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlin.math.cos

/** Manual commands fade the app's gain; ExoPlayer still handles focus/noisy interruptions directly. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class FadingPausePlayer(
    private val audio: Player,
    private val onPauseRequested: () -> Unit = {},
) : ForwardingSimpleBasePlayer(audio) {
    private class Fade(val volume: Float, val started: Long, val completion: SettableFuture<Void>)
    private val handler = Handler(audio.applicationLooper)
    private var fade: Fade? = null
    private val frame = Runnable { advance() }
    private val interruptions = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) { if (!isPlaying) finish(pause = true) }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { finish(pause = true) }
        override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) { finish(pause = true) }
    }

    init { audio.addListener(interruptions) }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) {
            finish(pause = false)
            return super.handleSetPlayWhenReady(true)
        }
        fade?.let { return it.completion }
        if (!audio.isPlaying || audio.volume <= 0f) return super.handleSetPlayWhenReady(false)
        val pending = Fade(audio.volume, SystemClock.uptimeMillis(), SettableFuture.create())
        fade = pending
        onPauseRequested()
        handler.postDelayed(frame, 16)
        // SimpleBasePlayer reports the requested paused state immediately until this completes.
        return pending.completion
    }

    private fun advance() {
        val pending = fade ?: return
        if (!audio.isPlaying) { finish(pause = true); return }
        val progress = ((SystemClock.uptimeMillis() - pending.started) / 360f).coerceIn(0f, 1f)
        audio.volume = pending.volume * ((1.0 + cos(Math.PI * progress)) * .5).toFloat()
        if (progress >= 1f) finish(pause = true) else handler.postDelayed(frame, 16)
    }

    private fun finish(pause: Boolean) {
        val pending = fade ?: return
        fade = null
        handler.removeCallbacks(frame)
        if (pause) audio.pause()
        audio.volume = pending.volume
        pending.completion.set(null)
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        finish(pause = true)
        return super.handleSeek(mediaItemIndex, positionMs, seekCommand)
    }

    override fun handleSetMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        finish(pause = true)
        return super.handleSetMediaItems(mediaItems, startIndex, startPositionMs)
    }

    override fun handleSetVolume(volume: Float, flags: Int): ListenableFuture<*> {
        finish(pause = true)
        return super.handleSetVolume(volume, flags)
    }

    override fun handleStop(): ListenableFuture<*> {
        finish(pause = true)
        return super.handleStop()
    }

    override fun handleRelease(): ListenableFuture<*> {
        audio.removeListener(interruptions)
        finish(pause = true)
        return super.handleRelease()
    }
}
