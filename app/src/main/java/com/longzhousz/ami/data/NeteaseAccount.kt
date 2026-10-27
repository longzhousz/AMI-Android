package com.longzhousz.ami.data

import android.content.Context
import com.longzhousz.ami.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Read-only account/library access. No account writes, downloads, alternative audio, or public proxy. */
internal class NeteaseAccount(context: Context, private val web: NeteaseTransport = NeteaseTransport()) {
    private val secret = SecretStore(context, "netease-account")
    private data class PlaylistIds(val at: Long, val ids: List<String>)
    private val lists = LinkedHashMap<String, PlaylistIds>(4, .75f, true)
    fun configured() = secret.configured()
    private fun credentials(): JSONObject = secret.read()?.let(::JSONObject) ?: error("请先登录网易云音乐")
    suspend fun login(rawCookies: String): String = withContext(Dispatchers.IO) {
        val cookie = neteaseCookies(rawCookies)
        val profile = profile(web.post("w/nuser/account/get", JSONObject(), cookie))
        secret.save(JSONObject().put("cookie", cookie).put("uid", profile.first).put("nickname", profile.second).toString())
        invalidate()
        profile.second
    }
    fun logout() { secret.save(""); invalidate() }
    fun invalidate() = synchronized(lists) { lists.clear() }
    suspend fun request(path: String, data: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val auth = credentials()
        val cookie = neteaseCookies(auth.getString("cookie"))
        when (path) {
            "status" -> {
                val user = profile(web.post("w/nuser/account/get", JSONObject(), cookie))
                check(user.first == auth.getString("uid")) { "网易云账号已变化，请重新登录" }
                JSONObject().put("nickname", user.second)
            }
            "playlists" -> {
                val all = JSONArray(); var offset = 0
                do {
                    val root = web.post("user/playlist", JSONObject().put("uid", neteaseId(auth.getString("uid")))
                        .put("limit", 100).put("offset", offset).put("includeVideo", false), cookie)
                    val rows = root.optJSONArray("playlist") ?: JSONArray()
                    require(rows.length() <= 100)
                    for (i in 0 until rows.length()) {
                        val row = rows.getJSONObject(i)
                        val kind = when {
                            row.optInt("specialType") == 5 -> "liked"
                            row.optJSONObject("creator")?.optString("userId") == auth.getString("uid") -> "created"
                            else -> "collected"
                        }
                        all.put(JSONObject().put("id", neteaseId(row.optString("id"))).put("title", row.optString("name").take(200))
                            .put("cover", picture(row.optString("coverImgUrl"))).put("count", row.optInt("trackCount").coerceAtLeast(0)).put("kind", kind))
                    }
                    offset += rows.length()
                } while (rows.length() == 100 && root.optBoolean("more") && offset < 1000)
                require(all.length() <= 1000) { "歌单数量过多，请稍后分批读取" }
                JSONObject().put("playlists", all)
            }
            "tracks" -> {
                val id = neteaseId(data.getString("id")); val offset = data.optInt("offset")
                require(offset in 0..20000)
                val ids = synchronized(lists) { lists[id]?.takeIf { System.nanoTime() - it.at < 120_000_000_000L }?.ids } ?: run {
                    val detail = web.post("v6/playlist/detail", JSONObject().put("id", id).put("n", 0).put("s", 0), cookie)
                        .optJSONObject("playlist") ?: error("暂时无法读取这张歌单")
                    val rows = detail.optJSONArray("trackIds") ?: JSONArray()
                    require(rows.length() <= 20000) { "这张歌单过大，暂时无法载入" }
                    val loaded = (0 until rows.length()).map { neteaseId(rows.getJSONObject(it).optString("id")) }
                    synchronized(lists) {
                        lists[id] = PlaylistIds(System.nanoTime(), loaded)
                        while (lists.size > 4) lists.remove(lists.keys.first())
                    }
                    loaded
                }
                val page = ids.drop(offset).take(30)
                val result = if (page.isEmpty()) JSONObject().put("songs", JSONArray()) else
                    web.post("v3/song/detail", JSONObject().put("c", JSONArray().apply { page.forEach { put(JSONObject().put("id", it)) } }.toString()), cookie)
                val songs = result.optJSONArray("songs") ?: JSONArray()
                val privileges = privileges(result)
                val byId = (0 until songs.length()).map { songs.getJSONObject(it) }.associateBy { it.optString("id") }
                val tracks = JSONArray().apply { page.forEach { songId ->
                    put(byId[songId]?.let { track(it, privileges[songId]) } ?: JSONObject().put("id", songId).put("originalId", songId)
                        .put("title", "暂不可用的歌曲").put("artist", "").put("album", "").put("duration", 0).put("available", false))
                } }
                JSONObject().put("tracks", tracks).put("nextOffset", offset + page.size).put("more", offset + page.size < ids.size)
            }
            "search" -> {
                val query = data.getString("query").trim(); require(query.length in 1..100)
                val offset = data.optInt("offset"); require(offset in 0..20000)
                val root = web.post("cloudsearch/pc", JSONObject().put("s", query).put("type", 1).put("limit", 30).put("offset", offset).put("total", true), cookie)
                    .optJSONObject("result") ?: JSONObject()
                val rows = root.optJSONArray("songs") ?: JSONArray(); require(rows.length() <= 30)
                JSONObject().put("tracks", JSONArray().apply { for (i in 0 until rows.length()) { val row = rows.getJSONObject(i); put(track(row, row.optJSONObject("privilege"))) } })
                    .put("nextOffset", offset + rows.length()).put("more", offset + rows.length() < root.optInt("songCount"))
            }
            "lyrics" -> {
                val root = web.post("song/lyric", JSONObject().put("id", neteaseId(data.getString("id"))).put("lv", -1).put("tv", -1).put("rv", -1).put("kv", -1), cookie)
                JSONObject().put("original", root.optJSONObject("lrc")?.optString("lyric").orEmpty().take(500000))
                    .put("translated", root.optJSONObject("tlyric")?.optString("lyric").orEmpty().take(500000))
            }
            else -> error("不支持的网易云操作")
        }
    }
    /** Media3 loader thread resolves short-lived URLs only when audio is requested. */
    fun stream(id: String, duration: Long): String {
        val auth = credentials(); val cookie = neteaseCookies(auth.getString("cookie")); neteaseId(id)
        val data = JSONObject().put("ids", JSONArray().put(id).toString()).put("level", "exhigh").put("encodeType", "aac")
        var root = web.blockingPost("song/enhance/player/url/v1", data, cookie)
        val first = root.optJSONArray("data")?.optJSONObject(0)
        if (first != null && !hasTrial(first) && first.optString("url").let { it.isBlank() || it == "null" }) {
            root = web.blockingPost("song/enhance/player/url", JSONObject().put("ids", data.getString("ids")).put("br", 320000), cookie)
        }
        return streamUrl(root, id, duration)
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
        internal fun track(row: JSONObject, privilege: JSONObject? = null): JSONObject {
            val id = neteaseId(row.optString("id"))
            val album = row.optJSONObject("al") ?: row.optJSONObject("album")
            val artists = row.optJSONArray("ar") ?: row.optJSONArray("artists") ?: JSONArray()
            val artist = (0 until artists.length()).map { artists.getJSONObject(it).optString("name") }.filter { it.isNotBlank() }.joinToString(" / ")
            return JSONObject().put("id", id).put("originalId", id).put("title", row.optString("name").take(200)).put("artist", artist.take(200))
                .put("album", album?.optString("name").orEmpty().take(200)).put("cover", picture(album?.optString("picUrl").orEmpty()))
                .put("duration", row.optLong("dt", row.optLong("duration")).coerceIn(0, 86_400_000))
                .put("available", (privilege?.optInt("st", 0) ?: 0) >= 0)
        }
        private fun hasTrial(row: JSONObject) = !row.isNull("freeTrialInfo") && row.opt("freeTrialInfo")?.toString() != "null"
        internal fun streamUrl(root: JSONObject, id: String, duration: Long): String {
            val row = root.optJSONArray("data")?.optJSONObject(0) ?: error("这首歌暂时无法播放")
            require(row.optString("id") == neteaseId(id)) { "网易云返回了不同歌曲，请重试" }
            check(!hasTrial(row) && !(row.optLong("time") > 0 && duration > 0 && row.optLong("time") + 5000 < duration)) { "这首歌当前仅提供试听，无法完整播放" }
            val url = row.optString("url")
            check(row.optInt("code") == 200 && url.isNotBlank() && url != "null") { "当前账号无法播放这首歌，请检查会员、购买或地区权限" }
            return cloudMediaUrl(url)
        }
    }
}
