package com.longzhousz.ami.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.longzhousz.ami.AmiApplication
import com.longzhousz.ami.MainActivity
import com.longzhousz.ami.core.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

fun Song.mediaItem(): MediaItem = MediaItem.Builder().setMediaId(id).setUri(uri)
    .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album)
        .setArtworkUri(cover?.let { android.net.Uri.fromFile(java.io.File(it)) }).build())
    .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(start)
        .apply { end?.let { setEndPositionMs(it) } }.build()).build()

class PlaybackService : MediaSessionService() {
    private val repository get() = (application as AmiApplication).repository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private var restored = false

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).setUsage(C.USAGE_MEDIA).build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
            repeatMode = repository.prefs.getInt("repeat", Player.REPEAT_MODE_ALL)
            shuffleModeEnabled = repository.prefs.getBoolean("shuffle", false)
        }
        session = MediaSession.Builder(this, player)
            .setSessionActivity(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setCallback(object : MediaSession.Callback {
                override fun onPlayerCommandRequest(session: MediaSession, controller: MediaSession.ControllerInfo, playerCommand: Int): Int {
                    repository.scope.launch { repository.db.log("media.command.$playerCommand", "system", "success") }
                    return androidx.media3.session.SessionResult.RESULT_SUCCESS
                }
            }).build()
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { persist() }
        })
        scope.launch {
            val songs = withContext(Dispatchers.IO) { repository.db.songs() }
            // A controller may have already supplied a new queue while disk restoration ran.
            if (player.mediaItemCount == 0) {
                val ids = runCatching { JSONArray(repository.prefs.getString("queue", "[]")) }.getOrDefault(JSONArray())
                val indexed = songs.associateBy { it.id }
                val queue = (0 until ids.length()).mapNotNull { indexed[ids.optString(it)] }
                if (queue.isNotEmpty()) {
                    val index = repository.prefs.getInt("queueIndex", 0).coerceIn(queue.indices)
                    val position = repository.prefs.getLong("position", 0).coerceAtLeast(0)
                    player.setMediaItems(queue.map { it.mediaItem() }, index, position)
                    player.prepare()
                }
            }
            restored = true
            while (true) {
                val sleepAt = repository.prefs.getLong("sleepAt", 0)
                if (sleepAt > 0 && System.currentTimeMillis() >= sleepAt) {
                    player.pause(); repository.prefs.edit().remove("sleepAt").apply()
                }
                persist()
                delay(2000)
            }
        }
    }
    private fun persist() {
        if (!restored) return
        if (player.mediaItemCount == 0) {
            repository.prefs.edit().remove("queue").remove("queueIndex").remove("position").apply()
            return
        }
        val ids = JSONArray().apply { repeat(player.mediaItemCount) { put(player.getMediaItemAt(it).mediaId) } }
        repository.prefs.edit().putString("queue", ids.toString()).putInt("queueIndex", player.currentMediaItemIndex)
            .putLong("position", player.currentPosition.coerceAtLeast(0)).putInt("repeat", player.repeatMode)
            .putBoolean("shuffle", player.shuffleModeEnabled).apply()
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onDestroy() {
        persist(); scope.cancel(); session?.release(); player.release(); super.onDestroy()
    }
}
