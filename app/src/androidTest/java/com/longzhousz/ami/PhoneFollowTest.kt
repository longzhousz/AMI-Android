package com.longzhousz.ami

import android.app.Application
import android.content.ComponentName
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.viewModelScope
import com.longzhousz.ami.core.*
import com.longzhousz.ami.data.LibraryDatabase
import com.longzhousz.ami.data.PhoneLyrics
import com.longzhousz.ami.playback.PhoneMediaAccess
import com.longzhousz.ami.playback.PhoneSession
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Synthetic publisher in the test UID; no NetEase account, audio stream, or real phone is used. */
@RunWith(AndroidJUnit4::class)
class PhoneFollowTest {
    @get:Rule val compose=createComposeRule()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val app get()=context.applicationContext as Application
    private lateinit var owner: MediaSession
    private lateinit var session: PhoneSession
    private var model: PhoneMusicViewModel?=null
    private var originallyPermitted=false
    private var originallyFollowing=false
    private val requestedSeek=AtomicLong(-1)
    private val flags=PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO
    private val component get()=ComponentName(context,PhoneMediaAccess::class.java).flattenToString()
    private fun shell(command: String) { InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() } }
    @Before fun prepare() {
        originallyPermitted=PhoneSession.permitted(context)
        originallyFollowing=(app as AmiApplication).repository.prefs.getBoolean("phoneFollowing",false)
        if(!originallyPermitted) shell("cmd notification allow_listener $component")
        compose.waitUntil(15000) { PhoneSession.permitted(context) && PhoneMediaAccess.connected.value }
        owner=MediaSession(context,"AMI phone-follow QA")
        owner.setCallback(object: MediaSession.Callback() {
            override fun onPlay() { playback(PlaybackState.STATE_PLAYING,requestedSeek.get().coerceAtLeast(0)) }
            override fun onPause() { playback(PlaybackState.STATE_PAUSED,12000) }
            override fun onSeekTo(pos: Long) { requestedSeek.set(pos);playback(PlaybackState.STATE_PLAYING,pos) }
            override fun onSkipToNext() { metadata("乙","991122332");playback(PlaybackState.STATE_PLAYING,0) }
            override fun onSkipToPrevious() { metadata("甲","991122331");playback(PlaybackState.STATE_PLAYING,0) }
        },Handler(Looper.getMainLooper()))
        metadata("甲","991122331");playback(PlaybackState.STATE_PLAYING,12000);owner.isActive=true
        session=PhoneSession(context,context.packageName)
    }
    @After fun cleanup() {
        model?.stop();model?.viewModelScope?.cancel()
        if(::session.isInitialized)session.stop()
        if(::owner.isInitialized)owner.release()
        (app as AmiApplication).repository.prefs.edit().putBoolean("phoneFollowing",originallyFollowing).commit()
        if(!originallyPermitted)shell("cmd notification disallow_listener $component")
    }
    private fun metadata(label: String,id: String) {
        val art=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888).apply { eraseColor(0xff657899.toInt()) }
        owner.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,"随播歌曲$label")
            .putString(MediaMetadata.METADATA_KEY_ARTIST,"AMI 测试").putString(MediaMetadata.METADATA_KEY_ALBUM,"只用手机")
            .putString(MediaMetadata.METADATA_KEY_MEDIA_ID,id).putLong(MediaMetadata.METADATA_KEY_DURATION,90000)
            .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART,art).build())
    }
    private fun playback(state: Int,position: Long,actions: Long=flags) { owner.setPlaybackState(PlaybackState.Builder().setActions(actions).setState(state,position,1f,SystemClock.elapsedRealtime()).build()) }
    private fun screenshot(name: String) {
        val bitmap=compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.filesDir,"qa/$name.png").apply {parentFile!!.mkdirs()}.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
    }
    @Test fun aDifferentAppSessionIsIgnoredAndPhoneControlsReachTheSelectedPublisher() {
        val filtered=PhoneSession(context)
        try {
            compose.runOnIdle { filtered.start();session.start() }
            compose.waitUntil(10000) { session.state.value.song!=null }
            assertNull(filtered.state.value.song)
            assertTrue(session.currentPosition(session.state.value.song?.id)>=12000)
            compose.runOnIdle { session.seek(15000) }
            compose.waitUntil(5000) { requestedSeek.get()==15000L }
            compose.runOnIdle { session.toggle() }
            compose.waitUntil(5000) { !session.state.value.playback.playing }
            val paused=session.currentPosition(session.state.value.song?.id)
            SystemClock.sleep(80)
            assertEquals(paused,session.currentPosition(session.state.value.song?.id))
            compose.runOnIdle { session.next() }
            compose.waitUntil(5000) { session.state.value.song?.title=="随播歌曲乙" }
        } finally { compose.runOnIdle { filtered.stop() } }
    }
    @Test fun realMediaSessionCallbacksDriveAmiLyricsArtworkAndSupportedControls() {
        val vm=PhoneMusicViewModel(app,session) { song -> SongLyrics("[00:00]第一句${song.title.last()}\n[00:10]第二句${song.title.last()}\n[00:25]让晚风继续唱",revision=song.lyricsVersion) }.also { model=it }
        compose.runOnIdle { vm.start() }
        compose.setContent { PhonePlayerBody(vm,true,{},{},{},{}) }
        compose.waitUntil(15000) { compose.onAllNodesWithText("第二句甲").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("随播歌曲甲").assertIsDisplayed()
        compose.onNodeWithText("队列").assertDoesNotExist()
        compose.onNodeWithContentDescription("开启随机播放").assertDoesNotExist()
        compose.onNodeWithContentDescription("加入喜欢").assertDoesNotExist()
        screenshot("phone-lyrics")
        compose.onNodeWithContentDescription("展开专辑封面").performClick()
        screenshot("phone-artwork")
        compose.onNodeWithText("歌词").performClick()
        compose.onNodeWithTag("playback-seek").performSemanticsAction(SemanticsActions.SetProgress) { it(15000f) }
        compose.waitUntil(5000) { requestedSeek.get()==15000L }
        compose.onNodeWithContentDescription("下一首").performClick()
        compose.waitUntil(10000) { vm.lyrics.value.document?.original?.contains("第二句乙")==true }
        compose.onNodeWithText("随播歌曲乙").assertIsDisplayed()
        compose.runOnIdle { playback(PlaybackState.STATE_PLAYING,0,PlaybackState.ACTION_PLAY_PAUSE) }
        compose.waitUntil(5000) { !session.state.value.supports(PlaybackState.ACTION_SEEK_TO) }
        compose.onNodeWithTag("playback-seek").assertIsNotEnabled()
    }
    @Test fun lateLyricsCannotReplaceTheNewTrackAndStoppingDetachesTheController() = runBlocking {
        val began=CompletableDeferred<Unit>()
        val vm=PhoneMusicViewModel(app,session) { song ->
            if(song.title.endsWith("甲")) { began.complete(Unit);withContext(NonCancellable) { delay(300) } }
            SongLyrics("[00:00]歌词${song.title.last()}",revision=song.lyricsVersion)
        }.also {model=it}
        compose.runOnIdle { vm.start() }
        withTimeout(10000) { began.await() }
        compose.runOnIdle { metadata("乙","991122332");playback(PlaybackState.STATE_PLAYING,0) }
        compose.waitUntil(10000) { vm.lyrics.value.document?.original=="[00:00]歌词乙" }
        compose.runOnIdle { vm.stop() }
        val before=session.state.value
        compose.runOnIdle { metadata("丙","991122333") }
        SystemClock.sleep(100)
        assertEquals(before,session.state.value)
    }
    @Test fun phoneLyricsReuseLocalOverridesAndRejectDifferentVersions() {
        val name="phone-follow-qa.db"
        val db=LibraryDatabase(context,name)
        try {
            val local=Song("phone-qa-local","file:///unused",folder="qa",title="晚风",artist="AMI",duration=90000,neteaseId="991122331",lyrics="[00:00]自动歌词")
            db.upsert(local);db.setLyrics(local.id,LocalLyrics("[00:00]手动歌词"),"test")
            val remote=local.copy(id="phone:remote",uri="ami-phone://remote")
            assertEquals("[00:00]手动歌词",db.phoneLyrics(remote)?.original)
            assertEquals("[00:00]手动歌词",db.phoneLyrics(remote.copy(neteaseId=null))?.original)
            assertNull(db.phoneLyrics(remote.copy(neteaseId=null,duration=95000)))
            db.cacheLyrics("991122334",LocalLyrics("[00:00]按歌曲 ID 匹配"),"test","qa")
            assertEquals("[00:00]按歌曲 ID 匹配",db.phoneLyrics(remote.copy(neteaseId="991122334"))?.original)
        } finally { db.close();context.deleteDatabase(name) }
    }
    @Test fun publicLookupRejectsTheWrongArtistAndSelectsTheMatchingDuration() = runBlocking {
        val song=Song("phone:api-qa","ami-phone://qa",folder="qa",title="AMI-public-lyric-contract",artist="AMI Test Artist",album="QA",duration=90000)
        val requests=java.util.concurrent.CopyOnWriteArrayList<String>()
        fun row(artist: String,duration: Int,text: String)=org.json.JSONObject().put("trackName",song.title).put("artistName",artist)
            .put("albumName","QA").put("duration",duration).put("syncedLyrics",text)
        val client=PhoneLyrics((app as AmiApplication).repository) { path ->
            requests.add(path)
            val payload=if(path.startsWith("/api/get")) row("Wrong Artist",90,"[00:00]wrong").toString() else org.json.JSONArray()
                .put(row(song.artist,120,"[00:00]wrong version")).put(row(song.artist,90,"[00:00]correct match")).toString()
            object: java.net.HttpURLConnection(java.net.URI("https://lrclib.net$path").toURL()) {
                override fun connect() {}
                override fun usingProxy()=false
                override fun disconnect() {}
                override fun getResponseCode()=200
                override fun getInputStream()=payload.byteInputStream()
            }
        }
        assertEquals("[00:00]correct match",client.load(song).original)
        assertEquals(2,requests.size)
    }
}
