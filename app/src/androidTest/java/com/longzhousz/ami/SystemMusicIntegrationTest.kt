package com.longzhousz.ami

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.PlaybackState
import androidx.activity.ComponentActivity
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
class SystemMusicIntegrationTest {
    private fun stopPreviousPlaybackSession() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val connected = CountDownLatch(1)
        val destroyed = CountDownLatch(1)
        var browser: MediaBrowser? = null
        var controls: MediaController? = null
        val callback = object : MediaController.Callback() {
            override fun onSessionDestroyed() { destroyed.countDown() }
        }
        try {
            instrumentation.runOnMainSync {
                browser = MediaBrowser(context, ComponentName(context, PlaybackService::class.java), object : MediaBrowser.ConnectionCallback() {
                    override fun onConnected() {
                        controls = MediaController(context, browser!!.sessionToken).also { it.registerCallback(callback) }
                        connected.countDown()
                    }
                    override fun onConnectionFailed() { connected.countDown() }
                }, null)
                browser!!.connect()
            }
            assertTrue(connected.await(40, TimeUnit.SECONDS))
            assertNotNull(controls)
            instrumentation.runOnMainSync { browser!!.disconnect(); context.stopService(Intent(context, PlaybackService::class.java)) }
            // stopService() returns before onDestroy(); observe the actual old session release.
            assertTrue("Previous media session should finish shutting down", destroyed.await(30, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
        } finally {
            instrumentation.runOnMainSync { controls?.unregisterCallback(callback); browser?.disconnect() }
        }
    }
    @Test fun packageDeclaresAudioCategoryMusicEntryAndLegacyBrowserService() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(ApplicationInfo.CATEGORY_AUDIO, context.applicationInfo.category)
        val music = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC).setPackage(context.packageName)
        assertTrue(context.packageManager.queryIntentActivities(music, PackageManager.MATCH_DEFAULT_ONLY).any { it.activityInfo.name == MainActivity::class.java.name })
        val browser = Intent("android.media.browse.MediaBrowserService").setPackage(context.packageName)
        assertTrue(context.packageManager.queryIntentServices(browser, 0).any { it.serviceInfo.name == PlaybackService::class.java.name })
    }
    @Test fun platformBrowserCanResumeAndControlSavedMusicWithoutTheMainScreen() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        checkNotNull(context.packageManager.resolveContentProvider("com.longzhousz.ami.test.fixtures", 0))
        val repo = (context.applicationContext as AmiApplication).repository
        val activity = ActivityScenario.launch(ComponentActivity::class.java)
        val id = "qa-system-music-${System.nanoTime()}"
        val song = Song(id, "content://com.longzhousz.ami.test.fixtures/document/album.wav", folder = "qa", title = "System Music", artist = "System Artist", duration = 6000)
        val connected = CountDownLatch(1)
        var browser: MediaBrowser? = null
        var platform: MediaController? = null
        try {
            stopPreviousPlaybackSession()
            repo.db.upsert(song)
            repo.playback.publish(PlaybackSnapshot(listOf(id), 0, 500, 1, false))
            instrumentation.runOnMainSync {
                browser = MediaBrowser(context, ComponentName(context, PlaybackService::class.java), object : MediaBrowser.ConnectionCallback() {
                    override fun onConnected() { platform = MediaController(context, browser!!.sessionToken); connected.countDown() }
                    override fun onConnectionFailed() { connected.countDown() }
                }, null)
                browser!!.connect()
            }
            assertTrue("Platform media browser should connect", connected.await(40, TimeUnit.SECONDS))
            assertNotNull(platform)
            instrumentation.runOnMainSync { assertEquals("ami-root", browser!!.root); platform!!.transportControls.play() }
            fun awaitState(state: Int) {
                val deadline = System.currentTimeMillis() + 30000
                var reached = false
                var observed = ""
                while (!reached && System.currentTimeMillis() < deadline) {
                    instrumentation.runOnMainSync {
                        val title = platform!!.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
                        reached = platform!!.playbackState?.state == state && title == "System Music"
                        observed = "${platform!!.playbackState}; title=$title"
                    }
                    if (!reached) Thread.sleep(50)
                }
                assertTrue("Platform playback state should become $state; observed $observed", reached)
            }
            awaitState(PlaybackState.STATE_PLAYING)
            instrumentation.runOnMainSync {
                val metadata = checkNotNull(platform!!.metadata)
                assertEquals("System Music", metadata.getString(MediaMetadata.METADATA_KEY_TITLE))
                assertEquals("System Artist", metadata.getString(MediaMetadata.METADATA_KEY_ARTIST))
                assertEquals(6000L, metadata.getLong(MediaMetadata.METADATA_KEY_DURATION))
                assertEquals(android.media.AudioAttributes.USAGE_MEDIA, platform!!.playbackInfo.audioAttributes.usage)
                platform!!.transportControls.pause()
            }
            awaitState(PlaybackState.STATE_PAUSED)
        } finally {
            instrumentation.runOnMainSync { platform?.transportControls?.stop(); browser?.disconnect() }
            activity.close()
            instrumentation.runOnMainSync { context.stopService(Intent(context, PlaybackService::class.java)) }
            instrumentation.waitForIdleSync()
            repo.playback.publish(PlaybackSnapshot())
            repo.db.remove(id); repo.reload()
        }
    }
}
