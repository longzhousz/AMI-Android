package com.longzhousz.ami

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.PlaybackState as AndroidPlaybackState
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.longzhousz.ami.playback.PhoneMediaAccess
import com.longzhousz.ami.playback.PhoneSession
import com.longzhousz.ami.ui.AmiTheme
import com.longzhousz.ami.ui.PlayerActions
import com.longzhousz.ami.ui.PlayerContent
import com.longzhousz.ami.ui.rememberPlaybackClock

class PhonePlayerActivity : ComponentActivity() {
    private val model: PhoneMusicViewModel by viewModels()
    private var permitted by mutableStateOf(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState);enableEdgeToEdge()
        setContent {
            val connected by PhoneMediaAccess.connected.collectAsStateWithLifecycle()
            LaunchedEffect(connected) {
                permitted=PhoneSession.permitted(this@PhonePlayerActivity)
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) { if (permitted) model.start() else model.stop() }
            }
            PhonePlayerBody(model,permitted,::allowMedia,::openMusic,::openSettings) { finish() }
        }
    }
    override fun onStart() { super.onStart();permitted=PhoneSession.permitted(this);if(permitted)model.start() }
    override fun onStop() { model.stop();super.onStop() }
    private fun openSettings() { startActivity(Intent(this,MainActivity::class.java).putExtra("page",2)) }
    private fun openMusic() { if (!openPhoneMusic(this)) model.message.value="请先安装网易云音乐 App" }
    private fun allowMedia() {
        val intent=if(Build.VERSION.SDK_INT>=30) Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,ComponentName(this,PhoneMediaAccess::class.java).flattenToString()) else Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        try { startActivity(intent) } catch (_: android.content.ActivityNotFoundException) { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
    }
}

fun openPhoneMusic(context: Context): Boolean = try {
    context.packageManager.getLaunchIntentForPackage(PhoneSession.NETEASE)?.let { context.startActivity(it);true } ?: false
} catch (_: android.content.ActivityNotFoundException) { false }

@Composable internal fun PhoneGate(permitted: Boolean, onAllow: () -> Unit, onMusic: () -> Unit, onSettings: () -> Unit) {
    var help by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().padding(28.dp).testTag("phone-music-gate"),contentAlignment=Alignment.Center) {
        Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(20.dp)) {
            Icon(Icons.Rounded.CloudQueue,null,Modifier.size(56.dp),tint=MaterialTheme.colorScheme.primary)
            Text(if(permitted) "在网易云选歌，在 AMI 看歌词" else "让 AMI 跟随网易云",style=MaterialTheme.typography.headlineSmall)
            Text(if(permitted) "先在手机上的网易云音乐播放一首歌，再回到这里。" else "允许通知使用权后，AMI 就能跟随手机上的网易云播放。只使用音乐会话，不读取或保存其他通知内容。",color=MaterialTheme.colorScheme.onSurfaceVariant)
            Button(if(permitted) onMusic else onAllow,Modifier.testTag("phone-music-connect")) { Text(if(permitted) "打开网易云" else "连接手机上的网易云") }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                if(!permitted) TextButton({help=true}) { Text("无法开启") }
                TextButton(onSettings) { Text("设置") }
            }
        }
    }
    if(help) AlertDialog(onDismissRequest={help=false},title={Text("开启播放连接")},text={Text("在系统的通知使用权页面开启 AMI。若系统提示受限设置，请先到 AMI 的应用信息页面，按系统提示允许受限设置，再回来开启。无需 root，也不需要连接电脑。")},confirmButton={TextButton({help=false}) {Text("知道了")}})
}


@Composable internal fun PhonePlayerBody(model: PhoneMusicViewModel, permitted: Boolean, onAllow: () -> Unit, onMusic: () -> Unit, onSettings: () -> Unit, onClose: () -> Unit) {
    val state by model.session.state.collectAsStateWithLifecycle()
    val lyrics by model.lyrics.collectAsStateWithLifecycle()
    val cover by model.cover.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    val clock=rememberPlaybackClock(state.playback) { model.session.currentPosition(state.song?.id) }
    val snackbar=remember { SnackbarHostState() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it);if(model.message.value==it)model.message.value=null } }
    LaunchedEffect(state.playback.error) { state.playback.error?.let { snackbar.showSnackbar(it) } }
    AmiTheme("system",immersive=permitted && state.song!=null) {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                val song=state.song
                if (!permitted || song==null) PhoneGate(permitted,onAllow,onMusic,onSettings)
                else {
                    val doc=lyrics.document.takeIf { lyrics.songId==song.id }
                    val current=song.copy(cover=cover?.takeIf { it.first==song.id }?.second,
                        lyrics=doc?.original.orEmpty(),lyricTranslation=doc?.translated.orEmpty())
                    fun act(action: () -> Unit) { runCatching(action).onFailure { model.message.value="播放控制暂不可用，请在网易云中重试" } }
                    val canToggle=state.supports(if (state.playback.playing) AndroidPlaybackState.ACTION_PAUSE else AndroidPlaybackState.ACTION_PLAY) || state.supports(AndroidPlaybackState.ACTION_PLAY_PAUSE)
                    PlayerContent(current,state.playback,lyrics.translation.takeIf { lyrics.songId==song.id },lyrics.translating,PlayerActions(
                        close=onClose,queue=onMusic,settings=onSettings,external=true,
                        seek={ target -> if (model.session.state.value.song?.id==song.id) act { model.session.seek(target) } },
                        toggle={ act(model.session::toggle) },next={ act(model.session::next) },previous={ act(model.session::previous) },
                        translate={ model.translate() },retranslate={ model.translate(true) },canToggle=canToggle,
                        canSeek=state.supports(AndroidPlaybackState.ACTION_SEEK_TO),canNext=state.supports(AndroidPlaybackState.ACTION_SKIP_TO_NEXT),
                        canPrevious=state.supports(AndroidPlaybackState.ACTION_SKIP_TO_PREVIOUS)
                    ),lyricPosition={ clock.value },preparedLines=doc?.lines,lyricsReady=doc!=null)
                }
                SnackbarHost(snackbar,Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}
