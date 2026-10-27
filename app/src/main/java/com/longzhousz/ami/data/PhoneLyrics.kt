package com.longzhousz.ami.data

import com.longzhousz.ami.core.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Public lyric lookup only. No NetEase login, audio URL, proxy, CLI, or private key. */
class PhoneLyrics(private val repository: LibraryRepository, private val open: (String) -> HttpURLConnection = {
    URI("https://lrclib.net$it").toURL().openConnection() as HttpURLConnection
}) {
    private val closer = Dispatchers.IO.limitedParallelism(1)
    suspend fun load(song: Song): SongLyrics = withContext(Dispatchers.IO) {
        repository.db.phoneLyrics(song)?.let { return@withContext it }
        ensureActive()
        if (song.artist.isBlank() || song.duration <= 0) return@withContext SongLyrics()
        fun encoded(raw: String) = URLEncoder.encode(raw, "UTF-8")
        val base = "track_name=${encoded(song.title)}&artist_name=${encoded(song.artist)}"
        val exact = if (song.album.isNotBlank()) request("/api/get?$base&album_name=${encoded(song.album)}&duration=${song.duration/1000.0}")?.let(::JSONObject) else null
        val row = if (exact != null && matches(song, exact)) exact else {
            val rows = request("/api/search?$base")?.let(::JSONArray) ?: JSONArray()
            require(rows.length() <= 200)
            (0 until rows.length()).map { rows.getJSONObject(it) }.filter { matches(song,it) }
                .sortedByDescending { it.optString("albumName").equals(song.album,true) }.firstOrNull()
        }
        ensureActive()
        SongLyrics(row?.optString("syncedLyrics")?.takeUnless { it.isBlank() || it == "null" }
            ?: row?.optString("plainLyrics")?.takeUnless { it == "null" }.orEmpty(), revision = song.lyricsVersion)
    }
    private fun matches(song: Song, row: JSONObject) = phoneLyricsMatch(song,row.optString("trackName"),row.optString("artistName"),(row.optDouble("duration",0.0)*1000).toLong())
    private suspend fun request(path: String): String? = suspendCancellableCoroutine { continuation ->
        val connection = open(path)
        val worker = CoroutineScope(continuation.context).launch {
            try {
                connection.connectTimeout=6000;connection.readTimeout=10000;connection.instanceFollowRedirects=false
                connection.setRequestProperty("User-Agent","AMI/${com.longzhousz.ami.BuildConfig.VERSION_NAME}")
                connection.setRequestProperty("Accept","application/json")
                val code=connection.responseCode
                if (code==404) continuation.resume(null)
                else {
                    check(code in 200..299) { "歌词暂时无法获取" }
                    val content=connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                        val body=StringBuilder();val buffer=CharArray(4096)
                        while (true) { val count=reader.read(buffer);if(count<0)break;require(body.length+count<=500000);body.append(buffer,0,count) }
                        body.toString()
                    }
                    continuation.resume(content)
                }
            } catch (error: Exception) { continuation.resumeWithException(error) }
            finally { connection.disconnect() }
        }
        continuation.invokeOnCancellation { worker.cancel();closer.dispatch(EmptyCoroutineContext) { runCatching { connection.disconnect() } } }
    }
}
