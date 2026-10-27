package com.longzhousz.ami.data

import android.content.Context
import android.util.AtomicFile
import com.longzhousz.ami.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Personal accounts use the official CLI on the paired PC; no platform private key enters Android. */
class CloudMusic(context: Context) {
    private val secret = SecretStore(context, "netease-connection")
    private val queueFile = AtomicFile(File(context.noBackupFilesDir, "netease-queue.json"))
    private val queueLock = Mutex()
    private val closer = Dispatchers.IO.limitedParallelism(1)
    val configured = MutableStateFlow(secret.configured())
    val catalog = MutableStateFlow<Map<String, Song>>(emptyMap())
    private val lyricCache = LinkedHashMap<String, SongLyrics>(8, .75f, true)
    private var lyricBytes = 0

    private fun connection(): CloudConnection {
        val saved = secret.read()?.let(::JSONObject) ?: error("请先连接电脑上的网易云连接端")
        return CloudConnection.parse(saved.getString("address"))
    }
    suspend fun connect(address: String): String = withContext(Dispatchers.IO) {
        val parsed = CloudConnection.parse(address)
        require(com.longzhousz.ami.BuildConfig.BUILD_TYPE != "release" || parsed.baseUrl.startsWith("https:")) { "正式版连接端需要 HTTPS" }
        val result = request("status", JSONObject(), parsed)
        ensureActive()
        secret.save(JSONObject().put("address", address.trim()).toString()); configured.value = true
        result.optString("nickname", "网易云音乐")
    }
    suspend fun disconnect() = withContext(Dispatchers.IO) { secret.save(""); configured.value = false; trim() }
    suspend fun profile(): String = request("status").optString("nickname", "网易云音乐")
    suspend fun playlists(): List<CloudPlaylist> = request("playlists").getJSONArray("playlists").let { rows ->
        require(rows.length() <= 1000)
        (0 until rows.length()).map { rows.getJSONObject(it) }.map { row ->
            CloudPlaylist(id(row.getString("id")), row.getString("title").take(200), picture(row), row.optInt("count"))
        }
    }
    suspend fun tracks(playlist: String, offset: Int = 0) = page(request("tracks", JSONObject().put("id", id(playlist)).put("offset", offset)))
    suspend fun search(query: String, offset: Int = 0) = page(request("search", JSONObject().put("query", query.take(100)).put("offset", offset)))
    private fun page(result: JSONObject): CloudTracks {
        val rows = result.getJSONArray("tracks"); require(rows.length() <= 500)
        return CloudTracks((0 until rows.length()).map { track(rows.getJSONObject(it)) }, result.getInt("nextOffset"), result.optBoolean("more"))
    }
    private fun id(raw: String): String = raw.uppercase(java.util.Locale.ROOT).also { require(it.matches(Regex("[A-F0-9]{32}"))) { "歌曲或歌单标识无效" } }
    private fun picture(row: JSONObject) = row.optString("cover").takeIf { it.isNotBlank() && it != "null" }?.let { cloudMediaUrl(it, true) }
    private fun track(row: JSONObject): CloudTrack {
        val original = row.getString("originalId"); require(original.matches(Regex("[0-9]{1,20}")))
        return CloudTrack(id(row.getString("id")), original, row.getString("title").take(200), row.getString("artist").take(200),
            row.getString("album").take(200), row.getLong("duration").coerceIn(0, 86_400_000), picture(row), row.optBoolean("available"))
    }
    suspend fun rememberQueue(tracks: List<CloudTrack>) = withContext(Dispatchers.IO) {
        require(tracks.size in 1..1000)
        val songs = tracks.map { it.song() }
        val rows = JSONArray().apply { tracks.forEach { track -> put(JSONObject().put("id", track.id).put("originalId", track.originalId)
            .put("title", track.title).put("artist", track.artist).put("album", track.album).put("duration", track.duration)
            .put("cover", track.cover).put("available", track.available)) } }
        queueLock.withLock {
            ensureActive()
            val output = queueFile.startWrite()
            try { output.write(rows.toString().toByteArray()); queueFile.finishWrite(output) }
            catch (error: Exception) { queueFile.failWrite(output); throw error }
            catalog.value = songs.associateBy { it.id }
        }
    }
    suspend fun restore(ids: List<String>): List<Song> = withContext(Dispatchers.IO) {
        if (ids.none { it.startsWith("netease:") }) return@withContext emptyList()
        queueLock.withLock {
            if (catalog.value.isEmpty() && queueFile.baseFile.exists()) {
                runCatching {
                    require(queueFile.baseFile.length() <= 2_000_000)
                    val rows = JSONArray(queueFile.openRead().bufferedReader().use { it.readText() }); require(rows.length() <= 1000)
                    catalog.value = (0 until rows.length()).map { track(rows.getJSONObject(it)).song() }.associateBy { it.id }
                }
            }
            ids.mapNotNull { catalog.value[it] }
        }
    }
    suspend fun lyrics(song: Song): SongLyrics {
        synchronized(lyricCache) { lyricCache[song.id]?.let { return it } }
        val body = request("lyrics", JSONObject().put("id", id(song.id.removePrefix("netease:"))))
        val document = SongLyrics(body.optString("original"), body.optString("translated"), song.lyricsVersion)
        synchronized(lyricCache) {
            if (document.estimatedBytes <= 2 * 1024 * 1024) {
                lyricCache.remove(song.id)?.let { lyricBytes -= it.estimatedBytes }
                lyricCache[song.id] = document; lyricBytes += document.estimatedBytes
                while (lyricBytes > 2 * 1024 * 1024) { val entry = lyricCache.entries.iterator(); lyricBytes -= entry.next().value.estimatedBytes; entry.remove() }
            }
        }
        return document
    }
    fun trim() = synchronized(lyricCache) { lyricCache.clear(); lyricBytes = 0 }

