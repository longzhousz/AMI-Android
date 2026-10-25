package com.longzhousz.ami.playback

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.longzhousz.ami.R
import java.util.concurrent.atomic.AtomicBoolean

/** Experimental vivo extension of the existing MediaStyle notification, never a second card. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class OriginOsMediaNotification(context: Context) : MediaNotification.Provider {
    private val delegate=DefaultMediaNotificationProvider(context)
    private val hints=OriginOsMediaHints(context)
    override fun getNotificationChannelInfo()=delegate.notificationChannelInfo
    override fun handleCustomCommand(session:MediaSession,action:String,extras:Bundle)=delegate.handleCustomCommand(session,action,extras)
    override fun createNotification(session:MediaSession,buttons:ImmutableList<CommandButton>,factory:MediaNotification.ActionFactory,
        callback:MediaNotification.Provider.Callback):MediaNotification {
        fun adapt(value:MediaNotification)=MediaNotification(value.notificationId,hints.apply(value.notification,session.player.isPlaying))
        return adapt(delegate.createNotification(session,buttons,factory) { callback.onNotificationChanged(adapt(it)) })
    }
}

internal class OriginOsMediaHints(
    private val context:Context,
    private val enabled:Boolean=isVivo(),
    private val register:()->Unit={ registerOwnScene(context) },
) {
    private val attempted=AtomicBoolean(false)
    private val payloadReported=AtomicBoolean(false)
    fun apply(original:Notification,playing:Boolean):Notification {
        if (!enabled) return original
        if (attempted.compareAndSet(false,true)) {
            val outcome=runCatching(register).fold({ "available" },{ error ->
                val cause=(error as? java.lang.reflect.InvocationTargetException)?.targetException ?: error
                "unavailable:${cause.javaClass.simpleName}"
            })
            Log.i("AmiOriginOS","Own music scene registration $outcome; island rendering remains system-controlled")
        }
        return runCatching {
            val title=original.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().ifBlank { "AMI" }
            val artist=original.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            val icon=original.getLargeIcon() ?: Icon.createWithResource(context,R.drawable.ic_music)
            val click=original.contentIntent
            val base=Bundle().apply {
                putParcelable("notification.superx.baseInfos.icon",icon)
                putCharSequence("notification.superx.baseInfos.title",title)
                putCharSequence("notification.superx.baseInfos.content",artist)
            }
            val info=Bundle().apply {
                putCharSequence("notification.superx.infos.describe",artist)
                putCharSequence("notification.superx.infos.coreInfo",title)
                putParcelable("notification.superx.infos.image",icon)
            }
            val short=Bundle().apply {
                putParcelable("notification.superx.shortInfos.icon",icon)
                putParcelable("notification.superx.shortInfos.image",icon)
                putCharSequence("notification.superx.shortInfos.describeShort",artist)
                putCharSequence("notification.superx.shortInfos.coreInfoShort",title)
            }
            val capsule=Bundle().apply {
                putInt("notification.superx.capsule.state",1)
                putParcelable("notification.superx.capsule.icon",icon)
                putString("notification.superx.capsule.content",title)
                putParcelable("notification.superx.capsule.clickResp",click)
            }
            val island=Bundle().apply {
                putInt("island.superx.leftTemplate",1)
                putBundle("island.superx.leftInfo",Bundle().apply {
                    putParcelable("island.superx.leftInfo.icon",icon)
                    putString("island.superx.leftInfo.content",title)
                })
                putInt("island.superx.rightTemplate",1)
                putBundle("island.superx.rightInfo",Bundle().apply {
                    putInt("island.superx.rightInfo.waveState",if (playing) 1 else 0)
                    putIntegerArrayList("island.superx.rightInfo.waveColor",arrayListOf(0xffddd5f4.toInt(),0xffa8a2c1.toInt()))
                })
                putParcelable("island.superx.clickResp",click)
            }
            original.clone().apply {
                extras=Bundle(original.extras).apply {
                    putInt("notification.superx.operation",0)
                    putBoolean("notification.superx.showNotify",true)
                    putString("notification.superx.scene",SCENE)
                    putInt("notification.superx.template",1)
                    putBoolean("notification.superx.sound",false)
                    putBoolean("notification.superx.dismissWhenKill",true)
                    putInt("notification.superx.displays",0x100)
                    putParcelable("notification.superx.clickResp",click)
                    putBundle("notification.superx.baseInfos",base)
                    putBundle("notification.superx.infos",info)
                    putBundle("notification.superx.shortInfos",short)
                    putBundle("notification.superx.capsule",capsule)
                    putBundle("notification.superx.island",island)
                }
                if (payloadReported.compareAndSet(false,true)) Log.i("AmiOriginOS","Single MediaStyle island payload attached; scene=$SCENE")
            }
        }.getOrElse { Log.w("AmiOriginOS","Island extras unavailable; keeping MediaStyle",it);original }
    }
    companion object {
        const val SCENE="AMI_MUSIC"
        fun isVivo()=Build.MANUFACTURER.equals("vivo",true) || Build.BRAND.equals("vivo",true) || Build.BRAND.equals("iQOO",true)
        private fun registerOwnScene(context:Context) {
            // Community-observed OriginOS extension. No hidden-API bypass or privilege request.
            // Supply only our own package/scene; absent or denied methods leave MediaStyle intact.
            val list=java.util.List::class.java
            val manager=context.getSystemService(NotificationManager::class.java)
            val method=manager.javaClass.getMethod("setSuperXInfosSceneList",list,list,list,list)
            method.invoke(manager,
                arrayListOf(SCENE),arrayListOf("true"),arrayListOf(context.packageName),arrayListOf("true"))
        }
    }
}
