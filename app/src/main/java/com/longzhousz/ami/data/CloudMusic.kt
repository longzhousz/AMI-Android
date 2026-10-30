package com.longzhousz.ami.data

import android.content.Context
import android.util.AtomicFile
import com.longzhousz.ami.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
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

/** Direct phone login by default; cloudPreview retains the older paired-PC experiment. */
class CloudMusic internal constructor(private val context: Context, val direct: Boolean, web: NeteaseTransport, cache: OnlineAudioCache? = null) {
    constructor(context: Context, direct: Boolean = com.longzhousz.ami.BuildConfig.BUILD_TYPE != "cloudPreview") : this(context, direct, NeteaseTransport())
    private val account = NeteaseAccount(context, web)
    private val audioCache by lazy { cache ?: (context.applicationContext as com.longzhousz.ami.AmiApplication).repository.onlineCache }
    private val authLock = Any()
    @Volatile private var bypassCacheId: String? = null
    @Volatile internal var recoveringAudio: String? = null
    @Volatile internal var playbackFailure: Pair<String, String>? = null
    private fun cacheAccount(): String = synchronized(authLock) { account.accountKey().also { audioCache.switchAccount(it) } }
    private val secret = SecretStore(context, "netease-connection")
    private val queueFile = AtomicFile(File(context.noBackupFilesDir, "netease-queue.json"))
    private val queueLock = Mutex()
    private val closer = Dispatchers.IO.limitedParallelism(1)
    val configured = MutableStateFlow(if (direct) account.configured() else secret.configured())
    val catalog = MutableStateFlow<Map<String, Song>>(emptyMap())
    val radio = MutableStateFlow<String?>(null)
    private var queueTracks: List<CloudTrack> = emptyList()
    fun stopRadio() { radio.value = null }
    val audioQuality = MutableStateFlow<Map<String, AudioQuality>>(emptyMap())
    @Synchronized private fun rememberQuality(value: AudioQuality) {
        val next = audioQuality.value.toMutableMap()
        next.remove(value.id); next[value.id] = value
        while (next.size > 4) next.remove(next.keys.first())
        audioQuality.value = next
    }
    private val lyricCache = LinkedHashMap<String, SongLyrics>(8, .75f, true)
    private var lyricBytes = 0
    // One list per primary tab, not a growing cache for every visited album/search.
    private val pages = arrayOfNulls<Pair<String, CloudTracks>>(2)
    @Synchronized fun savedPage(favorites: Boolean, key: String): CloudTracks? = pages[if (favorites) 1 else 0]?.takeIf { it.first == key }?.second
    @Synchronized fun savePage(favorites: Boolean, key: String, page: CloudTracks) { pages[if (favorites) 1 else 0] = key to page }
    @Synchronized fun clearPages() { pages.fill(null) }