    /** Called only by Media3's loader thread, including seek/retry and background playback. */
    fun resolve(uri: String): String {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { "网络播放需在加载线程准备" }
        val parsed = URI(uri); require(parsed.scheme == "ami-cloud" && parsed.host == "song")
        val result = read("stream", JSONObject().put("id", id(parsed.path.removePrefix("/"))), connection())
        return cloudMediaUrl(result.getString("url"))
    }
    private suspend fun request(path: String, body: JSONObject = JSONObject(), configured: CloudConnection? = null): JSONObject = withContext(Dispatchers.IO) {
        val target = configured ?: connection()
        val http = URI(target.baseUrl + "/v1/" + path).toURL().openConnection() as HttpURLConnection
        suspendCancellableCoroutine { response ->
            val worker = launch {
                try { response.resume(read(path, body, target, http)) }
                catch (error: Exception) { response.resumeWithException(error) }
                finally { http.disconnect() }
            }
            response.invokeOnCancellation { worker.cancel(); closer.dispatch(EmptyCoroutineContext) { runCatching { http.disconnect() } } }
        }
    }
    private fun read(path: String, body: JSONObject, target: CloudConnection, connection: HttpURLConnection? = null): JSONObject {
        val http = connection ?: URI(target.baseUrl + "/v1/" + path).toURL().openConnection() as HttpURLConnection
        try {
            http.connectTimeout = 7000; http.readTimeout = 35000; http.instanceFollowRedirects = false
            http.requestMethod = "POST"; http.doOutput = true
            http.setRequestProperty("Authorization", "Bearer ${target.token}")
            http.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            http.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = http.responseCode
            if (code == 401) error("网易云连接码已失效，请重新粘贴电脑端连接地址")
            require(code in 200..299 || code == 400) { "连接端暂不可用，请确认电脑窗口已打开" }
            val stream = if (code == 400) http.errorStream else http.inputStream
            val payload = stream.bufferedReader(Charsets.UTF_8).use { reader ->
                val result = StringBuilder(); val buffer = CharArray(4096)
                while (true) { val count = reader.read(buffer); if (count < 0) break; require(result.length + count <= 2_000_000); result.append(buffer, 0, count) }
                JSONObject(result.toString())
            }
            if (code == 400) error(payload.optString("error", "网易云连接暂不可用").take(150))
            return payload
        } finally { if (connection == null) http.disconnect() }
    }
}
