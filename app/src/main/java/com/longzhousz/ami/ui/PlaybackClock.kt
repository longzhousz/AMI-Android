package com.longzhousz.ami.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.key
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
): State<Long> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return key(player, playback.id) { produceState(playback.position, player, lifecycle, playback, enabled) {
        if (!enabled) {
            value = player.currentPosition(playback.id)
            return@produceState
        }
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            value = player.currentPosition(playback.id)
            if (playback.advancing) {
                while (isActive) {
                    // Let Compose's test clock identify this as an infinite frame observer.
                    withInfiniteAnimationFrameNanos { value = player.currentPosition(playback.id) }
                }
            }
        }
    } }
}
