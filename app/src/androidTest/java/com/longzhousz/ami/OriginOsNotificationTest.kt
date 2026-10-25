package com.longzhousz.ami

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.media.session.MediaSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longzhousz.ami.playback.OriginOsMediaHints
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OriginOsNotificationTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun islandHintsPreserveTheOriginalMediaStyleTokenChannelAndActions() {
        val session=MediaSession(context,"qa-island")
        try {
            val click=PendingIntent.getActivity(context,55,Intent(context,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE)
            val base=Notification.Builder(context,"media-test").setSmallIcon(R.drawable.ic_music).setContentTitle("Song").setContentText("Artist")
                .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken)).setContentIntent(click)
                .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(context,R.drawable.ic_music),"Play",click).build()).build()
            var registrations=0
            val hints=OriginOsMediaHints(context,true) { registrations++ }
            val notice=hints.apply(base,true)
            assertEquals(1,registrations)
            assertEquals(base.channelId,notice.channelId);assertEquals(base.flags,notice.flags)
            assertEquals(base.contentIntent,notice.contentIntent);assertEquals(base.actions.size,notice.actions.size)
            assertEquals(session.sessionToken,notice.extras.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION))
            assertEquals(base.extras.getString(Notification.EXTRA_TEMPLATE),notice.extras.getString(Notification.EXTRA_TEMPLATE))
            assertFalse(base.extras.containsKey("notification.superx.scene"))
            assertEquals(OriginOsMediaHints.SCENE,notice.extras.getString("notification.superx.scene"))
            val island=notice.extras.getBundle("notification.superx.island")!!
            assertEquals(1,island.getBundle("island.superx.rightInfo")!!.getInt("island.superx.rightInfo.waveState"))
            assertEquals(0,hints.apply(base,false).extras.getBundle("notification.superx.island")!!.getBundle("island.superx.rightInfo")!!.getInt("island.superx.rightInfo.waveState"))
            assertEquals(1,registrations)
        } finally { session.release() }
    }
    @Test fun unsupportedDevicesAndDeniedRegistrationKeepNormalPlaybackNotification() {
        val base=Notification.Builder(context,"media-test").setSmallIcon(R.drawable.ic_music).setContentTitle("Song").build()
        assertSame(base,OriginOsMediaHints(context,false) { error("Should not register") }.apply(base,true))
        val failed=OriginOsMediaHints(context,true) { throw SecurityException("denied") }.apply(base,true)
        assertEquals("Song",failed.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(base.channelId,failed.channelId)
    }
}
