package com.longzhousz.ami.data

import android.graphics.Bitmap
import coil.imageLoader
import androidx.compose.runtime.*
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longzhousz.ami.core.*
import com.longzhousz.ami.ui.AmiTheme
import com.longzhousz.ami.ui.OnlineLibraryContent
import com.longzhousz.ami.ui.PlayerContent
import com.longzhousz.ami.ui.PlayerActions
import com.longzhousz.ami.playback.PlaybackState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import com.longzhousz.ami.playback.RadioQueue
import com.longzhousz.ami.playback.mediaItem
import androidx.media3.exoplayer.ExoPlayer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** First-party HTTP fixtures only: no account, remote audio, or personal library is used. */
@RunWith(AndroidJUnit4::class)
class OnlineLibraryTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var cloud: CloudMusic
    private lateinit var cache: OnlineAudioCache
    private lateinit var tone: ByteArray
    private val cacheName = "qa-online-resolver"
    private val requests = CopyOnWriteArrayList<Pair<String, JSONObject>>()
    @Volatile private var fmGate: java.util.concurrent.CountDownLatch? = null
    private val fmGeneration = java.util.concurrent.atomic.AtomicInteger()
    @Volatile private var lyricStatus = 200
    @Volatile private var clientAudioCode = 200
    @Volatile private var webAudioCode = 200
    @Volatile private var likeStatus = 200
    @Volatile private var likeGate: java.util.concurrent.CountDownLatch? = null
    private val likedIds = CopyOnWriteArrayList(listOf("201", "202"))
    @Before fun prepare() {
        val web = NeteaseTransport(webForm = { it }, open = { address -> Fixture(address) })
        tone = InstrumentationRegistry.getInstrumentation().context.assets.open("qa-96k-24bit.flac").use { it.readBytes() }
        cache = OnlineAudioCache(context, cacheName, reserve = 0, open = { address ->
            object : HttpURLConnection(URL(address)) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getResponseCode() = 200
                override fun getContentLengthLong() = tone.size.toLong()
                override fun getInputStream() = ByteArrayInputStream(tone)
            }
        })
        cloud = CloudMusic(context, true, web, cache)
        runBlocking { cloud.disconnect(); cloud.login("MUSIC_U=qa-only-not-a-real-session") }
    }
    @After fun cleanup() {
        fmGate?.countDown()
        likeGate?.countDown()
        runBlocking { cloud.disconnect() }
        cache.close(); context.deleteDatabase(File(context.noBackupFilesDir, "$cacheName.db").path)
        File(context.cacheDir, cacheName).deleteRecursively()
        File(context.noBackupFilesDir, "netease-queue.json").delete()
    }
    private fun library() = runBlocking(Dispatchers.IO) { cloud.library() }
    private fun visible(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    private fun screenshot(name: String, system: Boolean = false) {
        compose.mainClock.advanceTimeBy(500); compose.waitForIdle()
        val bitmap = if (system) requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            else compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.filesDir, "qa/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun collectionsSeparateSavedAlbumsCreatedPlaylistsAndRedHearts() = runBlocking(Dispatchers.IO) {
        val shelf = cloud.library()
        assertEquals(setOf("album:200", "album:201", "created:200"), shelf.collections.map { it.key }.toSet())
        assertEquals("100", shelf.liked?.id)
        assertFalse(shelf.collections.any { it.kind in setOf("liked", "collected") })
        val album = shelf.collections.first { it.key == "album:200" }
        assertEquals("AMI · 界面验证", album.artist)
        val first = cloud.tracks(album)
        val second = cloud.tracks(album, first.nextOffset)
        assertEquals((1..35).map(Int::toString), (first.tracks + second.tracks).map { it.id })
        assertTrue(first.more); assertFalse(second.more)
        val playlist = shelf.collections.first { it.kind == "created" }
        assertEquals(listOf("105", "101", "104"), cloud.tracks(playlist).tracks.map { it.id })
        assertEquals(listOf("201", "202"), cloud.tracks(shelf.liked!!).tracks.map { it.id })
    }

    @Test fun pullRefreshReordersOnlyTheShelfAndAlbumPaginationStillPlaysInOrder() {
        var state by mutableStateOf(CloudLibraryState(library(), loaded = true))
        var refreshes = 0
        val plays = mutableListOf<Pair<List<CloudTrack>, Int>>()
        compose.setContent { AmiTheme("light") { Surface { OnlineLibraryContent(cloud, state, false, null, {
            refreshes++
            state = state.copy(library = state.library.copy(collections = shuffledShelf(state.library.collections, state.library.collections)), revision = state.revision + 1)
        }, { rows, index -> plays += rows to index }, {}) } } }
        compose.onNodeWithTag("online-shelf").assertIsDisplayed()
        compose.onNodeWithContentDescription("刷新网易云").assertDoesNotExist()
        compose.onNodeWithContentDescription("网易云账号").assertDoesNotExist()
        compose.onNodeWithText("歌曲").assertDoesNotExist()
        screenshot("online-library")
        compose.onNodeWithTag("collection-album:200").performClick()
        compose.onNodeWithTag("online-refresh-indicator").assertDoesNotExist()
        compose.waitUntil(10000) { visible("专辑曲目 01") }
        compose.onNodeWithText("专辑曲目 01").performClick()
        compose.onNodeWithTag("online-tracks").performScrollToIndex(28)
        compose.waitUntil(10000) { requests.any { it.first == "v3/song/detail" && it.second.optString("c").contains("31") } }
        compose.onNodeWithContentDescription("返回曲库").performClick()
        val albumReads = requests.count { it.first.startsWith("v1/album/") || it.first == "v3/song/detail" }
        compose.onNodeWithTag("collection-album:201").performClick()
        compose.waitUntil(10000) { visible("专辑曲目 01") }
        compose.onNodeWithContentDescription("返回曲库").performClick()
        val afterOtherAlbum = requests.count { it.first.startsWith("v1/album/") || it.first == "v3/song/detail" }
        assertTrue(afterOtherAlbum > albumReads)
        compose.onNodeWithTag("collection-album:200").performClick()
        compose.onNodeWithText("专辑曲目 01").assertIsDisplayed()
        compose.onNodeWithTag("online-refresh-indicator").assertDoesNotExist()
        assertEquals("Other albums must not evict this loaded album", afterOtherAlbum,
            requests.count { it.first.startsWith("v1/album/") || it.first == "v3/song/detail" })
        compose.onNodeWithTag("online-tracks").performTouchInput { swipeDown() }
        compose.waitUntil(10000) {
            requests.count { it.first.startsWith("v1/album/") || it.first == "v3/song/detail" } > afterOtherAlbum
        }
        compose.onNodeWithTag("online-refresh-indicator").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回曲库").performClick()
        compose.onNodeWithTag("online-refresh-indicator").assertExists()
        val before = state.library.collections.map { it.key }
        compose.onNodeWithTag("online-shelf").performTouchInput { swipeDown() }
        compose.waitUntil(5000) { refreshes > 0 }
        compose.runOnIdle {
            assertNotEquals(before, state.library.collections.map { it.key })
            assertEquals(before.toSet(), state.library.collections.map { it.key }.toSet())
            assertEquals(1, plays.size)
            assertEquals((1..30).map(Int::toString), plays.single().first.map { it.id })
            assertEquals(0, plays.single().second)
        }
        runBlocking { cloud.disconnect() }
        assertNull("Album metadata must be cleared on account change", cloud.savedPage(false, "album:200"))
    }

    @Test fun favoritesUseTheRedHeartPlaylistAndSearchRemainsAvailable() {
        val state = CloudLibraryState(library(), loaded = true)
        var favorites by mutableStateOf(true)
        val played = mutableListOf<String>()
        compose.setContent { AmiTheme("dark") { Surface { key(favorites) {
            OnlineLibraryContent(cloud, state, favorites, null, {}, { rows, index -> played += rows[index].id }, {})
        } } } }
        compose.waitUntil(10000) { visible("红心歌曲 201") }
        compose.onNodeWithText("红心歌曲 201").performClick()
        compose.onNodeWithText("红心歌曲 202").assertIsDisplayed()
        compose.onNodeWithText("夜里听海").assertDoesNotExist()
        screenshot("online-favorites")
        val before = requests.size
        compose.runOnIdle { assertEquals(listOf("201"), played); favorites = false }
        compose.onNodeWithTag("online-search").assertDoesNotExist()
        compose.runOnIdle { favorites = true }
        compose.onNodeWithText("红心歌曲 201").assertIsDisplayed()
        compose.runOnIdle { assertEquals("Returning to favorites must reuse its page", before, requests.size); favorites = false }
        compose.onNodeWithContentDescription("搜索音乐").performClick()
        compose.onNodeWithTag("online-search").performTextInput("晚风")
        compose.onNodeWithTag("online-search").performImeAction()
        compose.waitUntil(10000) { visible("搜索歌曲 301") }
        compose.onNodeWithText("搜索歌曲 301").performClick()
        compose.runOnIdle { assertEquals(listOf("201", "301"), played) }
        assertEquals("晚风", requests.last { it.first == "cloudsearch/pc" }.second.getString("s"))
    }

    @Test fun immersiveCoverWallUsesThirtyCachedTracksAndDoesNotBreakPaginationOrLikeUpdates() {
        likedIds.clear(); likedIds.addAll((201..235).reversed().map(Int::toString))
        val state = CloudLibraryState(library(), loaded = true)
        val liked = state.library.liked!!
        val folder = File(context.cacheDir, "qa-wall").apply { mkdirs() }
        try {
            val prefix = runBlocking(Dispatchers.IO) { cloud.tracks(liked) }
            val cached = prefix.copy(tracks = prefix.tracks.mapIndexed { index, track ->
                val cover = File(folder, "${track.id}.png")
                val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                paint.color = android.graphics.Color.HSVToColor(floatArrayOf(index * 29f % 360, .6f, .7f)); canvas.drawColor(paint.color)
                paint.color = android.graphics.Color.HSVToColor(floatArrayOf((index * 29f + 60) % 360, .45f, .95f)); canvas.drawCircle(90f, 38f, 65f, paint)
                paint.color = android.graphics.Color.WHITE; paint.alpha = 150; paint.textSize = 50f; canvas.drawText("${index + 1}", 12f, 112f, paint)
                cover.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
                track.copy(cover = cover.path)
            })
            cloud.savePage(true, liked.key, cached)
            runBlocking(Dispatchers.IO) { cloud.refreshLikes() }
            val metadataReads = requests.count { it.first == "v3/song/detail" }
            compose.setContent { AmiTheme("light") { OnlineLibraryContent(cloud, state, true, null, {}, { _, _ -> }, {},
                onFavorite = { runBlocking(Dispatchers.IO) { cloud.toggleLike(it) } }) } }
            compose.onNodeWithContentDescription("最近喜欢的 30 首歌曲封面").assertIsDisplayed()
            compose.onNodeWithTag("favorite-wall-235", useUnmergedTree = true).assertExists()
            compose.onNodeWithTag("favorite-wall-206", useUnmergedTree = true).assertExists()
            compose.onNodeWithTag("favorite-wall-205", useUnmergedTree = true).assertDoesNotExist()
            compose.waitUntil(15000) {
                val keys = context.imageLoader.memoryCache?.keys.orEmpty()
                cached.tracks.take(30).all { track -> keys.any { it.key.contains(track.cover!!) } }
            }
            screenshot("glass-favorites-wall")
            assertEquals("The wall reuses the loaded prefix", metadataReads, requests.count { it.first == "v3/song/detail" })
            compose.onNodeWithContentDescription("红心歌曲 235：取消喜欢").assertDoesNotExist()
            runBlocking(Dispatchers.IO) { cloud.toggleLike(cached.tracks.first()) }
            compose.waitUntil(5000) { "235" !in cloud.likes.value.ids }
            compose.onNodeWithTag("favorite-wall-235", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithTag("favorite-wall-206", useUnmergedTree = true).assertExists()
            compose.onNodeWithTag("online-tracks").performScrollToIndex(28)
            compose.waitUntil(10000) { cloud.savedPage(true, liked.key)?.tracks?.size == 34 }
            compose.onNodeWithTag("online-tracks").performScrollToIndex(34)
            compose.onNodeWithText("红心歌曲 201").assertIsDisplayed()
        } finally { folder.deleteRecursively() }
    }

    @Test fun explicitFavoritesRefreshFetchesAgainAndShelfRefreshMovesTheFirstCard() {
        var state by mutableStateOf(CloudLibraryState(library(), loaded = true))
        var request by mutableIntStateOf(0)
        var favorites by mutableStateOf(true)
        compose.setContent { AmiTheme("light") { Surface { key(favorites) {
            OnlineLibraryContent(cloud, state, favorites, null, {
                state = state.copy(library = state.library.copy(collections = shuffledShelf(state.library.collections, state.library.collections)), revision = state.revision + 1)
            }, { _, _ -> }, {}, refreshRequest = request)
        } } } }
        try {
            compose.waitUntil(10000) { visible("红心歌曲 201") }
        } catch (failure: Throwable) {
            android.util.Log.e("AmiCloudQA", "Favorites initial load: configured=${cloud.configured.value}, requests=${requests.map { it.first }}")
            compose.onRoot(useUnmergedTree = true).printToLog("AmiCloudQA")
            throw failure
        }
        val count = requests.count { it.first == "v6/playlist/detail" }
        compose.runOnIdle { request++ }
        compose.waitUntil(10000) { requests.count { it.first == "v6/playlist/detail" } > count }
        compose.runOnIdle { request = 0; favorites = false }
        val first = state.library.collections.first()
        compose.runOnIdle { request++ }
        compose.waitUntil(5000) { state.revision > 0 }
        compose.runOnIdle { assertNotEquals(first.key, state.library.collections.first().key) }
        val firstBounds = compose.onNodeWithTag("collection-${state.library.collections.first().key}").fetchSemanticsNode().boundsInRoot
        val gridBounds = compose.onNodeWithTag("online-shelf").fetchSemanticsNode().boundsInRoot
        assertTrue("Refresh must anchor the new first row", firstBounds.top - gridBounds.top < 20)
    }

    @Test fun streamRequestsHighQualityWithoutWritingUrlsOrCredentialsToTheQueue() = runBlocking(Dispatchers.IO) {
        val song = cloud.tracks(cloud.library().liked!!).tracks.first()
        cloud.rememberQueue(listOf(song))
        assertEquals("https://m7.music.126.net/qa-audio.flac", cloud.resolve(song.song().uri))
        val body = requests.single { it.first == "client-audio" }.second
        assertEquals("jyeffect", body.getString("level"))
        assertEquals("flac", body.getString("encodeType"))
        val persisted = File(context.noBackupFilesDir, "netease-queue.json").readText()
        assertFalse(persisted.contains("qa-audio")); assertFalse(persisted.contains("qa-only-not-a-real-session"))
        assertFalse(File(context.noBackupFilesDir, "netease-account.enc").readBytes().toString(Charsets.ISO_8859_1).contains("qa-only-not-a-real-session"))
    }

    @Test fun heartsWriteTheFirstPartyAccountAndAnotherClientReadsTheSameResult() = runBlocking(Dispatchers.IO) {
        val shelf = cloud.library(); cloud.refreshLikes()
        val before = cloud.tracks(shelf.liked!!)
        cloud.savePage(true, shelf.liked!!.key, before)
        val track = cloud.search("晚风").tracks.single()
        cloud.rememberQueue(listOf(track)); val queue = cloud.catalog.value
        cloud.toggleLike(track.song())
        assertEquals(setOf("201", "202", "301"), cloud.likes.value.ids)
        val write = requests.single { it.first == "radio/like" }.second
        assertEquals("301", write.getString("trackId")); assertTrue(write.getBoolean("like"))
        assertEquals("itembased", write.getString("alg")); assertEquals("3", write.getString("time"))
        assertEquals(listOf("301", "201", "202"), cloud.savedPage(true, shelf.liked!!.key)!!.tracks.map { it.id })
        assertEquals(3, cloud.savedPage(true, shelf.liked!!.key)!!.nextOffset)
        assertSame("Heart changes must not rebuild the playing catalog", queue, cloud.catalog.value)
        val second = CloudMusic(context, true, NeteaseTransport(webForm = { it }, open = { Fixture(it) }), cache)
        second.refreshLikes()
        assertTrue("A fresh client must read the server value", "301" in second.likes.value.ids)
        second.toggleLike(track)
        assertFalse("301" in likedIds)
        cloud.refreshLikes(force = true)
        assertFalse("301" in cloud.likes.value.ids)
        assertEquals(listOf(true, false), requests.filter { it.first == "radio/like" }.map { it.second.getBoolean("like") })
        val revision = cloud.likesRevision.value
        val saved = cloud.savedPage(true, shelf.liked!!.key)
        for (status in listOf(403, 401)) {
            likeStatus = status
            val failure = runCatching { cloud.toggleLike(track) }.exceptionOrNull()
            assertTrue(if (status == 401) failure is NeteaseLoginExpired else failure is NeteaseRequestRejected)
            assertFalse("301" in cloud.likes.value.ids); assertTrue(cloud.likes.value.pending.isEmpty())
            assertSame(saved, cloud.savedPage(true, shelf.liked!!.key)); assertEquals(revision, cloud.likesRevision.value)
        }
    }

    @Test fun searchHeartsUpdateTheCleanFavoritesListWithoutRefetchingOrStartingPlayback() {
        val shelf = library()
        runBlocking { cloud.refreshLikes(); cloud.rememberQueue(cloud.tracks(shelf.liked!!).tracks) }
        val queue = cloud.catalog.value
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var favorites by mutableStateOf(false); val played = mutableListOf<String>()
        compose.setContent { AmiTheme("light") { Surface { key(favorites) {
            OnlineLibraryContent(cloud, CloudLibraryState(shelf, loaded = true), favorites, null, {}, { _, _ -> played += "play" }, {},
                onFavorite = { track -> scope.launch { cloud.toggleLike(track) } })
        } } } }
        try {
            compose.onNodeWithContentDescription("搜索音乐").performClick()
            compose.onNodeWithTag("online-search").performTextInput("晚风")
            compose.onNodeWithTag("online-search").performImeAction()
            compose.waitUntil(10000) { visible("搜索歌曲 301") }
            likeGate = java.util.concurrent.CountDownLatch(1)
            compose.onNodeWithContentDescription("搜索歌曲 301：加入喜欢").performClick()
            compose.waitUntil(10000) { "301" in cloud.likes.value.pending }
            compose.onNodeWithContentDescription("搜索歌曲 301：取消喜欢").assertIsNotEnabled()
            likeGate!!.countDown(); likeGate = null
            compose.waitUntil(10000) { cloud.likes.value.pending.isEmpty() }
            screenshot("online-heart-search")
            compose.runOnIdle { favorites = true }
            compose.waitUntil(10000) { visible("搜索歌曲 301") }
            val reads = requests.count { it.first in setOf("v6/playlist/detail", "v3/song/detail") }
            compose.onNodeWithContentDescription("搜索歌曲 301：取消喜欢").assertDoesNotExist()
            runBlocking(Dispatchers.IO) { cloud.toggleLike(cloud.savedPage(true, shelf.liked!!.key)!!.tracks.first { it.id == "301" }) }
            compose.waitUntil(10000) { !visible("搜索歌曲 301") && cloud.likes.value.pending.isEmpty() }
            compose.runOnIdle {
                assertTrue(played.isEmpty()); assertSame(queue, cloud.catalog.value)
                assertEquals(reads, requests.count { it.first in setOf("v6/playlist/detail", "v3/song/detail") })
            }
            screenshot("online-heart-favorites")
        } finally { likeGate?.countDown(); scope.cancel() }
    }

    @Test fun theOnlinePlayerHasTheSameHeartOnBothCoverAndLyricsPages() {
        val track = runBlocking { cloud.refreshLikes(); cloud.tracks(library().liked!!).tracks.first().also { cloud.rememberQueue(listOf(it)) } }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        compose.setContent {
            val likes by cloud.likes.collectAsStateWithLifecycle()
            AmiTheme("dark") { PlayerContent(track.song().copy(favorite = track.originalId in likes.ids), PlaybackState(), null, false,
                PlayerActions(favorite = { scope.launch { cloud.toggleLike(track.song()) } }, canFavorite = track.originalId !in likes.pending)) }
        }
        try {
            compose.onNodeWithContentDescription("取消喜欢").performClick()
            compose.waitUntil(10000) { track.originalId !in cloud.likes.value.ids && cloud.likes.value.pending.isEmpty() }
            compose.onNodeWithContentDescription("加入喜欢").performClick()
            compose.waitUntil(10000) { track.originalId in cloud.likes.value.ids && cloud.likes.value.pending.isEmpty() }
            screenshot("online-heart-player")
            compose.onNodeWithContentDescription("切换到歌词").performClick()
            compose.onNodeWithContentDescription("取消喜欢").assertIsDisplayed()
            screenshot("online-heart-lyrics")
        } finally { scope.cancel() }
    }

    @Test fun clientSessionRefusalUsesTheSameSignedInWebSessionAndReportsRealExpiry() = runBlocking(Dispatchers.IO) {
        val track = cloud.tracks(cloud.library().liked!!).tracks.first()
        cloud.rememberQueue(listOf(track))
        for (code in listOf(301, 403)) {
            clientAudioCode = code; requests.clear()
            assertEquals("https://m7.music.126.net/qa-audio.flac", cloud.resolve(track.song().uri))
            assertEquals("Hi-Res", cloud.audioQuality.value[track.song().id]?.label)
            assertEquals(listOf("client-audio", "song/enhance/player/url/v1"), requests.map { it.first })
            assertEquals("jyeffect", requests.last().second.getString("level"))
        }
        requests.clear(); webAudioCode = 301
        assertTrue(runCatching { cloud.resolve(track.song().uri) }.exceptionOrNull() is NeteaseLoginExpired)
        assertEquals(listOf("client-audio", "song/enhance/player/url/v1"), requests.map { it.first })
    }

    @Test fun cachedAudioUsesNoStreamApiAndMissingAudioFallsBackWithTheSameLogicalSong() = runBlocking(Dispatchers.IO) {
        val track = cloud.tracks(cloud.library().liked!!).tracks.first().copy(duration = 6000)
        cloud.rememberQueue(listOf(track))
        val logical = cloud.catalog.value["netease:${track.id}"]!!
        val account = cache.scope()!!; val now = System.currentTimeMillis()
        repeat(3) { cache.record(account, track.id, 6000, now + it) }
        cache.download(account, OnlineAudioCache.Candidate(track.id, 6000),
            NeteaseAudio("https://m7.music.126.net/qa.flac", "hires", "flac", 2400000, tone.size.toLong()), now) { true }
        requests.clear()
        val local = cloud.resolve(logical.uri)
        assertTrue(local.startsWith("file:"))
        assertTrue(requests.isEmpty())
        assertEquals("Hi-Res", cloud.audioQuality.value[logical.id]?.label)
        assertEquals(logical, cloud.catalog.value[logical.id])
        // An already-open online source must stay online when this cache appears during playback.
        assertEquals("https://m7.music.126.net/qa-audio.flac", cloud.resolve(logical.uri, allowCache = false))
        assertEquals(local, cloud.resolve(logical.uri))
        File(android.net.Uri.parse(local).path!!).delete()
        assertEquals("https://m7.music.126.net/qa-audio.flac", cloud.resolve(logical.uri))
        assertEquals(2, requests.count { it.first == "client-audio" })
        assertFalse(cloud.audioQuality.value[logical.id]!!.cached)
        assertEquals("ami-cloud://song/${track.id}", cloud.catalog.value[logical.id]!!.uri)
    }

    @Test fun yieldingCacheWorkKeepsCandidatesReadyForTheNextIdleWindow() = runBlocking(Dispatchers.IO) {
        val account = cache.scope()!!
        val now = System.currentTimeMillis()
        repeat(3) { cache.record(account, "201", 6000, now + it) }
        requests.clear()
        cloud.fillAudioCache(account) { false }
        assertTrue("Busy UI/playback must not request background audio", requests.isEmpty())
        assertEquals(listOf("201"), cache.candidates(account, now + 10).map { it.id })
        var idle = true
        cloud.fillAudioCache(account) { idle.also { idle = false } }
        assertTrue(requests.isEmpty())
        assertEquals("Deferral must not cause a one-day failure backoff", "201", cache.candidates(account, now + 10).single().id)
        cloud.fillAudioCache(account)
        assertNotNull(cache.get(account, "201", System.currentTimeMillis()))
        assertEquals(1, requests.count { it.first == "client-audio" })
    }

    @Test fun onlineNeighborLyricsAreReusedWithoutFetchingArtworkOrGeneratingTranslation() = runBlocking(Dispatchers.IO) {
        val tracks = cloud.tracks(cloud.library().liked!!).tracks
        requests.clear()
        val neighbor = tracks[1].song()
        assertTrue(cloud.lyrics(neighbor).original.contains("202"))
        assertEquals(cloud.lyrics(neighbor), cloud.lyrics(neighbor))
        assertEquals(listOf("song/lyric/v1"), requests.map { it.first })
        cloud.trim()
        cloud.lyrics(neighbor)
        assertEquals(2, requests.size)
    }

    @Test fun normalBufferRefillsDelayNewWorkWithoutDiscardingAnActiveCopy() {
        val repository = (context.applicationContext as com.longzhousz.ami.AmiApplication).repository
        repository.setUiActive(true)
        try {
            repository.setPlaybackBusy(false, loading = true)
            repository.setUiActive(false)
            assertFalse(repository.canMaintainCaches)
            assertTrue("A normal top-up must not restart a large cached download", repository.canDownloadCache)
            repository.setPlaybackBusy(true, loading = true)
            assertFalse("An actual playback stall must take priority", repository.canDownloadCache)
            repository.setUiActive(true)
            repository.setPlaybackBusy(false)
            assertFalse("Opening the UI must stop optional copies", repository.canDownloadCache)
        } finally { repository.setPlaybackBusy(false); repository.setUiActive(false) }
    }

    @Test fun discoveryIsExplicitAndRadioRejectsLateResultsAfterAnotherQueueIsChosen() = runBlocking(Dispatchers.IO) {
        assertFalse(requests.any { it.first in setOf("v1/radio/get", "v3/discovery/recommend/songs") })
        val fm = cloud.discovery("fm")
        assertEquals(listOf("401", "402"), fm.map { it.id })
        assertEquals(listOf("501", "502"), cloud.discovery("daily").map { it.id })
        val token = "12345678-1234-1234-1234-123456789abc"
        cloud.rememberQueue(fm, token)
        val extra = fm.first().copy(id = "403", originalId = "403")
        val ids = fm.map { it.song().id }
        val added = cloud.appendRadio(token, ids, fm + extra + extra)
        assertEquals(listOf("netease:403"), added.map { it.id })
        assertEquals(ids + "netease:403", cloud.restore(ids + "netease:403").map { it.id })
        val restored = CloudMusic(context, true, NeteaseTransport(webForm = { it }, open = { Fixture(it) }), cache)
        assertEquals(ids + "netease:403", restored.restore(ids + "netease:403").map { it.id })
        assertEquals(token, restored.radio.value)
        cloud.rememberQueue(cloud.discovery("daily"))
        assertNull(cloud.radio.value)
        assertTrue(cloud.appendRadio(token, ids, listOf(extra)).isEmpty())
        assertEquals(setOf("netease:501", "netease:502"), cloud.catalog.value.keys)
        val oversized = List(1000) { dailyIndex -> extra.copy(id = (10000 + dailyIndex).toString(), originalId = (10000 + dailyIndex).toString(),
            cover = "https://p1.music.126.net/" + "x".repeat(7900)) }
        assertTrue(runCatching { cloud.rememberQueue(oversized) }.isFailure)
        assertEquals(setOf("netease:501", "netease:502"), cloud.catalog.value.keys)
    }

    @Test fun onlineWordTimingPreservesTheOriginalLineAndOrdinaryLyricsCache() = runBlocking(Dispatchers.IO) {
        val song = cloud.discovery("daily").first().song()
        val document = cloud.lyrics(song)
        assertEquals("歌词 501", document.lines.single().text)
        assertEquals(2, document.lines.single().words.size)
        assertEquals(1000, document.lines.single().words.last().start)
        assertSame(document, cloud.lyrics(song))
        assertEquals(1, requests.count { it.first == "song/lyric/v1" })
        assertFalse(requests.any { it.first == "client-audio" })
    }

    @Test fun nativeFmRefillsOnlyNearTheEndAndCancelledResultsCannotChangeANewQueue() = runBlocking {
        val daily = cloud.discovery("daily")
        val seed = daily + daily.last().copy(id = "503", originalId = "503")
        val token = "12345678-1234-1234-1234-123456789abc"
        cloud.rememberQueue(seed, token)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val player = withContext(Dispatchers.Main) { ExoPlayer.Builder(context).build() }
        val radio = RadioQueue(cloud, player, scope)
        try {
            withContext(Dispatchers.Main) {
                player.setMediaItems(seed.map { it.song().mediaItem() }, 0, 0)
                player.playWhenReady = true; radio.update()
            }
            assertFalse(requests.any { it.first == "v1/radio/get" })
            withContext(Dispatchers.Main) { player.seekTo(1, 0); radio.update() }
            withTimeout(10000) { while (withContext(Dispatchers.Main) { player.mediaItemCount } < 5) delay(20) }
            withContext(Dispatchers.Main) {
                assertEquals("netease:502", player.currentMediaItem!!.mediaId)
                assertEquals(1, player.currentMediaItemIndex)
                radio.update()
            }
            assertEquals(1, requests.count { it.first == "v1/radio/get" })
            withContext(Dispatchers.Main) { player.seekTo(3, 0); radio.update() }
            withTimeout(10000) { while (withContext(Dispatchers.Main) { player.mediaItemCount } < 7) delay(20) }
            withContext(Dispatchers.Main) { assertEquals("netease:401", player.currentMediaItem!!.mediaId) }
            assertEquals(2, requests.count { it.first == "v1/radio/get" })
            val gate = java.util.concurrent.CountDownLatch(1).also { fmGate = it }
            cloud.rememberQueue(seed, "22345678-1234-1234-1234-123456789abc")
            withContext(Dispatchers.Main) { player.setMediaItems(seed.map { it.song().mediaItem() }, 1, 0); radio.update() }
            withTimeout(10000) { while (requests.count { it.first == "v1/radio/get" } < 3) delay(20) }
            cloud.rememberQueue(daily)
            withContext(Dispatchers.Main) { player.setMediaItems(daily.map { it.song().mediaItem() }); radio.update() }
            gate.countDown(); fmGate = null
            withTimeout(5000) { scope.coroutineContext[Job]!!.children.toList().forEach { it.join() } }
            withContext(Dispatchers.Main) {
                assertEquals(2, player.mediaItemCount)
                assertEquals("netease:501", player.getMediaItemAt(0).mediaId)
            }
        } finally {
            fmGate?.countDown(); fmGate = null; scope.cancel()
            withContext(Dispatchers.Main) { player.release() }
        }
    }

    @Test fun wordEndpointFailureKeepsOrdinaryLyricsButDoesNotHideExpiredLogin() = runBlocking(Dispatchers.IO) {
        val songs = cloud.discovery("daily")
        lyricStatus = 401
        assertTrue(runCatching { cloud.lyrics(songs.first().song()) }.exceptionOrNull() is NeteaseLoginExpired)
        assertFalse(requests.any { it.first == "song/lyric" })
        lyricStatus = 404
        val document = cloud.lyrics(songs.last().song())
        assertEquals("歌词 502", document.lines.single().text)
        assertTrue(document.lines.single().words.isEmpty())
        assertEquals(1, requests.count { it.first == "song/lyric" })
    }

    @Test fun discoveryMenuHasTwoLightweightChoicesAndDoesNotFetchBeforeSelection() {
        var chosen = ""
        val state = CloudLibraryState(library(), loaded = true)
        compose.setContent { AmiTheme("light") { Surface {
            OnlineLibraryContent(cloud, state, false, null, {}, { _, _ -> }, {}, onDiscover = { chosen = it })
        } } }
        compose.onNodeWithContentDescription("发现音乐").performClick()
        compose.onNodeWithText("私人 FM").assertIsDisplayed()
        screenshot("online-discovery", system = true)
        compose.onNodeWithText("每日推荐").performClick()
        compose.runOnIdle { assertEquals("daily", chosen) }
        assertFalse(requests.any { it.first in setOf("v1/radio/get", "v3/discovery/recommend/songs") })
    }

    @Test fun albumSharedCoverHasIntermediateFramesOnEveryVisit() {
        val image = File(context.cacheDir, "qa-album-motion.png")
        Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFE91E63.toInt()) }.let { bitmap ->
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        val output = File(context.filesDir, "qa/performance").apply { mkdirs() }
        val frames = JSONArray()
        fun shell(command: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).use { it.readBytes() }
        fun frame(label: String): android.graphics.Rect {
            compose.waitForIdle()
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            var left = bitmap.width; var top = bitmap.height; var right = -1; var bottom = -1
            for (y in 0 until bitmap.height step 2) for (x in 0 until bitmap.width step 2) {
                val pixel = bitmap.getPixel(x, y)
                if (android.graphics.Color.red(pixel) > 220 && android.graphics.Color.green(pixel) < 50 && android.graphics.Color.blue(pixel) in 75..120) {
                    left = minOf(left, x); top = minOf(top, y); right = maxOf(right, x); bottom = maxOf(bottom, y)
                }
            }
            File(output, "album-$label.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            check(right > left && bottom > top) { "The shared cover disappeared at $label" }
            return android.graphics.Rect(left, top, right + 1, bottom + 1)
        }
        try {
            val cards = (1..60).map { CloudPlaylist((1000 + it).toString(), "唱片 $it", if (it == 21) image.path else null, 35, "album", "AMI QA") }
            compose.setContent { AmiTheme("light") { Surface {
                OnlineLibraryContent(cloud, CloudLibraryState(CloudLibrary(cards), loaded = true), false, null, {}, { _, _ -> }, {})
            } } }
            compose.onNodeWithTag("online-shelf").performScrollToIndex(20)
            compose.waitUntil(10000) { context.imageLoader.memoryCache?.keys?.any { it.key.contains(image.path) } == true }
            shell("dumpsys gfxinfo ${context.packageName} reset")
            repeat(2) { visit ->
                val start = frame("$visit-start")
                compose.mainClock.autoAdvance = false
                compose.onNodeWithTag("collection-album:1021").performClick()
                compose.mainClock.advanceTimeBy(80)
                val first = frame("$visit-80ms")
                compose.mainClock.advanceTimeBy(80)
                val second = frame("$visit-160ms")
                compose.mainClock.advanceTimeBy(1500)
                val end = frame("$visit-end")
                frames.put(JSONObject().put("visit", visit).put("start", start.toShortString()).put("80ms", first.toShortString())
                    .put("160ms", second.toShortString()).put("end", end.toShortString()).put("phoneFrameBenchmark", false))
                File(output, "album-transition.json").writeText(frames.toString(2))
                assertTrue("Cover must grow through intermediate widths: $start -> $first -> $end", first.width() > start.width() + 4 && first.width() < end.width() - 4)
                assertTrue("Cover must continue growing smoothly: $first -> $second -> $end", second.width() > first.width() && second.width() <= end.width() + 2)
                compose.mainClock.autoAdvance = true
                compose.onNodeWithContentDescription("返回曲库").performClick()
                compose.onNodeWithTag("collection-album:1021").assertIsDisplayed()
            }
        } finally {
            compose.mainClock.autoAdvance = true; image.delete()
            File(output, "album-gfxinfo.txt").writeBytes(shell("dumpsys gfxinfo ${context.packageName} framestats"))
        }
    }

    @Test fun albumCoverExpandsAndReturnsToTheSameShelfPositionDuringReversal() {
        val cards = (1..60).map { CloudPlaylist((1000 + it).toString(), "唱片 $it", null, 35, "album", "AMI QA") }
        compose.setContent { AmiTheme("light") { Surface {
            OnlineLibraryContent(cloud, CloudLibraryState(CloudLibrary(cards), loaded = true), false, null, {}, { _, _ -> }, {})
        } } }
        compose.onNodeWithTag("online-shelf").performScrollToIndex(20)
        val card = compose.onNodeWithTag("collection-album:1021")
        val before = card.fetchSemanticsNode().boundsInRoot
        card.performClick()
        compose.onNodeWithTag("collection-cover").assertIsDisplayed()
        val enlarged = compose.onNodeWithTag("collection-cover").fetchSemanticsNode().boundsInRoot
        assertTrue(enlarged.width > before.width + 10)
        screenshot("shelf-album-expanded")
        compose.onNodeWithTag("online-tracks").performScrollToIndex(20)
        compose.onNodeWithTag("collection-cover").assertIsNotDisplayed()
        compose.onNodeWithContentDescription("返回曲库").performClick()
        val returned = compose.onNodeWithTag("collection-album:1021").fetchSemanticsNode().boundsInRoot
        assertEquals(before.top, returned.top, 2f)
        card.performClick()
        compose.onNodeWithTag("collection-cover").assertIsDisplayed()
        val reopened = compose.onNodeWithTag("collection-cover").fetchSemanticsNode().boundsInRoot
        assertEquals("Reopening must start from the hero, not the old scroll position", enlarged.top, reopened.top, 2f)
        assertEquals(enlarged.height, reopened.height, 2f)
        screenshot("album-reopened-top")
        compose.onNodeWithContentDescription("返回曲库").performClick()
        compose.onNodeWithTag("collection-album:1021").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("collection-album:1021").performClick()
        compose.mainClock.advanceTimeBy(96)
        compose.onNodeWithContentDescription("返回曲库").performClick()
        compose.mainClock.advanceTimeBy(1500)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithTag("collection-album:1021").assertIsDisplayed()
        compose.onAllNodesWithTag("online-shelf").assertCountEquals(1)
    }

    private inner class Fixture(address: String) : HttpURLConnection(URL(address)) {
        private val output = ByteArrayOutputStream()
        private val response by lazy {
            val client = url.path.startsWith("/eapi/")
            val path = if (client) "client-audio" else url.path.removePrefix("/weapi/")
            val text = output.toString("UTF-8")
            val body = if (client) {
                val hex = text.substringAfter("params=")
                val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                val decoded = Cipher.getInstance("AES/ECB/PKCS5Padding").run {
                    init(Cipher.DECRYPT_MODE, SecretKeySpec("e82ckenh8dichen8".toByteArray(), "AES")); doFinal(bytes).toString(Charsets.UTF_8)
                }
                JSONObject(decoded.split("-36cd479b6b5-")[1])
            } else JSONObject(text)
            requests += path to body
            val result = when (path) {
                "w/nuser/account/get" -> JSONObject().put("profile", JSONObject().put("userId", 42).put("nickname", "AMI QA"))
                "user/playlist" -> JSONObject().put("playlist", JSONArray()
                    .put(playlist(100, "我喜欢的音乐", 5, 42)).put(playlist(200, "夜里听海", 0, 42)).put(playlist(300, "别人创建的歌单", 0, 99)))
                "song/like/get" -> {
                    assertEquals("music.163.com", url.host)
                    assertEquals("MUSIC_U=qa-only-not-a-real-session", getRequestProperty("Cookie"))
                    assertEquals("42", body.getString("uid"))
                    JSONObject().put("ids", JSONArray(likedIds.map(String::toLong)))
                }
                "radio/like" -> {
                    assertEquals("music.163.com", url.host)
                    assertEquals("MUSIC_U=qa-only-not-a-real-session", getRequestProperty("Cookie"))
                    likeGate?.await()
                    if (likeStatus == 200) {
                        val id = body.getString("trackId"); likedIds.remove(id)
                        if (body.getBoolean("like")) likedIds.add(0, id)
                    }
                    JSONObject().put("playlistId", 100)
                }
                "album/sublist" -> JSONObject().put("data", JSONArray().put(album(200, "晚风与回声")).put(album(201, "海边来信")))
                "v1/album/200", "v1/album/201", "v1/album/1021" -> JSONObject().put("songs", JSONArray((1..35).map(::song)))
                "v6/playlist/detail" -> JSONObject().put("playlist", JSONObject().put("trackIds", JSONArray(
                    (if (body.getString("id") == "100") likedIds else listOf("105", "101", "104")).map { JSONObject().put("id", it) })))
                "v3/song/detail" -> JSONObject().put("songs", JSONArray(JSONArray(body.getString("c")).let { ids ->
                    (0 until ids.length()).map { ids.getJSONObject(it).getInt("id") }.reversed().map(::song)
                }))
                "cloudsearch/pc" -> JSONObject().put("result", JSONObject().put("songs", JSONArray().put(song(301))).put("songCount", 1))
                "client-audio", "song/enhance/player/url/v1" -> {
                    assertEquals("MUSIC_U=qa-only-not-a-real-session", getRequestProperty("Cookie"))
                    JSONObject().put("data", JSONArray().put(JSONObject().put("id", 201).put("code", 200).put("time", 180000)
                        .put("url", "https://m7.music.126.net/qa-audio.flac").put("level", if (client) "jyeffect" else "hires")
                        .put("type", "flac").put("br", 1800000).put("size", tone.size)))
                }
                "song/lyric" -> JSONObject().put("lrc", JSONObject().put("lyric", "[00:00]歌词 ${body.getString("id")}"))
                "song/lyric/v1" -> JSONObject().put("lrc", JSONObject().put("lyric", "[00:00]歌词 ${body.getString("id")}"))
                    .put("yrc", JSONObject().put("lyric", "[0,2000](0,1000,0)歌词 (1000,1000,0)${body.getString("id")}"))
                "v1/radio/get" -> {
                    fmGate?.await(); val first = 401 + (fmGeneration.getAndIncrement() * 2)
                    JSONObject().put("data", JSONArray().put(song(first)).put(song(first + 1)).put(song(first + 1)))
                }
                "v3/discovery/recommend/songs" -> JSONObject().put("data", JSONObject().put("dailySongs", JSONArray().put(song(501)).put(song(502))))
                else -> error("Unexpected fixture endpoint: $path")
            }
            result.put("code", when (path) {
                "client-audio" -> clientAudioCode
                "song/enhance/player/url/v1" -> webAudioCode
                else -> 200
            }).toString().toByteArray()
        }
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getOutputStream() = output
        override fun getResponseCode(): Int { response; return when {
            url.path.endsWith("song/lyric/v1") -> lyricStatus
            url.path.endsWith("radio/like") -> likeStatus
            else -> 200
        } }
        override fun getInputStream() = ByteArrayInputStream(response)
    }
    private fun playlist(id: Int, title: String, special: Int, owner: Int) = JSONObject().put("id", id).put("name", title)
        .put("trackCount", 3).put("specialType", special).put("creator", JSONObject().put("userId", owner))
    private fun album(id: Int, title: String) = JSONObject().put("id", id).put("name", title).put("size", 35)
        .put("artists", JSONArray().put(JSONObject().put("name", "AMI · 界面验证")))
    private fun song(id: Int) = JSONObject().put("id", id).put("name", when {
        id >= 300 -> "搜索歌曲 $id"
        id >= 200 -> "红心歌曲 $id"
        id >= 100 -> "歌单歌曲 $id"
        else -> "专辑曲目 ${id.toString().padStart(2, '0')}"
    }).put("dt", 180000).put("ar", JSONArray().put(JSONObject().put("name", "AMI · 界面验证")))
        .put("al", JSONObject().put("name", "晚风与回声"))
}
