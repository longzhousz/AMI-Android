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
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import com.longzhousz.ami.core.isCloudSong
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
import com.longzhousz.ami.core.ListeningCounter
import com.longzhousz.ami.core.ListeningTrack
import com.longzhousz.ami.core.listeningTrack
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun Song.mediaItem(): MediaItem {
    val extras = android.os.Bundle().apply {
        neteaseId?.let { putString("ami.stats.neteaseId", it) }
        primaryArtistId?.let { putString("ami.stats.artistId", it) }
        primaryArtistName?.let { putString("ami.stats.artistName", it) }
        albumId?.let { putString("ami.stats.albumId", it) }
    }
    return MediaItem.Builder().setMediaId(id).setUri(uri)
    .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album).setAlbumArtist(albumArtist)
        .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).setIsPlayable(true).setIsBrowsable(false).setExtras(extras)
        .setDurationMs(duration.takeIf { it > 0 })
        .setArtworkUri(cover?.let { if (it.startsWith("https://")) android.net.Uri.parse(it) else android.net.Uri.fromFile(java.io.File(it)) }).build())
    .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(start)
        .apply { end?.let { setEndPositionMs(it) } }.build()).build()
}

private fun MediaItem.listeningIdentity(): ListeningTrack {
    val meta = mediaMetadata
    return Song(id = mediaId, uri = "", folder = "", title = meta.title?.toString().orEmpty(),
        artist = meta.artist?.toString().orEmpty(), album = meta.albumTitle?.toString().orEmpty(),
        albumArtist = meta.albumArtist?.toString().orEmpty(),
        cover = meta.artworkUri?.let { if (it.scheme == "file") it.path else it.toString() },
        neteaseId = meta.extras?.getString("ami.stats.neteaseId") ?: mediaId.removePrefix("netease:").takeIf { mediaId.startsWith("netease:") },
        primaryArtistId = meta.extras?.getString("ami.stats.artistId"), primaryArtistName = meta.extras?.getString("ami.stats.artistName"),
        albumId = meta.extras?.getString("ami.stats.albumId")).listeningTrack()
}

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
    private val listening = ListeningCounter()
    private var statsItem: MediaItem? = null
    private var statsTrack: ListeningTrack? = null
    private var statsFlushAt = 0L
    private var cacheRetry: Job? = null
    private var cacheRetryId: String? = null
    private lateinit var radio: RadioQueue
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
        val data = DefaultDataSource.Factory(this)
        val source = androidx.media3.datasource.DataSource.Factory {
            var local: android.net.Uri? = null
            var opened = false
            ResolvingDataSource.Factory(data) { spec ->
                if (spec.uri.scheme != "ami-cloud") spec else {
                    // A seek must not splice a newly cached rendition into an existing online source.
                    // A local source keeps its complete file; if it disappears, recovery prepares anew.
                    val uri = local ?: android.net.Uri.parse(repository.cloud.resolve(spec.uri.toString(), allowCache = !opened))
                    if (uri.scheme == "file") local = uri
                    opened = true
                    spec.withUri(uri)
                }
            }.createDataSource()
        }
        player = ExoPlayer.Builder(this, androidx.media3.exoplayer.DefaultRenderersFactory(this).setEnableAudioFloatOutput(true))
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(source)).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).setUsage(C.USAGE_MEDIA).build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
            repeatMode = repository.prefs.getInt("repeat", Player.REPEAT_MODE_ALL)
            shuffleModeEnabled = repository.prefs.getBoolean("shuffle", false)
        }
        radio = RadioQueue(repository.cloud, player, scope)
        session = MediaLibrarySession.Builder(this, player, callback)
            .setSessionActivity(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()
        player.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onAudioInputFormatChanged(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                format: androidx.media3.common.Format, decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) {
                android.util.Log.i("AmiAudio", "input codec=${format.sampleMimeType} sampleRate=${format.sampleRate} channels=${format.channelCount} pcm=${format.pcmEncoding}")
            }
        })
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                cacheRetry?.cancel(); cacheRetryId = null
                repository.cloud.recoveringAudio = null
                updateListening(restart = true)
                repository.cloud.protectAudio(mediaItem?.mediaId)
                player.setWakeMode(if (mediaItem?.mediaId?.startsWith("netease:") == true) C.WAKE_MODE_NETWORK else C.WAKE_MODE_LOCAL)
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val id = player.currentMediaItem?.mediaId ?: return
                if (repository.cloud.audioQuality.value[id]?.cached != true || cacheRetryId == id) {
                    repository.cloud.recoveringAudio = null; return
                }
                cacheRetryId = id
                repository.cloud.recoveringAudio = id
                val index = player.currentMediaItemIndex
                val position = player.currentPosition
                cacheRetry = scope.launch {
                    repository.cloud.discardCachedAudio(id)
                    if (player.currentMediaItem?.mediaId == id && player.currentMediaItemIndex == index && player.playerError != null) {
                        player.seekTo(index, position); player.prepare()
                    }
                    repository.cloud.recoveringAudio = null
                }
            }
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                    val ids = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
                    if (ids != queue) queue = ids
                }
            }
            override fun onEvents(player: Player, events: Player.Events) {
                updatePreloading()
                if (restored) radio.update()
                repository.setPlaybackBusy(player.playbackState == Player.STATE_BUFFERING, player.isLoading)
                updateListening()
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
            val songs = withContext(Dispatchers.IO) { repository.db.playbackSongs(snapshot.queue) + repository.cloud.restore(snapshot.queue) }
            // Commands received while disk restoration ran take precedence over the old queue.
            if (player.mediaItemCount == 0) {
                val (items, location) = snapshot.restore(songs)
                player.repeatMode = snapshot.repeat.coerceIn(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ALL)
                player.shuffleModeEnabled = snapshot.shuffle
                if (items.isNotEmpty()) {
                    player.setMediaItems(items.map { it.mediaItem() }, location.first, location.second)
                    if (!items[location.first].isCloudSong() && !repository.prefs.getBoolean("phoneFollowing",false)) player.prepare()
                }
            }
            restored = true
            repository.scope.launch { repository.cloud.maintainAudioCache() }
            checkpoint()
            updatePositionJob()
            restoration.complete(Unit)
            session?.notifyChildrenChanged(recentRoot, if (player.currentMediaItem == null) 0 else 1, null)
        }
    }
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun updatePreloading() {
        // Media3 preloads one neighboring item only after current loading has priority. Keep
        // just a small in-memory head start; paused players release the extra preload pool.
        val target = if (player.isPlaying && player.mediaItemCount > 1 && player.repeatMode != Player.REPEAT_MODE_ONE) 2_000_000L else C.TIME_UNSET
        if (player.preloadConfiguration.targetPreloadDurationUs != target)
            player.preloadConfiguration = ExoPlayer.PreloadConfiguration(target)
    }
    private fun updatePositionJob() {
        if (!restored || !player.isPlaying) { positionJob?.cancel(); positionJob = null; return }
        if (positionJob?.isActive == true) return
        positionJob = scope.launch {
            while (player.isPlaying) {
                delay(10_000)
                if (player.isPlaying) { updateListening(); checkpoint() }
            }
        }
    }
    private fun checkpoint() {
        if (!restored) return
        val position = if (queue.isEmpty() || player.playbackState == Player.STATE_ENDED) 0 else player.currentPosition.coerceAtLeast(0)
        repository.playback.publish(PlaybackSnapshot(queue, player.currentMediaItemIndex.coerceAtLeast(0), position, player.repeatMode, player.shuffleModeEnabled))
    }
    private fun updateListening(restart: Boolean = false) {
        val item = player.currentMediaItem
        if (statsItem !== item) { statsItem = item; statsTrack = item?.listeningIdentity() }
        val elapsed = android.os.SystemClock.elapsedRealtime()
        repository.listeningStats.sample(statsTrack, player.isPlaying, elapsed, System.currentTimeMillis(), ZoneId.systemDefault())
        if (restart || !player.isPlaying || elapsed - statsFlushAt >= 10_000) {
            statsFlushAt = elapsed; repository.listeningStats.flushAsync()
        }
        val id = player.currentMediaItem?.mediaId
        val duration = player.duration.takeIf { it > 0 } ?: player.currentMediaItem?.mediaMetadata?.durationMs ?: 0
        listening.update(id, duration, player.isPlaying, android.os.SystemClock.elapsedRealtime(), restart)?.let { qualified ->
            repository.scope.launch { repository.cloud.recordListen(qualified) }
        }
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session
    override fun onDestroy() {
        repository.listeningStats.sample(null, false, android.os.SystemClock.elapsedRealtime(), System.currentTimeMillis(), ZoneId.systemDefault())
        repository.listeningStats.flushAsync()
        listening.update(null, 0, false, android.os.SystemClock.elapsedRealtime())?.let { id -> repository.scope.launch { repository.cloud.recordListen(id) } }
        repository.cloud.protectAudio(null)
        repository.cloud.recoveringAudio = null
        repository.setPlaybackBusy(false)
        checkpoint()
        restored = false
        restoration.cancel()
        scope.cancel()
        session?.release(); player.release()
        super.onDestroy()
    }
}
