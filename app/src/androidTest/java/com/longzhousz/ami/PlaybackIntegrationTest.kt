package com.longzhousz.ami

import android.content.ComponentName
import android.net.Uri
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.ViewModelProvider
import com.longzhousz.ami.core.Song
import com.longzhousz.ami.playback.PlaybackService
import com.longzhousz.ami.playback.mediaItem
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PlaybackIntegrationTest {
    @Test fun highResolutionFlacDecodesSeeksAndAdvancesThroughTheProductionPlayer() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = ActivityScenario.launch(MainActivity::class.java)
        val source = File(context.cacheDir, "qa-96k-24bit.flac")
        instrumentation.context.assets.open("qa-96k-24bit.flac").use { input -> source.outputStream().use { output -> input.copyTo(output) } }
        var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
        instrumentation.runOnMainSync { future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync() }
        val controller = future!!.get(40, TimeUnit.SECONDS)
        fun await(predicate: () -> Boolean): Boolean {
            val deadline = System.currentTimeMillis() + 20000
            var ready = false
            while (!ready && System.currentTimeMillis() < deadline) {
                instrumentation.runOnMainSync { ready = predicate() }
                if (!ready) Thread.sleep(100)
            }
            return ready
        }
        try {
            instrumentation.runOnMainSync {
                controller.repeatMode = Player.REPEAT_MODE_OFF
                val song = Song("qa-hires", Uri.fromFile(source).toString(), folder = "qa", title = "96kHz / 24-bit QA", duration = 6000)
                controller.setMediaItems(listOf(song.mediaItem(), song.copy(id = "qa-hires-next").mediaItem()))
                controller.prepare(); controller.play()
            }
            assertTrue("The production float-enabled player must decode 24-bit FLAC", await { controller.isPlaying && controller.currentPosition > 100 })
            instrumentation.runOnMainSync {
                val formats = controller.currentTracks.groups.flatMap { group -> (0 until group.length).map(group::getTrackFormat) }
                assertTrue(formats.any { it.sampleMimeType == "audio/flac" && it.sampleRate == 96000 && it.channelCount == 2 })
                assertEquals(6000L, controller.duration)
                controller.pause(); controller.seekTo(2000); controller.play()
            }
            assertTrue("High-resolution seeking should resume", await { controller.isPlaying && controller.currentPosition in 2100..5000 })
            instrumentation.runOnMainSync { controller.seekTo(5500); controller.play() }
            assertTrue("The prepared neighbor must play through the same native session", await {
                controller.currentMediaItem?.mediaId == "qa-hires-next" && controller.isPlaying && controller.playerError == null
            })
        } finally {
            instrumentation.runOnMainSync { controller.stop(); controller.clearMediaItems(); MediaController.releaseFuture(future!!) }
            source.delete(); activity.close()
        }
    }

    @Test fun cueClipPlaysThroughNativeSessionWithRelativeDurationAndSeek() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = ActivityScenario.launch(MainActivity::class.java)
        val source = File(context.cacheDir, "playback-test.wav")
        val samples = 44100 * 6
        val dataSize = samples * 2
        val bytes = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVEfmt ".toByteArray()).putInt(16)
        bytes.putShort(1).putShort(1).putInt(44100).putInt(88200).putShort(2).putShort(16)
        bytes.put("data".toByteArray()).putInt(dataSize)
        source.writeBytes(bytes.array())
        var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
        instrumentation.runOnMainSync { future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync() }
        val controller = future!!.get(40, TimeUnit.SECONDS)
        try {
            val song = Song("test-cue", Uri.fromFile(source).toString(), folder = "test", title = "CUE Clip", duration = 3000, start = 1000, end = 4000)
            instrumentation.runOnMainSync {
                controller.repeatMode = Player.REPEAT_MODE_OFF
                controller.setMediaItems(listOf(song.mediaItem())); controller.prepare(); controller.play()
            }
            val deadline = System.currentTimeMillis() + 30000
            var duration = 0L
            var ready = false
            while (!ready && System.currentTimeMillis() < deadline) {
                instrumentation.runOnMainSync { duration = controller.duration; ready = controller.playbackState == Player.STATE_READY }
                if (!ready) Thread.sleep(200)
            }
            assertTrue("Native player should reach READY", ready)
            assertEquals(3000, duration)
            instrumentation.runOnMainSync { controller.pause(); controller.seekTo(1250) }
            Thread.sleep(300)
            var position = 0L
            instrumentation.runOnMainSync { position = controller.currentPosition }
            assertTrue("Seek position should be relative to the CUE clip", position in 1200..1300)
            instrumentation.runOnMainSync { controller.seekTo(2800); controller.play() }
            var ended = false
            val endDeadline = System.currentTimeMillis() + 10000
            while (!ended && System.currentTimeMillis() < endDeadline) {
                instrumentation.runOnMainSync { ended = controller.playbackState == Player.STATE_ENDED }
                if (!ended) Thread.sleep(100)
            }
            assertTrue("Clip should reach the end with repeat disabled", ended)
            activity.onActivity { ViewModelProvider(it)[AmiViewModel::class.java].player.toggle() }
            var restarted = false
            val restartDeadline = System.currentTimeMillis() + 10000
            while (!restarted && System.currentTimeMillis() < restartDeadline) {
                instrumentation.runOnMainSync { restarted = controller.isPlaying }
                if (!restarted) Thread.sleep(100)
            }
            assertTrue("Play should restart an ended playlist", restarted)
        } finally {
            instrumentation.runOnMainSync { controller.stop(); controller.clearMediaItems(); MediaController.releaseFuture(future!!) }
            source.delete()
            activity.close()
        }
    }
}