    private fun connection(): CloudConnection {
        val saved = secret.read()?.let(::JSONObject) ?: error("请先连接电脑上的网易云连接端")
        return CloudConnection.parse(saved.getString("address"))
    }
    suspend fun connect(address: String): String = withContext(Dispatchers.IO) {
        check(!direct) { "请使用手机上的网易云登录页面" }
        val parsed = CloudConnection.parse(address)
        require(com.longzhousz.ami.BuildConfig.BUILD_TYPE != "release" || parsed.baseUrl.startsWith("https:")) { "正式版连接端需要 HTTPS" }
        val result = request("status", JSONObject(), parsed)
        ensureActive()
        secret.save(JSONObject().put("address", address.trim()).toString()); configured.value = true
        result.optString("nickname", "网易云音乐")
    }
    suspend fun login(cookies: String): String {
        check(direct)
        stopRadio()
        val nickname = account.login(cookies)
        withContext(Dispatchers.IO) { runCatching { cacheAccount() } }
        clearPages()
        configured.value = true
        return nickname
    }
    fun refreshCatalog() { if (direct) account.invalidate() }
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        stopRadio()
        if (direct) synchronized(authLock) {
            account.logout(); OnlineCacheWorker.cancel(context); runCatching { audioCache.switchAccount(null) }
        } else secret.save("")
        configured.value = false; trim(); clearPages(); audioQuality.value = emptyMap()
    }
    suspend fun profile(): String = request("status").optString("nickname", "网易云音乐")
    suspend fun playlists(): List<CloudPlaylist> = request("playlists").getJSONArray("playlists").let { rows ->
        require(rows.length() <= 1000)
        (0 until rows.length()).map { rows.getJSONObject(it) }.map { row ->
            CloudPlaylist(id(row.getString("id")), row.getString("title").take(200), picture(row), row.optInt("count"), row.optString("kind"))
        }
    }
    suspend fun library(): CloudLibrary {
        check(direct)
        val playlists = playlists()
        val rows = request("albums").getJSONArray("albums")
        require(rows.length() <= 1000)
        val albums = (0 until rows.length()).map { rows.getJSONObject(it) }.map {
            CloudPlaylist(id(it.getString("id")), it.getString("title").take(200), picture(it), it.optInt("count"), "album", it.optString("artist").take(200))
        }
        return CloudLibrary(albums + playlists.filter { it.kind == "created" }, playlists.firstOrNull { it.kind == "liked" })
    }
    suspend fun tracks(collection: CloudPlaylist, offset: Int = 0): CloudTracks =
        if (collection.kind == "album") page(request("albumTracks", JSONObject().put("id", id(collection.id)).put("offset", offset)))
        else tracks(collection.id, offset)
    suspend fun tracks(playlist: String, offset: Int = 0) = page(request("tracks", JSONObject().put("id", id(playlist)).put("offset", offset)))
    suspend fun search(query: String, offset: Int = 0) = page(request("search", JSONObject().put("query", query.take(100)).put("offset", offset)))
    suspend fun discovery(kind: String): List<CloudTrack> {
        require(direct && kind in setOf("fm", "daily"))
        return page(request(kind)).tracks.distinctBy { it.id }.filter { it.available }
    }
    private fun page(result: JSONObject): CloudTracks {
        val rows = result.getJSONArray("tracks"); require(rows.length() <= 500)
        return CloudTracks((0 until rows.length()).map { track(rows.getJSONObject(it)) }, result.getInt("nextOffset"), result.optBoolean("more"))
    }
    private fun id(raw: String): String = raw.uppercase(java.util.Locale.ROOT).also { require(it.matches(Regex("[A-F0-9]{32}")) || direct && it.matches(Regex("[1-9][0-9]{0,19}"))) { "歌曲或歌单标识无效" } }
    private fun picture(row: JSONObject) = row.optString("cover").takeIf { it.isNotBlank() && it != "null" }?.let { cloudMediaUrl(it, true) }
    private fun track(row: JSONObject): CloudTrack {
        val original = row.getString("originalId"); require(original.matches(Regex("[0-9]{1,20}")))
        return CloudTrack(id(row.getString("id")), original, row.getString("title").take(200), row.getString("artist").take(200),
            row.getString("album").take(200), row.getLong("duration").coerceIn(0, 86_400_000), picture(row), row.optBoolean("available"),
            row.optString("primaryArtistId").takeIf { it.matches(Regex("[1-9][0-9]{0,19}")) },
            row.optString("primaryArtistName").takeIf { it.isNotBlank() && it != "null" }?.take(200),
            row.optString("albumId").takeIf { it.matches(Regex("[1-9][0-9]{0,19}")) })
    }
    suspend fun rememberQueue(tracks: List<CloudTrack>, radioToken: String? = null) = withContext(Dispatchers.IO) {
        require(tracks.size in 1..1000)
        queueLock.withLock { ensureActive(); writeQueue(tracks, radioToken) }
    }
    /** Called under queueLock on IO. Queue and its radio identity are committed together. */
    private fun writeQueue(tracks: List<CloudTrack>, radioToken: String?) {
        val rows = JSONArray().apply { tracks.forEach { track -> put(JSONObject().put("id", track.id).put("originalId", track.originalId)
            .put("title", track.title).put("artist", track.artist).put("album", track.album).put("duration", track.duration)
            .put("cover", track.cover).put("available", track.available)
            .put("primaryArtistId", track.primaryArtistId).put("primaryArtistName", track.primaryArtistName).put("albumId", track.albumId)) } }
        val saved = JSONObject().put("tracks", rows).put("radio", radioToken)
            .put("radioAccount", if (radioToken == null) null else account.accountKey())
        val bytes = saved.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 2_000_000) { "播放队列过大，请减少载入的曲目" }
        val output = queueFile.startWrite()
        try { output.write(bytes); queueFile.finishWrite(output) }
        catch (error: Exception) { queueFile.failWrite(output); throw error }
        queueTracks = tracks
        catalog.value = tracks.map { it.song() }.associateBy { it.id }
        radio.value = radioToken
    }
    suspend fun appendRadio(token: String, ids: List<String>, candidates: List<CloudTrack>): List<Song> = withContext(Dispatchers.IO) {
        queueLock.withLock {
            ensureActive()
            if (radio.value != token || ids.size >= 1000) return@withLock emptyList()
            val byId = queueTracks.associateBy { "netease:${it.id}" }
            val existing = ids.map { byId[it] ?: return@withLock emptyList() }
            val seen = ids.toHashSet()
            val added = candidates.filter { it.available && "netease:${it.id}" !in seen }.distinctBy { it.id }.take(1000 - existing.size)
            if (added.isNotEmpty()) writeQueue(existing + added, token)
            added.map { it.song() }
        }
    }
    suspend fun restore(ids: List<String>): List<Song> = withContext(Dispatchers.IO) {
        if (ids.none { it.startsWith("netease:") }) return@withContext emptyList()
        queueLock.withLock {
            if (catalog.value.isEmpty() && queueFile.baseFile.exists()) {
                runCatching {
                    require(queueFile.baseFile.length() <= 2_000_000)
                    val text = queueFile.openRead().bufferedReader().use { it.readText() }
                    val root = text.takeIf { it.trimStart().startsWith('{') }?.let(::JSONObject)
                    val rows = root?.getJSONArray("tracks") ?: JSONArray(text); require(rows.length() <= 1000)
                    queueTracks = (0 until rows.length()).map { track(rows.getJSONObject(it)) }
                    catalog.value = queueTracks.map { it.song() }.associateBy { it.id }
                    radio.value = root?.optString("radio")?.takeIf {
                        it.matches(Regex("[a-f0-9-]{36}")) && root.optString("radioAccount") == account.accountKey()
                    }
                }
            }
            ids.mapNotNull { catalog.value[it] }
        }
    }
    suspend fun lyrics(song: Song): SongLyrics = withContext(Dispatchers.IO) {
        synchronized(lyricCache) { lyricCache[song.id]?.let { return@withContext it } }
        val body = request("lyrics", JSONObject().put("id", if (direct) song.neteaseId ?: id(song.id.removePrefix("netease:")) else id(song.id.removePrefix("netease:"))))
        val document = SongLyrics(body.optString("original"), body.optString("translated"), song.lyricsVersion, body.optString("wordTimed"))
        document.lines // Parse on the loader thread; lyric animation never parses a new document.
        ensureActive()
        synchronized(lyricCache) {
            if (document.estimatedBytes <= 2 * 1024 * 1024) {
                lyricCache.remove(song.id)?.let { lyricBytes -= it.estimatedBytes }
                lyricCache[song.id] = document; lyricBytes += document.estimatedBytes
                while (lyricBytes > 2 * 1024 * 1024) { val entry = lyricCache.entries.iterator(); lyricBytes -= entry.next().value.estimatedBytes; entry.remove() }
            }
        }
        document
    }
    fun trim() = synchronized(lyricCache) { lyricCache.clear(); lyricBytes = 0 }

    suspend fun maintainAudioCache() = withContext(Dispatchers.IO) {
        val app = context.applicationContext as com.longzhousz.ami.AmiApplication
        val idle = { app.repository.canMaintainCaches }
        if (!idle()) return@withContext
        android.os.Trace.beginSection("ami:cache-maintenance")
        try {
            runCatching {
                app.maintainArtworkCache()
                if (direct && configured.value && idle()) {
                    val key = cacheAccount(); val now = System.currentTimeMillis()
                    audioCache.cleanup(now, active = idle)
                    if (idle() && audioCache.candidates(key, now).isNotEmpty()) OnlineCacheWorker.enqueue(context, key)
                }
            }
        } finally { android.os.Trace.endSection() }
    }
    suspend fun recordListen(songId: String) = withContext(Dispatchers.IO) {
        if (!direct || !configured.value || !songId.startsWith("netease:")) return@withContext
        runCatching {
            val key = cacheAccount(); val id = neteaseId(songId.removePrefix("netease:"))
            val now = System.currentTimeMillis()
            if (audioCache.record(key, id, catalog.value[songId]?.duration ?: 0, now)) {
                if (audioCache.candidates(key, now).isNotEmpty()) OnlineCacheWorker.enqueue(context, key)
            }
        }
    }
    fun protectAudio(songId: String?) {
        if (!direct) return
        val next = songId?.removePrefix("netease:")
        val changed = audioCache.playingId != next
        audioCache.playingId = next
        if (changed && audioCache.trimPending) (context.applicationContext as com.longzhousz.ami.AmiApplication).repository.scope.launch {
            maintainAudioCache()
        }
    }
    suspend fun discardCachedAudio(songId: String) = withContext(Dispatchers.IO) {
        bypassCacheId = songId
        if (direct && configured.value) runCatching { audioCache.invalidate(cacheAccount(), songId.removePrefix("netease:")) }
        synchronized(this@CloudMusic) { audioQuality.value = audioQuality.value - songId }
    }
    internal suspend fun fillAudioCache(expectedAccount: String, idle: () -> Boolean = { true }) = withContext(Dispatchers.IO) {
        if (!direct || !configured.value || runCatching { account.accountKey() }.getOrNull() != expectedAccount || audioCache.scope() != expectedAccount) return@withContext
        if (!idle()) return@withContext
        audioCache.cleanup(System.currentTimeMillis(), active = idle)
        val coroutine = currentCoroutineContext()
        for (candidate in audioCache.candidates(expectedAccount, System.currentTimeMillis())) {
            ensureActive()
            if (audioCache.scope() != expectedAccount || !idle()) break
            try {
                val audio = account.stream(candidate.id, candidate.duration)
                audioCache.download(expectedAccount, candidate, audio, System.currentTimeMillis()) {
                    coroutine.isActive && idle() && audioCache.scope() == expectedAccount
                }
            } catch (cancelled: CancellationException) { ensureActive(); break }
            catch (_: Exception) {
                ensureActive()
                if (!idle()) break // Yielding to the UI or playback is not a network failure.
                audioCache.failed(expectedAccount, candidate.id, System.currentTimeMillis())
            }
        }
    }

    /** Called only by Media3's loader thread, including seek/retry and background playback. */
    fun resolve(uri: String, allowCache: Boolean = true): String {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { "网络播放需在加载线程准备" }
        val parsed = URI(uri); require(parsed.scheme == "ami-cloud" && parsed.host == "song")
        if (direct) {
            val key = id(parsed.path.removePrefix("/"))
            val cached = if (!allowCache || bypassCacheId == "netease:$key") null else runCatching {
                audioCache.get(cacheAccount(), key, System.currentTimeMillis())
            }.getOrNull()
            cached?.let { hit ->
                rememberQuality(hit.quality)
                return android.net.Uri.fromFile(hit.file).toString()
            }
            val song = catalog.value["netease:$key"]
            val audio = account.stream(song?.neteaseId ?: neteaseId(key), song?.duration ?: 0)
            rememberQuality(AudioQuality("netease:$key", audio.level, audio.codec, audio.bitrate))
            return audio.url
        }
        val result = read("stream", JSONObject().put("id", id(parsed.path.removePrefix("/"))), connection())
        return cloudMediaUrl(result.getString("url"))
    }
    private suspend fun request(path: String, body: JSONObject = JSONObject(), configured: CloudConnection? = null): JSONObject = withContext(Dispatchers.IO) {
        if (direct) return@withContext account.request(path, body)
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
