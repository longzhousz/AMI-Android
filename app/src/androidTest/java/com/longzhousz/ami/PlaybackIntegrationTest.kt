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
import com.longzhousz.ami.playback.PlayMode
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
    @Test fun fourPlaybackModesApplyToTheNativePlayerWithoutReorderingTheVisibleQueue() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = ActivityScenario.launch(MainActivity::class.java)
        val source = File(context.cacheDir, "qa-mode-source.flac")
        instrumentation.context.assets.open("qa-96k-24bit.flac").use { input -> source.outputStream().use { input.copyTo(it) } }
        var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
        lateinit var model: AmiViewModel
        activity.onActivity { model = ViewModelProvider(it)[AmiViewModel::class.java] }
        instrumentation.runOnMainSync { future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync() }
        val controller = future!!.get(40, TimeUnit.SECONDS)
        fun await(message: String, predicate: () -> Boolean) {
            val deadline = System.currentTimeMillis() + 15000
            var done = false
            while (!done && System.currentTimeMillis() < deadline) {
                instrumentation.runOnMainSync { done = predicate() }
                if (!done) Thread.sleep(30)
            }
            assertTrue(message, done)
        }
        val songs = List(15) { Song("qa-mode-$it", Uri.fromFile(source).toString(), folder = "qa", title = "Mode $it",
            artist = "Artist ${it / 3}", album = "Album ${it / 3}", duration = 6000) }
        try {
            await("UI controller should connect") { model.player.state.value.connected }
            instrumentation.runOnMainSync {
                controller.pause(); controller.setMediaItems(songs.map { it.mediaItem() }); controller.prepare()
                model.player.mode(PlayMode.Shuffle)
            }
            await("Random mode should reach the native player") { controller.shuffleModeEnabled && controller.repeatMode == Player.REPEAT_MODE_ALL }
            await("Balanced next index should reach the controller") { controller.nextMediaItemIndex / 3 != controller.currentMediaItemIndex / 3 }
            val visited = mutableListOf<Int>()
            repeat(songs.size) {
                instrumentation.runOnMainSync { visited += controller.currentMediaItemIndex; controller.seekToNextMediaItem() }
                // Allow the session's command and state response to settle before observing it.
                val before = visited.last()
                await("Random next should move to another track") { controller.currentMediaItemIndex != before }
            }
            instrumentation.runOnMainSync {
                assertEquals(songs.indices.toSet(), visited.toSet())
                assertEquals(visited.first(), controller.currentMediaItemIndex)
                assertEquals(songs.map { it.id }, (0 until controller.mediaItemCount).map { controller.getMediaItemAt(it).mediaId })
                assertTrue("The shuffle should break up groups of the same artist", visited.zipWithNext().count { (a, b) -> a / 3 == b / 3 } <= 3)
                model.player.mode(PlayMode.Ordered)
            }
            await("Ordered mode disables both loop and random") { !controller.shuffleModeEnabled && controller.repeatMode == Player.REPEAT_MODE_OFF }
            instrumentation.runOnMainSync { controller.seekTo(songs.lastIndex, 5800); controller.play() }
            await("Ordered mode stops at the queue end") { controller.playbackState == Player.STATE_ENDED }
            instrumentation.runOnMainSync { model.player.mode(PlayMode.Loop); controller.seekTo(songs.lastIndex, 5800); controller.play() }
            await("List loop should return to the first track") { controller.currentMediaItemIndex == 0 && controller.isPlaying }
            instrumentation.runOnMainSync { model.player.mode(PlayMode.One); controller.seekTo(2, 5500) }
            await("Single loop should restart the same item") { controller.currentMediaItemIndex == 2 && controller.currentPosition in 0..3000 && controller.isPlaying }
            instrumentation.runOnMainSync {
                assertFalse(controller.shuffleModeEnabled)
                assertEquals(Player.REPEAT_MODE_ONE, controller.repeatMode)
            }
        } finally {
            instrumentation.runOnMainSync {
                controller.stop(); controller.clearMediaItems(); model.player.mode(PlayMode.Loop)
                MediaController.releaseFuture(future!!)
            }
            source.delete(); activity.close()
        }
    }

    @Test fun highResolutionFlacDecodesSeeksAndAdvancesThroughTheProductionPlayer() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val activity = ActivityScenario.launch(MainActivity::class.java)
        val source = File(context.cacheDir, "qa-96k-24bit.flac")
        instrumentation.context.assets.open("qa-96k-24bit.flac").use { input -> source.outputStream().use { output -> input.copyTo(output) } }
        var future: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
        instrumentation.runOnMainSync { future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync() }
        val controller = future!!.get(40, TimeUnit.SECONDS)
        val repo = (context.applicationContext as AmiApplication).repository
        fun countedMillis(): Long = kotlinx.coroutines.runBlocking {
            repo.listeningStats.report(com.longzhousz.ami.core.ListeningPeriod(com.longzhousz.ami.core.ListeningScale.MONTH, java.time.LocalDate.now()))
            synchronized(repo.db) {
                repo.db.readableDatabase.rawQuery("SELECT SUM(s.millis) FROM listening_song_day s JOIN listening_tracks t ON t.id=s.track WHERE t.music_key IN (?,?)",
                    arrayOf("local:${com.longzhousz.ami.core.digest("qa-hires")}", "local:${com.longzhousz.ami.core.digest("qa-hires-next")}")).use { it.moveToFirst(); it.getLong(0) }
            }
        }
        val heardBefore = countedMillis()
        val started = android.os.SystemClock.elapsedRealtime()
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
            instrumentation.runOnMainSync { controller.pause() }
            assertTrue(await { !controller.isPlaying })
            val heard = countedMillis() - heardBefore
            assertTrue("Native listening time must follow elapsed playback, including seeks", heard >= 100 && heard <= android.os.SystemClock.elapsedRealtime() - started + 500)
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
