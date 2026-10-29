package com.longzhousz.ami

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.longzhousz.ami.core.CloudTrack
import com.longzhousz.ami.data.CloudMusic
import com.longzhousz.ami.ui.AmiTheme
import com.longzhousz.ami.ui.CloudMusicContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class CloudMusicTest {
    @get:Rule val networkPermission: org.junit.rules.TestRule = if (android.os.Build.VERSION.SDK_INT >= 37)
        GrantPermissionRule.grant(android.Manifest.permission.ACCESS_LOCAL_NETWORK) else org.junit.rules.TestRule { base, _ -> base }
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var cloud: CloudMusic
    private lateinit var backend: Backend
    @Before fun prepare() {
        cloud = CloudMusic(context, direct = false)
        runBlocking { cloud.disconnect() }
        File(context.noBackupFilesDir, "netease-queue.json").delete()
        backend = Backend()
    }
    @After fun cleanup() {
        backend.close()
        runBlocking { cloud.disconnect() }
        File(context.noBackupFilesDir, "netease-queue.json").delete()
    }
    private fun visible(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    private fun screenshot(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.filesDir, "qa/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun pairingPlaylistsSearchPaginationAndPlaybackSelectionStaySimple() {
        val played = AtomicReference<Pair<List<CloudTrack>, Int>>()
        compose.setContent { AmiTheme("dark") { CloudMusicContent(cloud, { rows, index -> played.set(rows to index) }) } }
        compose.onNodeWithText("连接你的网易云音乐").assertIsDisplayed()
        screenshot("cloud-connect")
        assertTrue(backend.requests.isEmpty())
        compose.onNodeWithTag("cloud-connect").performClick()
        compose.onNodeWithTag("cloud-address").performTextInput("http://8.8.8.8/#" + "A".repeat(43))
        compose.onNodeWithTag("cloud-pair-confirm").performClick()
        compose.waitUntil(5000) { visible("连接地址需使用 HTTPS 或可信局域网地址") }
        assertTrue(backend.requests.isEmpty())
        compose.onNodeWithTag("cloud-address").performTextClearance()
        compose.onNodeWithTag("cloud-address").performTextInput(backend.address)
        compose.onNodeWithTag("cloud-pair-confirm").performClick()
        compose.waitUntil(10000) { visible("晚风收藏") }
        screenshot("cloud-playlists")
        compose.onNodeWithText("晚风收藏").performClick()
        compose.waitUntil(10000) { visible("把晚风留在唱片里") }
        compose.onNodeWithContentDescription("在网易云音乐中收听").assertIsDisplayed()
        screenshot("cloud-tracks")
        compose.onNodeWithText("把晚风留在唱片里").performClick()
        compose.runOnIdle { assertEquals("把晚风留在唱片里", played.get().first[played.get().second].title) }
        compose.onNodeWithTag("cloud-search").performTextInput("晚风")
        compose.onNodeWithTag("cloud-search").performImeAction()
        compose.waitUntil(10000) { visible("搜索歌曲 01") }
        compose.onNodeWithText("更多歌曲").performClick()
        compose.waitUntil(10000) { visible("搜索歌曲 02") }
        compose.onNodeWithText("搜索歌曲 02").performClick()
        compose.runOnIdle { assertEquals(1, played.get().second); assertEquals(2, played.get().first.size) }
        compose.onNodeWithContentDescription("网易云连接").performClick()
        compose.onNodeWithText("断开连接").performClick()
        compose.waitUntil(5000) { visible("连接你的网易云音乐") }
        assertFalse(cloud.configured.value)
    }
    @Test fun aCancelledSlowSearchCannotReplaceNewerResults() {
        runBlocking { cloud.connect(backend.address) }
        compose.setContent { AmiTheme("dark") { CloudMusicContent(cloud, { _, _ -> }) } }
        try { compose.waitUntil(10000) { visible("晚风收藏") } }
        catch (failure: Throwable) {
            android.util.Log.e("AmiCloudQA", "initial configured=${cloud.configured.value} paths=${backend.requests.map { it.first }}")
            runCatching { compose.onRoot().printToLog("AmiCloudQA") }
            throw failure
        }
        compose.onNodeWithTag("cloud-search").performTextInput("慢")
        compose.onNodeWithTag("cloud-search").performImeAction()
        compose.waitUntil(5000) { backend.requests.any { it.first == "/v1/search" && it.second.optString("query") == "慢" } }
        compose.onNodeWithTag("cloud-search").performTextClearance()
        compose.onNodeWithTag("cloud-search").performTextInput("新")
        compose.onNodeWithTag("cloud-search").performImeAction()
        compose.waitUntil(5000) { visible("搜索歌曲 01") }
        backend.releaseSlow.countDown()
        compose.waitUntil(5000) { backend.slowFinished.get() }
        compose.waitForIdle()
        compose.onNodeWithText("搜索歌曲 01").assertIsDisplayed()
        compose.onNodeWithText("过期搜索结果").assertDoesNotExist()
    }
    @Test fun queueRestoresWithoutNetworkAndCredentialsStayEncrypted() = runBlocking(Dispatchers.IO) {
        cloud.connect(backend.address)
        val tracks = cloud.tracks("C".repeat(32)).tracks
        cloud.rememberQueue(tracks.filter { it.available })
        val song = tracks.first().song()
        assertTrue(cloud.lyrics(song).original.contains("晚风"))
        assertEquals("https://iot202.music.126.net/fixture.mp3?token=temporary", cloud.resolve(song.uri))
        val count = backend.requests.size
        val restored = CloudMusic(context, direct = false).restore(listOf(song.id))
        assertEquals(listOf(song.id), restored.map { it.id })
        assertEquals(song.uri, restored.single().uri)
        assertEquals(count, backend.requests.size)
        assertFalse(File(context.noBackupFilesDir, "netease-queue.json").readText().contains("temporary"))
        assertFalse(File(context.noBackupFilesDir, "netease-connection.enc").readBytes().toString(Charsets.ISO_8859_1).contains("A".repeat(43)))
    }

    /** Deterministic private-LAN fixture; never uses a real account or signed playback URL. */
    private class Backend : AutoCloseable {
        private val socket = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newCachedThreadPool()
        private val closed = AtomicBoolean(false)
        val requests = CopyOnWriteArrayList<Pair<String, JSONObject>>()
        val releaseSlow = CountDownLatch(1)
        val slowFinished = AtomicBoolean(false)
        val address = "http://127.0.0.1:${socket.localPort}/#" + "A".repeat(43)
        init { executor.execute { while (!closed.get()) {
            val client = try { socket.accept() } catch (_: Exception) { break }
            executor.execute { client.use {
                var slow = false
                try {
                    client.soTimeout = 5000
                    val input = client.getInputStream().buffered()
                    fun line(): String {
                        val bytes = java.io.ByteArrayOutputStream()
                        while (true) { val value = input.read(); if (value < 0 || value == 10) break; if (value != 13) bytes.write(value) }
                        return bytes.toString("US-ASCII")
                    }
                    val path = line().split(' ')[1]
                    var length = 0; var authorized = false
                    while (true) { val line = line(); if (line.isEmpty()) break
                        if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                        if (line.equals("Authorization: Bearer " + "A".repeat(43), true)) authorized = true
                    }
                    val bodyBytes = ByteArray(length); java.io.DataInputStream(input).readFully(bodyBytes)
                    val body = JSONObject(bodyBytes.toString(Charsets.UTF_8).ifEmpty { "{}" }); requests.add(path to body)
                    slow = body.optString("query") == "慢"
                    if (slow) releaseSlow.await(5, TimeUnit.SECONDS)
                    val response = when (path) {
                        "/v1/status" -> JSONObject().put("nickname", "慢慢听")
                        "/v1/playlists" -> JSONObject().put("playlists", JSONArray().put(JSONObject().put("id", "C".repeat(32)).put("title", "晚风收藏").put("count", 2)))
                        "/v1/tracks" -> page(listOf(row("A", "把晚风留在唱片里"), row("B", "仅可在网易云收听", false)))
                        "/v1/search" -> if (slow) page(listOf(row("D", "过期搜索结果"))) else if (body.optInt("offset") == 0) page(listOf(row("A", "搜索歌曲 01")), true) else page(listOf(row("E", "搜索歌曲 02")))
                        "/v1/lyrics" -> JSONObject().put("original", "[00:00]让晚风慢慢唱").put("translated", "")
                        "/v1/stream" -> JSONObject().put("url", "http://iot202.music.126.net/fixture.mp3?token=temporary")
                        else -> JSONObject()
                    }
                    val payload = response.toString().toByteArray(Charsets.UTF_8)
                    client.getOutputStream().apply { write("HTTP/1.1 ${if (authorized) "200 OK" else "401 Unauthorized"}\r\nContent-Type: application/json\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(payload); flush() }
                } catch (_: Exception) { /* A cancelled UI request closes its socket. */ }
                finally { if (slow) slowFinished.set(true) }
            } }
        } } }
        private fun row(id: String, title: String, available: Boolean = true) = JSONObject().put("id", id.repeat(32)).put("originalId", "1234").put("title", title)
            .put("artist", "AMI · 慢慢听").put("album", "晚风与回声").put("duration", 180000).put("available", available)
        private fun page(rows: List<JSONObject>, more: Boolean = false) = JSONObject().put("tracks", JSONArray(rows)).put("nextOffset", 30).put("more", more)
        override fun close() { closed.set(true); releaseSlow.countDown(); socket.close(); executor.shutdownNow(); executor.awaitTermination(5, TimeUnit.SECONDS) }
    }
}
