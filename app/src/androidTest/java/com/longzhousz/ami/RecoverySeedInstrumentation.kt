package com.longzhousz.ami

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.test.platform.app.InstrumentationRegistry
import com.longzhousz.ami.core.PlaybackSnapshot
import com.longzhousz.ami.core.Song
import com.longzhousz.ami.playback.PlaybackService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Host-only fixture; it is never packaged in Preview/Release or discovered as a JUnit test. */
class RecoverySeedInstrumentation : Instrumentation() {
    private var phase="seed"
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        phase=arguments?.getString("phase") ?: "seed"
        InstrumentationRegistry.registerInstance(this,arguments ?: Bundle())
        start()
    }
    override fun onStart() {
        try {
            waitForIdleSync()
            val repo=(targetContext.applicationContext as AmiApplication).repository
            if (phase=="cleanup") {
                runOnMainSync { targetContext.stopService(Intent(targetContext,PlaybackService::class.java)) }
                repo.db.remove("qa-host-recovery");repo.playback.publish(PlaybackSnapshot())
                val until=System.currentTimeMillis()+10000
                while (repo.db.playbackSnapshot()?.queue?.isNotEmpty()!=false && System.currentTimeMillis()<until) Thread.sleep(25)
            } else seed()
            finish(Activity.RESULT_OK,Bundle().apply { putString("stream","AMI_RECOVERY_OK phase=$phase\n") })
        } catch (error: Throwable) {
            finish(Activity.RESULT_CANCELED,Bundle().apply { putString("stream","AMI_RECOVERY_FAILED ${error.stackTraceToString()}\n") })
        }
    }
    private fun seed() {
        val repo=(targetContext.applicationContext as AmiApplication).repository
        stopPreviousPlaybackSession()
        val song=Song("qa-host-recovery","content://com.longzhousz.ami.test.fixtures/document/album.wav",folder="qa",title="AMI recovery fixture",artist="AMI QA",duration=6000)
        repo.db.upsert(song);repo.playback.publish(PlaybackSnapshot(listOf(song.id),0,800,1,false))
        val activity=startActivitySync(Intent(targetContext,ComponentActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val connected=CountDownLatch(1);val destroyed=CountDownLatch(1)
        var browser:MediaBrowser?=null;var controller:MediaController?=null
        val callback=object:MediaController.Callback() { override fun onSessionDestroyed() { destroyed.countDown() } }
        try {
            runOnMainSync {
                browser=MediaBrowser(targetContext,ComponentName(targetContext,PlaybackService::class.java),object:MediaBrowser.ConnectionCallback() {
                    override fun onConnected() {
                        controller=MediaController(targetContext,browser!!.sessionToken).also { it.registerCallback(callback) }
                        connected.countDown()
                    }
                    override fun onConnectionFailed() { connected.countDown() }
                },null)
                browser!!.connect()
            }
            check(connected.await(40,TimeUnit.SECONDS) && controller!=null)
            runOnMainSync { controller!!.transportControls.play() }
            awaitState(controller!!,PlaybackState.STATE_PLAYING)
            runOnMainSync { controller!!.transportControls.pause() }
            awaitState(controller!!,PlaybackState.STATE_PAUSED)
            runOnMainSync { browser!!.disconnect();activity.finish();targetContext.stopService(Intent(targetContext,PlaybackService::class.java)) }
            check(destroyed.await(30,TimeUnit.SECONDS)) { "Service shutdown did not complete" }
            val until=System.currentTimeMillis()+15000
            while(repo.db.playbackSnapshot()?.queue!=listOf(song.id) && System.currentTimeMillis()<until) Thread.sleep(25)
            check(repo.db.playbackSnapshot()?.queue==listOf(song.id))
        } finally {
            runOnMainSync { controller?.unregisterCallback(callback);browser?.disconnect();activity.finish() }
        }
    }
    private fun awaitState(controller:MediaController,state:Int) {
        val until=System.currentTimeMillis()+30000
        var ready=false
        while(!ready && System.currentTimeMillis()<until) {
            runOnMainSync { ready=controller.playbackState?.state==state && controller.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)=="AMI recovery fixture" }
            if(!ready) Thread.sleep(25)
        }
        check(ready) { "Expected state $state; observed ${controller.playbackState}" }
    }
}
