package com.longzhousz.ami.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.longzhousz.ami.playback.PlaybackState
import com.longzhousz.ami.playback.PlayerConnection
import kotlinx.coroutines.isActive

/** A UI-local clock. Read its value in a draw lambda or the smallest progress composable. */
@Composable
internal fun rememberPlaybackClock(
    player: PlayerConnection,
    playback: PlaybackState,
    enabled: Boolean = true,
): State<Long> = rememberPlaybackClock(playback, enabled) { player.currentPosition(playback.id) }

@Composable internal fun rememberPlaybackClock(playback: PlaybackState, enabled: Boolean = true, position: () -> Long): State<Long> {
    val read by rememberUpdatedState(position)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return key(playback.id) { produceState(playback.position, lifecycle, playback, enabled) {
        if (!enabled) {
            value = read()
            return@produceState
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            value = read()
            if (playback.advancing) {
                while (isActive) {
                    // Let Compose's test clock identify this as an infinite frame observer.
                    withInfiniteAnimationFrameNanos { value = read() }
                }
            }
        }
    } }
}
