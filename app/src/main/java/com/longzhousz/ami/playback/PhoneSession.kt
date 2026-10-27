package com.longzhousz.ami.playback

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState as AndroidPlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import com.longzhousz.ami.core.Song
import com.longzhousz.ami.core.digest
import com.longzhousz.ami.core.phonePosition
import com.longzhousz.ami.core.phoneSongId
import kotlinx.coroutines.flow.MutableStateFlow

/** Grants access to public media sessions. No notification body is read or persisted. */
class PhoneMediaAccess : NotificationListenerService() {
    override fun onListenerConnected() { connected.value = true }
    override fun onListenerDisconnected() { connected.value = false }
    companion object { val connected = MutableStateFlow(false) }
}

data class PhoneState(val song: Song? = null, val playback: PlaybackState = PlaybackState(), val actions: Long = 0, val artwork: Bitmap? = null) {
    fun supports(action: Long) = actions and action != 0L
}

/** The NetEase app remains the audio owner; AMI never opens or caches its audio. */
class PhoneSession(private val context: Context, private val targetPackage: String = NETEASE) {
    companion object {
        const val NETEASE = "com.netease.cloudmusic"
        fun permitted(context: Context) = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
    }
    val state = MutableStateFlow(PhoneState())
    var artwork: Bitmap? = null; private set
    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(context, PhoneMediaAccess::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null
    private var snapshot: AndroidPlaybackState? = null
    private var metadata: MediaMetadata? = null
    private var callback: MediaController.Callback? = null
    private var listening = false
    private val listener = MediaSessionManager.OnActiveSessionsChangedListener { select(it.orEmpty()) }
    fun start(stopLocal: Boolean = false) {
        if (listening) return
        try {
            val sessions=manager.getActiveSessions(component)
            if (stopLocal && targetPackage!=context.packageName) sessions.filter { it.packageName==context.packageName }.forEach {
                it.transportControls.pause();it.transportControls.stop()
            }
            manager.addOnActiveSessionsChangedListener(listener, component, handler)
            listening = true; select(sessions)
        } catch (_: SecurityException) { state.value = PhoneState() }
    }
    fun refresh() {
        if (!listening) return
        try { select(manager.getActiveSessions(component)) }
        catch (_: SecurityException) { select(emptyList()) }
    }
    private fun select(sessions: List<MediaController>) {
        val available = sessions.filter { it.packageName == targetPackage }
        val chosen = available.firstOrNull { it.playbackState?.state == AndroidPlaybackState.STATE_PLAYING } ?: available.firstOrNull()
        if (controller?.sessionToken != chosen?.sessionToken) {
            callback?.let { controller?.unregisterCallback(it) }; controller = chosen
            callback=chosen?.let { bound -> object : MediaController.Callback() {
                override fun onMetadataChanged(value: MediaMetadata?) { if (controller===bound) { metadata=value;publish() } }
                override fun onPlaybackStateChanged(value: AndroidPlaybackState?) { if (controller===bound) { snapshot=value;publish() } }
                override fun onSessionDestroyed() { if (controller===bound) refresh() }
            } }
            callback?.let { chosen?.registerCallback(it,handler) }
        }
        metadata=controller?.metadata;snapshot=controller?.playbackState
        publish()
    }
    private fun publish() {
        val current = controller
        val meta = metadata
        val title = (meta?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE))?.take(200).orEmpty()
        if (current == null || meta == null || title.isBlank()) { artwork = null; state.value = PhoneState(); return }
        val artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)?.take(200).orEmpty()
        val album = meta.getString(MediaMetadata.METADATA_KEY_ALBUM)?.take(200).orEmpty()
        val duration = meta.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0)
        val nativeId = phoneSongId(meta.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)) ?: phoneSongId(meta.getString(MediaMetadata.METADATA_KEY_MEDIA_URI))
        val id = "phone:" + digest("$nativeId|$title|$artist|$album|$duration")
        artwork = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val raw = snapshot
        val playing = raw?.state == AndroidPlaybackState.STATE_PLAYING
        val song = Song(id, "ami-phone://netease/${id.removePrefix("phone:")}", folder = "phone", title = title, artist = artist, album = album,
            duration = duration, format = "网易云 · 随播", neteaseId = nativeId, lyricsAvailable = true, lyricsVersion = id, importedAt = 0)
        state.value = PhoneState(song, PlaybackState(connected = true, id = id, playing = playing, position = position(raw, duration), duration = duration,
            buffering = raw?.state in setOf(AndroidPlaybackState.STATE_BUFFERING, AndroidPlaybackState.STATE_CONNECTING),
            advancing = playing && (raw?.playbackSpeed ?: 0f) > 0, error = if (raw?.state == AndroidPlaybackState.STATE_ERROR) "请在网易云音乐中检查播放状态" else null), raw?.actions ?: 0, artwork)
    }
    private fun position(raw: AndroidPlaybackState?, duration: Long) = raw?.let {
        phonePosition(it.position, it.lastPositionUpdateTime, SystemClock.elapsedRealtime(), it.playbackSpeed, it.state == AndroidPlaybackState.STATE_PLAYING, duration)
    } ?: 0
    // The foreground frame clock interpolates this snapshot; it performs no Binder calls.
    fun currentPosition(expectedId: String?): Long = if (expectedId == state.value.song?.id) position(snapshot, state.value.song?.duration ?: 0) else 0
    fun toggle() {
        val c = controller ?: return
        val playing = state.value.playback.playing
        val supported = if (playing) AndroidPlaybackState.ACTION_PAUSE else AndroidPlaybackState.ACTION_PLAY
        if (state.value.supports(supported) || state.value.supports(AndroidPlaybackState.ACTION_PLAY_PAUSE)) {
            if (playing) c.transportControls.pause() else c.transportControls.play()
        }
    }
    fun seek(position: Long) { if (state.value.supports(AndroidPlaybackState.ACTION_SEEK_TO)) controller?.transportControls?.seekTo(position.coerceIn(0, state.value.song?.duration?.takeIf { it > 0 } ?: Long.MAX_VALUE)) }
    fun next() { if (state.value.supports(AndroidPlaybackState.ACTION_SKIP_TO_NEXT)) controller?.transportControls?.skipToNext() }
    fun previous() { if (state.value.supports(AndroidPlaybackState.ACTION_SKIP_TO_PREVIOUS)) controller?.transportControls?.skipToPrevious() }
    fun stop() {
        if (listening) manager.removeOnActiveSessionsChangedListener(listener)
        listening = false;callback?.let { controller?.unregisterCallback(it) };callback=null;controller=null;artwork=null;snapshot=null;metadata=null
        state.value=state.value.copy(artwork=null)
    }
}
