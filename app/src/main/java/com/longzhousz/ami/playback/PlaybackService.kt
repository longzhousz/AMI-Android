package com.longzhousz.ami.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionError
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.SettableFuture
import com.longzhousz.ami.AmiApplication
import com.longzhousz.ami.MainActivity
import com.longzhousz.ami.core.PlaybackSnapshot
import com.longzhousz.ami.core.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun Song.mediaItem(): MediaItem = MediaItem.Builder().setMediaId(id).setUri(uri)
    .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album)
        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).setIsPlayable(true).setIsBrowsable(false)
        .setDurationMs(duration.takeIf { it > 0 })
        .setArtworkUri(cover?.let { android.net.Uri.fromFile(java.io.File(it)) }).build())
    .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(start)
        .apply { end?.let { setEndPositionMs(it) } }.build()).build()

class PlaybackService : MediaLibraryService() {
    private val recentRoot = "ami-recent"
    private val repository get() = (application as AmiApplication).repository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var player: ExoPlayer
    private var session: MediaLibrarySession? = null
    private var restored = false
    private val restoration = CompletableDeferred<Unit>()
    private var queue: List<String> = emptyList()
    private var positionJob: Job? = null
    private val callback = object : MediaLibrarySession.Callback {
        @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
        override fun onPlaybackResumption(session: MediaSession, controller: MediaSession.ControllerInfo, isForPlayback: Boolean): com.google.common.util.concurrent.ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            scope.launch {
                try {
                    restoration.await()
                    check(player.mediaItemCount > 0) { "没有可恢复的播放队列" }
                    future.set(MediaSession.MediaItemsWithStartPosition((0 until player.mediaItemCount).map { player.getMediaItemAt(it) }, player.currentMediaItemIndex, player.currentPosition))
                } catch (e: Exception) { future.setException(e) }
            }
            return future
        }
        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?) =
            Futures.immediateFuture(LibraryResult.ofItem(MediaItem.Builder().setMediaId(if (params?.isRecent == true) recentRoot else "ami-root")
                .setMediaMetadata(MediaMetadata.Builder().setTitle("AMI").setIsBrowsable(true).setIsPlayable(false).setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED).build()).build(), params))
        // The standard recent root exposes one resumable item, not the private collection.
        override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String,
            page: Int, pageSize: Int, params: LibraryParams?): com.google.common.util.concurrent.ListenableFuture<LibraryResult<com.google.common.collect.ImmutableList<MediaItem>>> {
            if (parentId != recentRoot || page != 0) return Futures.immediateFuture(LibraryResult.ofItemList(emptyList(), params))
            val future = SettableFuture.create<LibraryResult<com.google.common.collect.ImmutableList<MediaItem>>>()
            scope.launch {
                try {
                    restoration.await()
                    future.set(LibraryResult.ofItemList(listOfNotNull(player.currentMediaItem), params))
                } catch (e: Exception) { future.setException(e) }
            }
            return future
        }
        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String) =
            Futures.immediateFuture(player.currentMediaItem?.takeIf { it.mediaId == mediaId }?.let { LibraryResult.ofItem(it, null) }
                ?: LibraryResult.ofError<MediaItem>(SessionError.ERROR_BAD_VALUE))
        override fun onSubscribe(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, params: LibraryParams?) =
            Futures.immediateFuture(if (parentId == recentRoot || parentId == "ami-root") LibraryResult.ofVoid(params)
                else LibraryResult.ofError<Void>(SessionError.ERROR_BAD_VALUE))
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        if (OriginOsMediaHints.isVivo()) setMediaNotificationProvider(OriginOsMediaNotification(this))
        player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).setUsage(C.USAGE_MEDIA).build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
            repeatMode = repository.prefs.getInt("repeat", Player.REPEAT_MODE_ALL)
            shuffleModeEnabled = repository.prefs.getBoolean("shuffle", false)
        }
        session = MediaLibrarySession.Builder(this, player, callback)
            .setSessionActivity(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()
        player.addListener(object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                    val ids = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
                    if (ids != queue) queue = ids
                }
            }
            override fun onEvents(player: Player, events: Player.Events) {
                if (restored && events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION))
                    session?.notifyChildrenChanged(recentRoot, if (player.currentMediaItem == null) 0 else 1, null)
                if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_POSITION_DISCONTINUITY,
                        Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED,
                        Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED, Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)) checkpoint()
                updatePositionJob()
            }
        })
        repository.prefs.edit().remove("sleepAt").apply()
        scope.launch {
            val snapshot = repository.playback.load()
            val songs = withContext(Dispatchers.IO) { repository.db.playbackSongs(snapshot.queue) }
            // Commands received while disk restoration ran take precedence over the old queue.
            if (player.mediaItemCount == 0) {
                val (items, location) = snapshot.restore(songs)
                player.repeatMode = snapshot.repeat.coerceIn(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ALL)
                player.shuffleModeEnabled = snapshot.shuffle
                if (items.isNotEmpty()) {
                    player.setMediaItems(items.map { it.mediaItem() }, location.first, location.second)
                    player.prepare()
                }
            }
            restored = true
            checkpoint()
            updatePositionJob()
            restoration.complete(Unit)
            session?.notifyChildrenChanged(recentRoot, if (player.currentMediaItem == null) 0 else 1, null)
        }
    }
    private fun updatePositionJob() {
        if (!restored || !player.isPlaying) { positionJob?.cancel(); positionJob = null; return }
        if (positionJob?.isActive == true) return
        positionJob = scope.launch {
            while (player.isPlaying) {
                delay(10_000)
                if (player.isPlaying) checkpoint()
            }
        }
    }
    private fun checkpoint() {
        if (!restored) return
        val position = if (queue.isEmpty() || player.playbackState == Player.STATE_ENDED) 0 else player.currentPosition.coerceAtLeast(0)
        repository.playback.publish(PlaybackSnapshot(queue, player.currentMediaItemIndex.coerceAtLeast(0), position, player.repeatMode, player.shuffleModeEnabled))
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session
    override fun onDestroy() {
        checkpoint()
        restored = false
        restoration.cancel()
        scope.cancel()
        session?.release(); player.release()
        super.onDestroy()
    }
}
