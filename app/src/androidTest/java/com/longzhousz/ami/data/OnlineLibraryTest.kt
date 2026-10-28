package com.longzhousz.ami.data

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longzhousz.ami.core.*
import com.longzhousz.ami.ui.AmiTheme
import com.longzhousz.ami.ui.OnlineLibraryContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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
    private val requests = CopyOnWriteArrayList<Pair<String, JSONObject>>()
    @Before fun prepare() {
        val web = NeteaseTransport(webForm = { it }, open = { address -> Fixture(address) })
        cloud = CloudMusic(context, true, web)
        runBlocking { cloud.disconnect(); cloud.login("MUSIC_U=qa-only-not-a-real-session") }
    }
    @After fun cleanup() {
        runBlocking { cloud.disconnect() }
        File(context.noBackupFilesDir, "netease-queue.json").delete()
    }
    private fun library() = runBlocking(Dispatchers.IO) { cloud.library() }
    private fun visible(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    private fun screenshot(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
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
        compose.setContent { AmiTheme("light") { OnlineLibraryContent(cloud, state, false, null, {
            refreshes++
            state = state.copy(library = state.library.copy(collections = shuffledShelf(state.library.collections, state.library.collections)))
        }, { rows, index -> plays += rows to index }, {}) } }
        compose.onNodeWithTag("online-shelf").assertIsDisplayed()
        compose.onNodeWithContentDescription("刷新网易云").assertDoesNotExist()
        compose.onNodeWithContentDescription("网易云账号").assertDoesNotExist()
        compose.onNodeWithText("歌曲").assertDoesNotExist()
        screenshot("online-library")
        compose.onNodeWithTag("collection-album:200").performClick()
        compose.waitUntil(10000) { visible("专辑曲目 01") }
        compose.onNodeWithText("专辑曲目 01").performClick()
        compose.onNodeWithTag("online-tracks").performScrollToIndex(28)
        compose.waitUntil(10000) { requests.any { it.first == "v3/song/detail" && it.second.optString("c").contains("31") } }
        compose.onNodeWithContentDescription("返回曲库").performClick()
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
    }

    @Test fun favoritesUseTheRedHeartPlaylistAndSearchRemainsAvailable() {
        val state = CloudLibraryState(library(), loaded = true)
        var favorites by mutableStateOf(true)
        val played = mutableListOf<String>()
        compose.setContent { AmiTheme("dark") { key(favorites) {
            OnlineLibraryContent(cloud, state, favorites, null, {}, { rows, index -> played += rows[index].id }, {})
        } } }
        compose.waitUntil(10000) { visible("红心歌曲 201") }
        compose.onNodeWithText("红心歌曲 201").performClick()
        compose.onNodeWithText("红心歌曲 202").assertIsDisplayed()
        compose.onNodeWithText("夜里听海").assertDoesNotExist()
        screenshot("online-favorites")
        compose.runOnIdle { assertEquals(listOf("201"), played); favorites = false }
        compose.onNodeWithTag("online-search").performTextInput("晚风")
        compose.onNodeWithTag("online-search").performImeAction()
        compose.waitUntil(10000) { visible("搜索歌曲 301") }
        compose.onNodeWithText("搜索歌曲 301").performClick()
        compose.runOnIdle { assertEquals(listOf("201", "301"), played) }
        assertEquals("晚风", requests.last { it.first == "cloudsearch/pc" }.second.getString("s"))
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
                "album/sublist" -> JSONObject().put("data", JSONArray().put(album(200, "晚风与回声")).put(album(201, "海边来信")))
                "v1/album/200", "v1/album/201" -> JSONObject().put("songs", JSONArray((1..35).map(::song)))
                "v6/playlist/detail" -> JSONObject().put("playlist", JSONObject().put("trackIds", JSONArray(
                    (if (body.getString("id") == "100") listOf(201, 202) else listOf(105, 101, 104)).map { JSONObject().put("id", it) })))
                "v3/song/detail" -> JSONObject().put("songs", JSONArray(JSONArray(body.getString("c")).let { ids ->
                    (0 until ids.length()).map { ids.getJSONObject(it).getInt("id") }.reversed().map(::song)
                }))
                "cloudsearch/pc" -> JSONObject().put("result", JSONObject().put("songs", JSONArray().put(song(301))).put("songCount", 1))
                "client-audio" -> JSONObject().put("data", JSONArray().put(JSONObject().put("id", 201).put("code", 200).put("time", 180000)
                    .put("url", "https://m7.music.126.net/qa-audio.flac").put("level", "jyeffect").put("type", "flac").put("br", 1800000)))
                else -> error("Unexpected fixture endpoint: $path")
            }
            result.put("code", 200).toString().toByteArray()
        }
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getOutputStream() = output
        override fun getResponseCode(): Int { response; return 200 }
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
