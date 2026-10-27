package com.longzhousz.ami.core

import java.net.URI
import java.util.Locale

data class CloudConnection(val baseUrl: String, val token: String) {
    companion object {
        fun parse(raw: String): CloudConnection {
            val uri = URI(raw.trim())
            val host = uri.host?.lowercase(Locale.ROOT) ?: error("请粘贴电脑连接端显示的完整连接地址")
            val privateHost = host == "localhost" || host == "127.0.0.1" || run {
                val parts = host.split('.')
                val numbers = parts.mapNotNull { it.toIntOrNull() }
                parts.size == 4 && numbers.size == 4 && parts == numbers.map { it.toString() } && numbers.all { it in 0..255 } &&
                    (numbers[0] == 10 || numbers.take(2) == listOf(192, 168) || numbers[0] == 172 && numbers[1] in 16..31)
            }
            require(uri.scheme == "https" || uri.scheme == "http" && privateHost) { "连接地址需使用 HTTPS 或可信局域网地址" }
            require(uri.userInfo == null && uri.rawQuery == null && uri.rawPath in listOf("", "/") && uri.port in -1..65535 && uri.port != 0) { "连接地址格式不正确" }
            val token = uri.rawFragment.orEmpty()
            require(token.matches(Regex("[A-Za-z0-9_-]{43}"))) { "连接地址缺少有效连接码，请完整复制" }
            return CloudConnection("${uri.scheme}://${uri.rawAuthority}", token)
        }
    }
}

data class CloudPlaylist(val id: String, val title: String, val cover: String?, val count: Int)
data class CloudTrack(val id: String, val originalId: String, val title: String, val artist: String,
    val album: String, val duration: Long, val cover: String?, val available: Boolean) {
    fun song() = Song(id = "netease:$id", uri = "ami-cloud://song/$id", folder = "netease-online", title = title,
        artist = artist, album = album, duration = duration, cover = cover, format = "网易云 · 在线", neteaseId = originalId,
        lyricsAvailable = true, lyricsVersion = "cloud:$id")
}
data class CloudTracks(val tracks: List<CloudTrack>, val nextOffset: Int, val more: Boolean)
fun Song.isCloudSong() = id.startsWith("netease:") && uri.startsWith("ami-cloud://song/")

/** Only official NetEase CDN URLs returned by the authenticated local bridge reach media loaders. */
fun cloudMediaUrl(raw: String, artwork: Boolean = false): String {
    val uri = URI(raw)
    val host = uri.host?.lowercase(Locale.ROOT) ?: error("网易云音频地址无效")
    require(uri.scheme in setOf("https", "http") && uri.userInfo == null && uri.port in setOf(-1, 443) &&
        if (artwork) host.matches(Regex("p[0-9]+\\.music\\.126\\.net")) else host.endsWith(".music.126.net")) { "网易云媒体地址无效" }
    require(raw.length <= 8192)
    return URI("https://$host${uri.rawPath}${uri.rawQuery?.let { "?$it" }.orEmpty()}").toASCIIString()
}
