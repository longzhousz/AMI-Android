package com.longzhousz.ami

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longzhousz.ami.playback.FadingPausePlayer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class FadingPauseTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun manualPauseLowersRealPlayerGainAndImmediatelyReportsPaused() {
        val checkpoints = AtomicInteger()
        withAudio({ checkpoints.incrementAndGet() }) { audio, controls ->
            val samples = mutableListOf<Pair<Long, Float>>()
            val started = SystemClock.uptimeMillis()
            instrumentation.runOnMainSync {
                audio.addListener(
                    object : Player.Listener {
                        override fun onVolumeChanged(volume: Float) {
                            if (audio.playWhenReady) samples += (SystemClock.uptimeMillis() - started) to volume
                        }
                    },
                )
                controls.pause()
                assertFalse("The button and media session must immediately reflect the request", controls.playWhenReady)
                assertTrue("The audible player should still be fading", audio.isPlaying)
                assertEquals(.63f, controls.volume, .001f)
            }
            await { !audio.playWhenReady && audio.volume == .63f }
            assertEquals(1, checkpoints.get())
            assertTrue("The fade must contain intermediate gain levels", samples.count { it.second > .01f && it.second < .62f } >= 3)
            assertTrue("Gain should fall continuously", samples.zipWithNext().all { (a, b) -> a.second >= b.second })
            assertTrue("The sound should fade rather than cut off immediately", samples.last().first >= 250)
            val output = File(instrumentation.targetContext.filesDir, "qa/pause-envelope.csv")
            output.parentFile!!.mkdirs()
            output.writeText("elapsed_ms,player_gain\n" + samples.joinToString("\n") { "${it.first},${it.second}" } + "\n")
            Thread.sleep(450)
            instrumentation.runOnMainSync {
                assertEquals(.63f, audio.volume, .001f)
                assertFalse(audio.isPlaying)
            }
        }
    }

    @Test fun resumeAndNewTrackCancelThePendingPauseWithoutLeavingLowVolume() {
        withAudio { audio, controls ->
            instrumentation.runOnMainSync { controls.pause() }
            await { audio.volume < .55f }
            instrumentation.runOnMainSync { controls.play() }
            Thread.sleep(500)
            instrumentation.runOnMainSync {
                assertTrue(audio.isPlaying)
                assertEquals(.63f, audio.volume, .001f)
                controls.pause()
                controls.seekTo(1, 0)
                controls.play()
            }
            await { audio.isPlaying && audio.currentMediaItemIndex == 1 }
            Thread.sleep(500)
            instrumentation.runOnMainSync {
                assertTrue("An old fade must not pause a newly selected track", audio.isPlaying)
                assertEquals(1, audio.currentMediaItemIndex)
                assertEquals(.63f, audio.volume, .001f)
            }
        }
    }

    @Test fun nativeInterruptionCutsTheFadeImmediatelyAndKeepsItPaused() {
        withAudio { audio, controls ->
            instrumentation.runOnMainSync { controls.pause() }
            await { audio.volume < .55f }
            instrumentation.runOnMainSync {
                // Focus/noisy handling operates on ExoPlayer itself, outside the manual wrapper.
                audio.pause()
            }
            await { !audio.playWhenReady && audio.volume == .63f }
            Thread.sleep(450)
            instrumentation.runOnMainSync {
                assertFalse(controls.playWhenReady)
                assertFalse(audio.isPlaying)
            }
        }
    }

    @Test fun silentAndUnpreparedPlayersPauseWithoutSchedulingAnAudibleTail() {
        withAudio { audio, controls ->
            instrumentation.runOnMainSync {
                audio.volume = 0f
                controls.pause()
                assertFalse(audio.playWhenReady)
                controls.stop()
                controls.playWhenReady = true
                controls.pause()
                assertFalse(audio.playWhenReady)
                assertEquals(Player.STATE_IDLE, audio.playbackState)
            }
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            Thread.sleep(10)
        }
        fail("Native audio did not reach the expected state")
    }

    private fun withAudio(
        onPause: () -> Unit = {},
        test: (ExoPlayer, FadingPausePlayer) -> Unit,
    ) {
        val context = instrumentation.targetContext
        val activity = ActivityScenario.launch(ComponentActivity::class.java)
        val source = File(context.cacheDir, "qa-pause.flac")
        instrumentation.context.assets
            .open("qa-96k-24bit.flac")
            .use { input -> source.outputStream().use { input.copyTo(it) } }
        lateinit var audio: ExoPlayer
        var controls: FadingPausePlayer? = null
        try {
            stopPreviousPlaybackSession()
            instrumentation.runOnMainSync {
                audio = ExoPlayer.Builder(context, DefaultRenderersFactory(context).setEnableAudioFloatOutput(true)).build()
                audio.setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .setUsage(C.USAGE_MEDIA)
                        .build(),
                    true,
                )
                audio.volume = .63f
                val transport = FadingPausePlayer(audio, onPause)
                controls = transport
                transport.setMediaItems(
                    (0..1).map {
                        MediaItem
                            .Builder()
                            .setMediaId("pause-$it")
                            .setUri(android.net.Uri.fromFile(source))
                            .build()
                    },
                )
                transport.prepare()
                transport.play()
            }
            await { audio.isPlaying }
            test(audio, checkNotNull(controls))
        } finally {
            instrumentation.runOnMainSync { controls?.release() }
            source.delete()
            activity.close()
        }
    }
}
