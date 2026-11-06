package com.longzhousz.ami.data

import android.content.Context
import com.longzhousz.ami.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** First-party account/library access. Only explicit heart actions write to the signed-in account. */
internal class NeteaseAccount(
    context: Context,
    private val web: NeteaseTransport = NeteaseTransport(),
) {
    private val secret = SecretStore(context, "netease-account")
    private val authLock = Any()

    private data class StreamSource(
        val audio: NeteaseAudio,
        val duration: Long,
        val until: Long,
    )

    private val streams = LinkedHashMap<String, StreamSource>(3, .75f, true)

    private data class StreamFailure(
        val session: Long,
        val report: String,
    )

    private val failures = LinkedHashMap<String, StreamFailure>()
    private val warmLock = Any()
    private var warmedSession = -1L

    internal fun playbackDiagnostics(id: String): String? =
        synchronized(authLock) {
            failures[id]?.takeIf { it.session == likes.state.value.session }?.report
        }

    internal fun clearFailure(id: String) {
        synchronized(authLock) { failures.remove(id) }
    }

    internal fun forgetStream(id: String) =
        synchronized(authLock) {
            streams.remove(id)
            Unit
        }

    private var likedPlaylist: String? = null
    val likes: NeteaseLikes =
        NeteaseLikes(read = { session ->
            val auth = likeCredentials(session)
            val rows =
                web
                    .post("song/like/get", JSONObject().put("uid", neteaseId(auth.getString("uid"))), auth.getString("cookie"))
                    .optJSONArray("ids") ?: error("红心列表暂时无法读取")
            require(rows.length() <= 100000) { "红心列表过大，暂时无法读取" }
            (0 until rows.length()).map { neteaseId(rows.optString(it)) }.toSet()
        }, write = { session, id, liked ->
            val auth = likeCredentials(session)
            val result =
                web.post(
                    "radio/like",
                    JSONObject()
                        .put("alg", "itembased")
                        .put("trackId", neteaseId(id))
                        .put("like", liked)
                        .put("time", "3"),
                    auth.getString("cookie"),
                )
            synchronized(authLock) {
                if (likes.state.value.session == session) {
                    result.optString("playlistId").takeIf { it.matches(Regex("[1-9][0-9]{0,19}")) }?.let { likedPlaylist = it }
                    synchronized(lists) {
                        lists["tracks:$likedPlaylist"]?.let { old ->
                            val ids = (if (liked) listOf(id) else emptyList()) + old.ids.filter { it != id }
                            lists["tracks:$likedPlaylist"] = PlaylistIds(System.nanoTime(), ids)
                        }
                    }
                }
            }
        })

    private fun likeCredentials(session: Long): JSONObject =
        synchronized(authLock) {
            if (likes.state.value.session != session) throw kotlinx.coroutines.CancellationException("网易云账号已变化")
            credentials()
        }

    private data class PlaylistIds(
        val at: Long,
        val ids: List<String>,
    )

    private val lists = LinkedHashMap<String, PlaylistIds>(4, .75f, true)

    fun configured() = secret.configured()

    private fun credentials(): JSONObject = secret.read()?.let(::JSONObject) ?: error("请先登录网易云音乐")

    fun accountKey(): String =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(credentials().getString("uid").toByteArray())
            .joinToString("") { "%02x".format(it) }

    suspend fun login(rawCookies: String): String =
        withContext(Dispatchers.IO) {
            val cookie = neteaseCookies(rawCookies)
            val profile = profile(web.post("w/nuser/account/get", JSONObject(), cookie))
            synchronized(authLock) {
                secret.save(
                    JSONObject()
                        .put("cookie", cookie)
                        .put("uid", profile.first)
                        .put("nickname", profile.second)
                        .toString(),
                )
                likedPlaylist = null
                streams.clear()
                failures.clear()
                likes.reset()
            }
            invalidate()
            profile.second
        }

    fun logout() {
        synchronized(authLock) {
            secret.save("")
            likedPlaylist = null
            streams.clear()
            failures.clear()
            likes.reset()
        }
        invalidate()
    }

    fun invalidate() = synchronized(lists) { lists.clear() }

    suspend fun request(
        path: String,
        data: JSONObject,
    ): JSONObject =
        withContext(Dispatchers.IO) {
            val (auth, session) = synchronized(authLock) { credentials() to likes.state.value.session }
            val cookie = neteaseCookies(auth.getString("cookie"))
            when (path) {
                "status" -> {
                    val user = profile(web.post("w/nuser/account/get", JSONObject(), cookie))
                    check(user.first == auth.getString("uid")) { "网易云账号已变化，请重新登录" }
                    JSONObject().put("nickname", user.second)
                }

                "playlists" -> {
                    val all = JSONArray()
                    var offset = 0
                    do {
                        val root =
                            web.post(
                                "user/playlist",
                                JSONObject()
                                    .put("uid", neteaseId(auth.getString("uid")))
                                    .put("limit", 100)
                                    .put("offset", offset)
                                    .put("includeVideo", false),
                                cookie,
                            )
                        val rows = root.optJSONArray("playlist") ?: JSONArray()
                        require(rows.length() <= 100)
                        for (i in 0 until rows.length()) {
                            val row = rows.getJSONObject(i)
                            val kind =
                                when {
                                    row.optInt("specialType") == 5 -> "liked"
                                    row.optJSONObject("creator")?.optString("userId") == auth.getString("uid") -> "created"
                                    else -> "collected"
                                }
                            if (kind == "liked") {
                                synchronized(authLock) {
                                    if (likes.state.value.session == session) likedPlaylist = neteaseId(row.optString("id"))
                                }
                            }
                            all.put(
                                JSONObject()
                                    .put("id", neteaseId(row.optString("id")))
                                    .put("title", row.optString("name").take(200))
                                    .put("cover", picture(row.optString("coverImgUrl")))
                                    .put("count", row.optInt("trackCount").coerceAtLeast(0))
                                    .put("kind", kind),
                            )
                        }
                        offset += rows.length()
                    } while (rows.length() == 100 && root.optBoolean("more") && offset < 1000)
                    require(all.length() <= 1000) { "歌单数量过多，请稍后分批读取" }
                    JSONObject().put("playlists", all)
                }

                "albums" -> {
                    val all = JSONArray()
                    var offset = 0
                    do {
                        val root = web.post("album/sublist", JSONObject().put("limit", 100).put("offset", offset).put("total", true), cookie)
                        val rows = root.optJSONArray("data") ?: JSONArray()
                        require(rows.length() <= 100)
                        for (i in 0 until rows.length()) {
                            val row = rows.getJSONObject(i)
                            val artists = row.optJSONArray("artists") ?: JSONArray()
                            val artist =
                                (0 until artists.length())
                                    .map {
                                        artists.getJSONObject(it).optString("name")
                                    }.filter { it.isNotBlank() }
                                    .joinToString(" / ")
                            all.put(
                                JSONObject()
                                    .put("id", neteaseId(row.optString("id")))
                                    .put("title", row.optString("name").take(200))
                                    .put("cover", picture(row.optString("picUrl")))
                                    .put("count", row.optInt("size").coerceAtLeast(0))
                                    .put("kind", "album")
                                    .put("artist", artist.take(200)),
                            )
                        }
                        offset += rows.length()
                    } while (rows.length() == 100 && root.optBoolean("hasMore") && offset < 1000)
                    JSONObject().put("albums", all)
                }

                "tracks", "albumTracks" -> {
                    val id = neteaseId(data.getString("id"))
                    val offset = data.optInt("offset")
                    require(offset in 0..20000)
                    val album = path == "albumTracks"
                    val key = "$path:$id"
                    var albumDetail: JSONObject? = null
                    val ids =
                        synchronized(lists) { lists[key]?.takeIf { System.nanoTime() - it.at < 120_000_000_000L }?.ids } ?: run {
                            val rows =
                                if (album) {
                                    albumDetail = web.post("v1/album/$id", JSONObject(), cookie)
                                    albumDetail?.optJSONArray("songs") ?: JSONArray()
                                } else {
                                    web
                                        .post("v6/playlist/detail", JSONObject().put("id", id).put("n", 0).put("s", 0), cookie)
                                        .optJSONObject("playlist")
                                        ?.optJSONArray("trackIds") ?: error("暂时无法读取这张歌单")
                                }
                            require(rows.length() <= 20000) { "这张歌单过大，暂时无法载入" }
                            val loaded = (0 until rows.length()).map { neteaseId(rows.getJSONObject(it).optString("id")) }
                            synchronized(lists) {
                                lists[key] = PlaylistIds(System.nanoTime(), loaded)
                                while (lists.size > 4) lists.remove(lists.keys.first())
                            }
                            loaded
                        }
                    val page = ids.drop(offset).take(30)
                    val result =
                        if (page.isEmpty()) {
                            JSONObject().put("songs", JSONArray())
                        } else {
                            albumDetail
                                ?: web.post(
                                    "v3/song/detail",
                                    JSONObject().put(
                                        "c",
                                        JSONArray()
                                            .apply {
                                                page.forEach { put(JSONObject().put("id", it)) }
                                            }.toString(),
                                    ),
                                    cookie,
                                )
                        }
                    val songs = result.optJSONArray("songs") ?: JSONArray()
                    val privileges = privileges(result)
                    val byId = (0 until songs.length()).map { songs.getJSONObject(it) }.associateBy { it.optString("id") }
                    val tracks =
                        JSONArray().apply {
                            page.forEach { songId ->
                                put(
                                    byId[songId]?.let { track(it, privileges[songId]) } ?: JSONObject()
                                        .put("id", songId)
                                        .put("originalId", songId)
                                        .put("title", "暂不可用的歌曲")
                                        .put("artist", "")
                                        .put("album", "")
                                        .put("duration", 0)
                                        .put("available", false),
                                )
                            }
                        }
                    JSONObject().put("tracks", tracks).put("nextOffset", offset + page.size).put("more", offset + page.size < ids.size)
                }

                "search" -> {
                    val query = data.getString("query").trim()
                    require(query.length in 1..100)
                    val offset = data.optInt("offset")
                    require(offset in 0..20000)
                    val root =
                        web
                            .post(
                                "cloudsearch/pc",
                                JSONObject()
                                    .put("s", query)
                                    .put("type", 1)
                                    .put("limit", 30)
                                    .put("offset", offset)
                                    .put("total", true),
                                cookie,
                            ).optJSONObject("result") ?: JSONObject()
                    val rows = root.optJSONArray("songs") ?: JSONArray()
                    require(rows.length() <= 30)
                    JSONObject()
                        .put(
                            "tracks",
                            JSONArray().apply {
                                for (i in 0 until rows.length()) {
                                    val row = rows.getJSONObject(i)
                                    put(track(row, row.optJSONObject("privilege")))
                                }
                            },
                        ).put("nextOffset", offset + rows.length())
                        .put("more", offset + rows.length() < root.optInt("songCount"))
                }

                "fm", "daily" -> {
                    val root = web.post(if (path == "fm") "v1/radio/get" else "v3/discovery/recommend/songs", JSONObject(), cookie)
                    val rows =
                        if (path == "fm") {
                            root.optJSONArray("data")
                        } else {
                            root.optJSONObject("data")?.optJSONArray("dailySongs") ?: root.optJSONArray("recommend")
                        }
                    require(rows != null && rows.length() <= 100) { "推荐暂时无法读取，请稍后重试" }
                    val rights = privileges(root)
                    JSONObject()
                        .put(
                            "tracks",
                            JSONArray().apply {
                                for (i in 0 until rows.length()) {
                                    val row = rows.getJSONObject(i)
                                    put(track(row, row.optJSONObject("privilege") ?: rights[row.optString("id")]))
                                }
                            },
                        ).put("nextOffset", rows.length())
                        .put("more", false)
                }

                "lyrics" -> {
                    val id = neteaseId(data.getString("id"))
                    val root =
                        try {
                            web.post(
                                "song/lyric/v1",
                                JSONObject()
                                    .put("id", id)
                                    .put("cp", false)
                                    .put("lv", 0)
                                    .put("tv", 0)
                                    .put("rv", 0)
                                    .put("kv", 0)
                                    .put("yv", 0)
                                    .put("ytv", 0)
                                    .put("yrv", 0),
                                cookie,
                            )
                        } catch (
                            cancelled: kotlinx.coroutines.CancellationException,
                        ) {
                            throw cancelled
                        } catch (
                            expired: NeteaseLoginExpired,
                        ) {
                            throw expired
                        } catch (
                            _: Exception,
                        ) {
                            web.post(
                                "song/lyric",
                                JSONObject()
                                    .put("id", id)
                                    .put("lv", -1)
                                    .put("tv", -1)
                                    .put("rv", -1)
                                    .put("kv", -1),
                                cookie,
                            )
                        }
                    val words =
                        root
                            .optJSONObject("yrc")
                            ?.optString("lyric")
                            .orEmpty()
                            .take(500000)
                    val original =
                        root
                            .optJSONObject("lrc")
                            ?.optString("lyric")
                            .orEmpty()
                            .take(500000)
                            .ifBlank { if (words.isBlank()) "" else runCatching { NeteaseLyrics.decode(words).original }.getOrDefault("") }
                    JSONObject()
                        .put("original", original)
                        .put("wordTimed", words)
                        .put(
                            "translated",
                            root
                                .optJSONObject("tlyric")
                                ?.optString("lyric")
                                .orEmpty()
                                .take(500000),
                        )
                }

                else -> {
                    error("不支持的网易云操作")
                }
            }
        }

    /** A few short-lived, account-bound URLs avoid re-resolving every range/seek. Never persisted. */
    fun stream(
        id: String,
        duration: Long,
    ): NeteaseAudio {
        neteaseId(id)
        val (auth, session) =
            synchronized(authLock) {
                streams[id]?.takeIf { it.duration == duration && it.until > android.os.SystemClock.elapsedRealtime() }?.let { return it.audio }
                credentials() to likes.state.value.session
            }
        val originalCookie = neteaseCookies(auth.getString("cookie"))
        val cookie =
            if (originalCookie.split(';').any { it.trim().startsWith("__csrf=") }) {
                originalCookie
            } else {
                synchronized(warmLock) {
                    val current =
                        synchronized(authLock) {
                            if (likes.state.value.session != session) throw kotlinx.coroutines.CancellationException("网易云账号已变化")
                            neteaseCookies(credentials().getString("cookie"))
                        }
                    if (current.split(';').any { it.trim().startsWith("__csrf=") } || warmedSession == session) {
                        current
                    } else {
                        warmedSession = session
                        val fresh = runCatching { web.preheat(current) }.getOrDefault(current)
                        synchronized(authLock) {
                            if (likes.state.value.session != session) throw kotlinx.coroutines.CancellationException("网易云账号已变化")
                            if (fresh != current) secret.save(credentials().put("cookie", fresh).toString())
                        }
                        fresh
                    }
                }
            }
        val requestedAt = android.os.SystemClock.elapsedRealtime()
        val attempts = ArrayList<String>(8)

        fun measured(
            route: String,
            data: JSONObject,
            request: () -> JSONObject,
        ): JSONObject {
            try {
                return request().also { if (attempts.size < 8) attempts += audioAttempt(route, data.optString("level", "exhigh"), it) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val code =
                    when (failure) {
                        is NeteaseLoginExpired -> "login-expired"
                        is NeteaseRequestRejected -> "api-${failure.code}"
                        is java.io.IOException -> "network"
                        else -> "invalid-response"
                    }
                if (attempts.size < 8) attempts += "$route/${data.optString("level", "exhigh")}: $code"
                throw failure
            }
        }
        val audio =
            try {
                authorizedNeteaseAudio(
                    id,
                    duration,
                    { data -> measured("android", data) { web.blockingAudio(data, cookie) } },
                    { path, data -> measured(if (path.endsWith("/v1")) "web" else "web-legacy", data) { web.blockingPost(path, data, cookie) } },
                    desktop = { data -> measured("desktop", data) { web.blockingAudio(data, cookie, desktop = true) } },
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                synchronized(authLock) {
                    if (likes.state.value.session != session) throw kotlinx.coroutines.CancellationException("网易云账号已变化")
                    failures[id] =
                        StreamFailure(
                            session,
                            "song=$id csrf=${if (cookie.split(';').any { it.trim().startsWith("__csrf=") }) "present" else "missing"}\n" +
                                attempts.joinToString("\n"),
                        )
                    while (failures.size > 3) failures.remove(failures.keys.first())
                }
                throw failure
            }
        synchronized(authLock) {
            if (likes.state.value.session != session) throw kotlinx.coroutines.CancellationException("网易云账号已变化")
            failures.remove(id)
            val lifetime = (audio.validForSeconds.coerceAtMost(600) - 30).coerceAtLeast(0) * 1000
            if (requestedAt + lifetime > android.os.SystemClock.elapsedRealtime()) {
                streams[id] = StreamSource(audio, duration, requestedAt + lifetime)
                while (streams.size > 3) streams.remove(streams.keys.first())
            }
        }
        android.util.Log.i("AmiAudio", "source=netease level=${audio.level ?: "unknown"} codec=${audio.codec} bitrate=${audio.bitrate ?: 0}")
        return audio
    }

    companion object {
        private fun profile(root: JSONObject): Pair<String, String> {
            val row = root.optJSONObject("profile") ?: error("请先在官方页面完成登录")
            return neteaseId(row.optString("userId")) to row.optString("nickname", "网易云音乐").take(100)
        }

        private fun picture(raw: String) = runCatching { cloudMediaUrl(raw, true) }.getOrNull()

        private fun privileges(root: JSONObject): Map<String, JSONObject> {
            val rows = root.optJSONArray("privileges") ?: JSONArray()
            return (0 until rows.length()).map { rows.getJSONObject(it) }.associateBy { it.optString("id") }
        }

        internal fun track(
            row: JSONObject,
            privilege: JSONObject? = null,
        ): JSONObject {
            val id = neteaseId(row.optString("id"))
            val album = row.optJSONObject("al") ?: row.optJSONObject("album")
            val artists = row.optJSONArray("ar") ?: row.optJSONArray("artists") ?: JSONArray()
            val artist = (0 until artists.length()).map { artists.getJSONObject(it).optString("name") }.filter { it.isNotBlank() }.joinToString(" / ")
            return JSONObject()
                .put("id", id)
                .put("originalId", id)
                .put("title", row.optString("name").take(200))
                .put("artist", artist.take(200))
                .put("album", album?.optString("name").orEmpty().take(200))
                .put("cover", picture(album?.optString("picUrl").orEmpty()))
                .put("primaryArtistId", artists.optJSONObject(0)?.optString("id"))
                .put("primaryArtistName", artists.optJSONObject(0)?.optString("name")?.take(200))
                .put("albumId", album?.optString("id"))
                .put("duration", row.optLong("dt", row.optLong("duration")).coerceIn(0, 86_400_000))
                .put("available", (privilege?.optInt("st", 0) ?: 0) >= 0)
        }

        private fun hasTrial(row: JSONObject) = !row.isNull("freeTrialInfo") && row.opt("freeTrialInfo")?.toString() != "null"

        internal fun streamUrl(
            root: JSONObject,
            id: String,
            duration: Long,
        ): String {
            when (val code = root.optInt("code")) {
                200 -> Unit
                301, 302, 401 -> throw NeteaseLoginExpired()
                else -> throw NeteaseRequestRejected(code)
            }
            val row = audioRow(root) ?: throw NeteaseAudioUnavailable(serverCode = root.optInt("code"))
            require(row.optString("id") == neteaseId(id)) { "网易云返回了不同歌曲，请重试" }
            if (hasTrial(row) || (row.optLong("time") > 0 && duration > 0 && row.optLong("time") + 5000 < duration)) throw NeteaseAudioUnavailable(trial = true)
            val url = row.optString("url")
            val code = row.optInt("code", root.optInt("code"))
            if (code != 200 || url.isBlank() || url == "null") throw NeteaseAudioUnavailable(serverCode = code)
            return cloudMediaUrl(url)
        }
    }
}
