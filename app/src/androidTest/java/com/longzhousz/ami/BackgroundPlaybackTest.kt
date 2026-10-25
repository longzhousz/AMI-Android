package com.longzhousz.ami

import android.content.ComponentName
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Bundle
import android.service.media.MediaBrowserService
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longzhousz.ami.core.PlaybackSnapshot
import com.longzhousz.ami.core.Song
import com.longzhousz.ami.playback.PlaybackService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BackgroundPlaybackTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private fun await(message: String,predicate: ()->Boolean) {
        val until=System.currentTimeMillis()+30000
        var ready=false
        while(!ready && System.currentTimeMillis()<until) { instrumentation.runOnMainSync { ready=predicate() };if(!ready) Thread.sleep(25) }
        assertTrue(message,ready)
    }
    private fun scenario(block: (MediaBrowser,MediaController,ActivityScenario<ComponentActivity>,Song)->Unit) {
        val activity=ActivityScenario.launch(ComponentActivity::class.java)
        val repo=(context.applicationContext as AmiApplication).repository
        val id="qa-background-${System.nanoTime()}"
        val song=Song(id,"content://com.longzhousz.ami.test.fixtures/document/album.wav",folder="qa",title="Background fixture",duration=6000)
        var browser:MediaBrowser?=null;var controller:MediaController?=null
        try {
            stopPreviousPlaybackSession()
            repo.db.upsert(song);repo.playback.publish(PlaybackSnapshot(listOf(id),0,500,1,false))
            val connected=CountDownLatch(1)
            instrumentation.runOnMainSync {
                val hints=Bundle().apply { putBoolean(MediaBrowserService.BrowserRoot.EXTRA_RECENT,true) }
                browser=MediaBrowser(context,ComponentName(context,PlaybackService::class.java),object:MediaBrowser.ConnectionCallback() {
                    override fun onConnected() { controller=MediaController(context,browser!!.sessionToken);connected.countDown() }
                    override fun onConnectionFailed() { connected.countDown() }
                },hints)
                browser!!.connect()
            }
            assertTrue(connected.await(40,TimeUnit.SECONDS));assertNotNull(controller)
            block(browser!!,controller!!,activity,song)
        } finally {
            instrumentation.runOnMainSync { controller?.transportControls?.stop();browser?.disconnect() }
            stopPreviousPlaybackSession()
            repo.playback.publish(PlaybackSnapshot());repo.db.remove(id);repo.reload()
            activity.close()
        }
    }
    @Test fun recentRootContainsOnlyTheResumableTrack()=scenario { browser,_,_,song ->
        assertEquals("ami-recent",browser.root)
        val loaded=CountDownLatch(1);var items:List<MediaBrowser.MediaItem>?=null
        instrumentation.runOnMainSync {
            browser.subscribe(browser.root,object:MediaBrowser.SubscriptionCallback() {
                override fun onChildrenLoaded(parentId:String,children:MutableList<MediaBrowser.MediaItem>) { items=children.toList();loaded.countDown() }
                override fun onError(parentId:String) { loaded.countDown() }
            })
        }
        assertTrue(loaded.await(30,TimeUnit.SECONDS))
        assertEquals(listOf(song.id),items!!.map { it.mediaId })
        assertTrue(items!!.single().isPlayable)
        assertEquals(song.title,items!!.single().description.title.toString())
    }
    @Test fun backgroundPlaybackSurvivesScreenOffAndTransientFocusThenHonorsTheSleepTimer()=scenario { _,controller,activity,_ ->
        val repo=(context.applicationContext as AmiApplication).repository
        val audio=context.getSystemService(AudioManager::class.java)
        val focus=AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { }.build()
        try {
            instrumentation.runOnMainSync { controller.transportControls.play() }
            await("Playback should begin") { controller.playbackState?.state==PlaybackState.STATE_PLAYING }
            activity.moveToState(Lifecycle.State.CREATED)
            instrumentation.uiAutomation.executeShellCommand("input keyevent 223").close()
            Thread.sleep(2500)
            await("Playback should continue while screen is off") { controller.playbackState?.state==PlaybackState.STATE_PLAYING }
            instrumentation.runOnMainSync { assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED,audio.requestAudioFocus(focus)) }
            await("Transient audio focus should pause playback") { controller.playbackState?.state==PlaybackState.STATE_PAUSED }
            instrumentation.runOnMainSync { audio.abandonAudioFocusRequest(focus) }
            await("Playback should resume after focus returns") { controller.playbackState?.state==PlaybackState.STATE_PLAYING }
            repo.prefs.edit().putLong("sleepAt",System.currentTimeMillis()+1500).apply()
            await("Sleep timer should pause in the background") { controller.playbackState?.state==PlaybackState.STATE_PAUSED }
            assertEquals(0L,repo.prefs.getLong("sleepAt",0))
        } finally {
            instrumentation.runOnMainSync { audio.abandonAudioFocusRequest(focus) }
            repo.prefs.edit().remove("sleepAt").apply()
            instrumentation.uiAutomation.executeShellCommand("input keyevent 224").close()
            instrumentation.uiAutomation.executeShellCommand("wm dismiss-keyguard").close()
        }
    }
}
