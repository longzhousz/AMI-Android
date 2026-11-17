package com.longzhousz.ami.data

import com.longzhousz.ami.LibraryStateHolder
import com.longzhousz.ami.PlaybackStateHolder
import com.longzhousz.ami.core.CloudPlaylist
import com.longzhousz.ami.core.CloudTrack
import com.longzhousz.ami.core.ListeningPeriod
import com.longzhousz.ami.core.ListeningScale
import com.longzhousz.ami.core.neteaseId
import com.longzhousz.ami.playback.PlayMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.LocalDate

/** JSON boundary for Agent actions. No raw exception text, URL, path, credential or lyrics leave here. */
internal class AgentActions(
    private val library: LibraryStateHolder,
    private val playback: PlaybackStateHolder,
) {
    private val repository get() = library.repository
    private val player get() = playback.player

    suspend fun execute(
        action: String,
        payload: JSONObject,
    ): AgentBridge.ActionResult =
        when (action) {
            "player.pause" -> unit { player.pause() }
            "player.play" -> unit { player.resume() }
            "player.toggle" -> unit { player.toggle() }
            "player.next" -> unit { player.next() }
            "player.previous" -> unit { player.previous() }
            "player.seek" -> seek(payload)
            "player.jump" -> jump(payload)
            "player.mode" -> mode(payload)
            "player.stop" -> unit { player.stop() }
            "player.get" -> playerState()
            "queue.get" -> queueState()
            "queue.set" -> queueSet(payload)
            "queue.add" -> queueAdd(payload)
            "queue.remove" -> queueRemove(payload)
            "queue.clear" -> unit { playback.actForAgent("queue.clear") { player.clear() } }
            "queue.reorder" -> queueReorder(payload)
            "album.open", "album.play" -> album(action, payload)
            "album.reorderTracks" -> albumReorder(payload)
            "like.read" -> likeRead(payload)
            "like.set" -> likeSet(payload)
            "online.likes.read" -> cloudLikes()
            "online.library.read" -> onlineLibrary()
            "online.tracks.read" -> onlineTracks(payload)
            "online.search" -> onlineSearch(payload)
            "online.discover" -> onlineDiscover(payload)
            "online.library.refresh" -> unit { library.refreshBridgeOnlineLibrary() }
            "online.likes.refresh" -> unit { library.refreshBridgeCloudLikes() }
            "online.play" -> onlinePlay(payload)
            "online.prepare.cancel" -> unit { library.cancelBridgeCloudPreparation() }
            "lyrics.translateCurrent" -> translateCurrent(payload)
            "lyrics.clear" -> lyricsClear(payload)
            "lyrics.import" -> lyricsImport()
            "lyrics.models.read" -> modelsRead()
            "lyrics.models.refresh" -> unit { playback.refreshModels(true) }
            "lyrics.model.set" -> modelSet(payload)
            "lyrics.style.set" -> styleSet(payload)
            "library.scan" -> libraryScan(payload)
            "library.scan.cancel" -> unit { repository.cancelScan() }
            "library.remove" -> libraryRemove(payload)
            "library.roots.read" -> rootsRead()
            "library.roots.remove" -> rootsRemove(payload)
            "cache.getStats", "cache.cleanupDryRun" -> cacheStats()
            "cache.cleanupApply", "online.cache.cleanupApply" -> pendingCleanup()
            "stats.report" -> statsReport(payload)
            "stats.clear" -> library.clearBridgePending()
            "bridge.status" -> bridgeStatus()
            "agent.requests" -> requests()
            else -> AgentBridge.ActionResult(AgentBridge.ActionResult().status)
        }

    private inline fun unit(block: () -> Unit): AgentBridge.ActionResult {
        block()
        return AgentBridge.ActionResult()
    }

    private fun seek(payload: JSONObject): AgentBridge.ActionResult {
        val ms = payload.getLong("positionMs")
        require(ms >= 0 && ms <= player.state.value.duration)
        player.seek(ms)
        return AgentBridge.ActionResult()
    }

    private fun jump(payload: JSONObject): AgentBridge.ActionResult {
        val index = payload.getInt("index")
        require(index in player.state.value.queue.indices)
        playback.actForAgent("player.jump") { player.jump(index) }
        return AgentBridge.ActionResult()
    }

    private fun mode(payload: JSONObject): AgentBridge.ActionResult {
        val mode = PlayMode.valueOf(payload.getString("mode").replaceFirstChar { it.uppercase() })
        playback.actForAgent("player.mode") { player.mode(mode) }
        return AgentBridge.ActionResult()
    }

    private fun playerState(): AgentBridge.ActionResult {
        val state = player.state.value
        val mod = PlayMode.from(state.shuffle, state.repeat).name.lowercase()
        return result(
            JSONObject()
                .put("playing", state.playing)
                .put("buffering", state.buffering)
                .put("songId", state.id)
                .put("positionMs", state.position)
                .put("durationMs", state.duration)
                .put("mode", mod)
                .put("queue", JSONArray(state.queue)),
        )
    }

    private fun queueState() =
        result(
            JSONObject()
                .put("songIds", JSONArray(player.state.value.queue))
                .put(
                    "currentIndex",
                    player.state.value.queue
                        .indexOf(player.state.value.id),
                ),
        )

    private suspend fun queueSet(payload: JSONObject): AgentBridge.ActionResult {
        check(repository.cloud.radio.value == null) { "私人 FM 队列不可编排" }
        val ids = songIds(payload)
        val index = payload.getInt("index")
        require(index in ids.indices)
        val songs = ids.map(::resolveSong)
        playback.actForAgent("player.play") { player.play(songs, index) }
        return AgentBridge.ActionResult()
    }

    private fun queueAdd(payload: JSONObject): AgentBridge.ActionResult {
        check(repository.cloud.radio.value == null) { "私人 FM 队列不可编排" }
        val songs = songIds(payload).map(::resolveSong)
        val index = if (payload.has("index")) payload.getInt("index") else player.state.value.queue.size
        playback.actForAgent("queue.add") { player.add(songs, index) }
        return AgentBridge.ActionResult()
    }

    private fun queueRemove(payload: JSONObject): AgentBridge.ActionResult {
        check(repository.cloud.radio.value == null) { "私人 FM 队列不可编排" }
        val ids = songIds(payload).toSet()
        val queue = player.state.value.queue
        require(ids.all { it in queue })
        playback.actForAgent("queue.remove") { ids.forEach(player::remove) }
        return AgentBridge.ActionResult()
    }

    private fun queueReorder(payload: JSONObject): AgentBridge.ActionResult {
        check(repository.cloud.radio.value == null) { "私人 FM 队列不可编排" }
        val ids = songIds(payload)
        playback.actForAgent("queue.reorder") { player.reorder(ids) }
        return AgentBridge.ActionResult()
    }

    private suspend fun album(
        action: String,
        payload: JSONObject,
    ): AgentBridge.ActionResult {
        val album = repository.library.value.albumsByKey[payload.getString("albumKey")] ?: error("Album missing")
        if (action == "album.play") playback.actForAgent("player.play") { player.play(album.songs) } else library.openBridgeAlbum(album.key)
        return AgentBridge.ActionResult()
    }

    private suspend fun albumReorder(payload: JSONObject): AgentBridge.ActionResult {
        val ids = payload.getJSONArray("songIds").strings()
        val album = repository.library.value.albumsByKey[payload.getString("albumKey")] ?: error("Album missing")
        require(ids.size == album.songs.size && ids.toSet() == album.songs.map { it.id }.toSet())
        withContext(Dispatchers.IO) { repository.reorder(ids) }
        return AgentBridge.ActionResult()
    }

    private suspend fun likeRead(payload: JSONObject): AgentBridge.ActionResult {
        val id = payload.getString("songId")
        return if (payload.optString("target") == "cloud") {
            cloudLikes()
        } else {
            result(
                JSONObject()
                    .put(
                        "liked",
                        repository.library.value.songsById[id]
                            ?.favorite == true,
                    ).put("ready", true),
            )
        }
    }

    private suspend fun likeSet(payload: JSONObject): AgentBridge.ActionResult {
        val id = payload.getString("songId")
        val liked = payload.getBoolean("liked")
        if (payload.optString("target") == "cloud") {
            check(repository.cloud.direct && repository.cloud.configured.value) { "请先登录网易云音乐" }
            val likes = repository.cloud.likes.value
            check(likes.ready && id !in likes.pending) { "红心状态正在同步" }
            val originalId =
                repository.cloud.catalog.value[id]
                    ?.neteaseId ?: id.removePrefix("netease:")
            val session = likes.session
            repository.cloud.setLike(originalId, liked, session)
            library.refreshBridgeCloudLikes()
        } else {
            val song = repository.library.value.songsById[id] ?: error("Song missing")
            withContext(Dispatchers.IO) { repository.setFavorite(song.id, liked) }
        }
        return AgentBridge.ActionResult()
    }

    private fun cloudLikes(): AgentBridge.ActionResult {
        val likes = repository.cloud.likes.value
        return result(JSONObject().put("ready", likes.ready).put("revision", repository.cloud.likesRevision.value).put("ids", JSONArray(likes.ids)))
    }

    private suspend fun onlineLibrary(): AgentBridge.ActionResult {
        check(repository.cloud.direct && repository.cloud.configured.value) { "请先登录网易云音乐" }
        val library = repository.cloud.library()
        return result(
            JSONObject()
                .put("collections", JSONArray(library.collections.map(::collectionJson)))
                .put("liked", library.liked?.let(::collectionJson)),
        )
    }

    private fun collectionJson(collection: CloudPlaylist) =
        JSONObject()
            .put("id", collection.id)
            .put("title", collection.title)
            .put("kind", collection.kind)
            .put("count", collection.count)
            .apply { if (collection.artist.isNotBlank()) put("artist", collection.artist) }

    private suspend fun onlineTracks(payload: JSONObject): AgentBridge.ActionResult {
        check(repository.cloud.direct && repository.cloud.configured.value) { "请先登录网易云音乐" }
        val id = neteaseId(payload.getString("id"))
        val kind = payload.optString("kind").ifBlank { "playlist" }
        require(kind in setOf("album", "playlist", "created", "liked", "collected"))
        val offset = payload.optInt("offset", 0)
        require(offset in 0..20_000)
        val page =
            repository.cloud.tracks(
                CloudPlaylist(id, "", null, 0, if (kind == "album") "album" else "playlist"),
                offset,
            )
        return result(tracksJson(page))
    }

    private suspend fun onlineSearch(payload: JSONObject): AgentBridge.ActionResult {
        val query = payload.getString("query")
        val offset = payload.optInt("offset", 0).coerceIn(0, 10_000)
        val page = repository.cloud.search(query, offset)
        repository.cloud.savePage(false, "search:${query.trim()}:$offset", page)
        return result(tracksJson(page))
    }

    private suspend fun onlineDiscover(payload: JSONObject): AgentBridge.ActionResult {
        val kind = payload.getString("kind")
        val tracks = repository.cloud.discovery(kind)
        repository.cloud.savePage(
            false,
            "discover:$kind",
            com.longzhousz.ami.core
                .CloudTracks(tracks, 0, false),
        )
        return result(
            tracksJson(
                com.longzhousz.ami.core
                    .CloudTracks(tracks, 0, false),
            ),
        )
    }

    private fun tracksJson(page: com.longzhousz.ami.core.CloudTracks) =
        JSONObject()
            .put(
                "tracks",
                JSONArray(
                    page.tracks.map { track ->
                        JSONObject()
                            .put("id", track.id)
                            .put("originalId", track.originalId)
                            .put("title", track.title)
                            .put("artist", track.artist)
                            .put("album", track.album)
                            .put("durationMs", track.duration)
                            .put("available", track.available)
                            .apply {
                                track.primaryArtistId?.let { put("primaryArtistId", it) }
                                track.primaryArtistName?.let { put("primaryArtistName", it) }
                                track.albumId?.let { put("albumId", it) }
                            }
                    },
                ),
            ).put("nextOffset", page.nextOffset)
            .put("more", page.more)

    private fun onlinePlay(payload: JSONObject): AgentBridge.ActionResult {
        val rows = payload.getJSONArray("tracks")
        val tracks =
            (0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                CloudTrack(
                    id = row.getString("id"),
                    originalId = row.getString("originalId"),
                    title = row.getString("title"),
                    artist = row.getString("artist"),
                    album = row.getString("album"),
                    duration = row.getLong("durationMs"),
                    cover = null,
                    available = row.getBoolean("available"),
                    primaryArtistId = row.optString("primaryArtistId").takeIf { it.isNotBlank() },
                    primaryArtistName = row.optString("primaryArtistName").takeIf { it.isNotBlank() },
                    albumId = row.optString("albumId").takeIf { it.isNotBlank() },
                )
            }
        require(tracks.isNotEmpty() && tracks.size <= 1000)
        val index = payload.getInt("index")
        require(index in tracks.indices)
        library.playBridgeCloud(tracks, index)
        return AgentBridge.ActionResult()
    }

    private suspend fun translateCurrent(payload: JSONObject): AgentBridge.ActionResult {
        val song = playback.findSong(player.state.value.id) ?: error("No song")
        val lines = playback.translateForBridge(song, payload.optBoolean("force", false))
        return result(JSONObject().put("lines", lines.size))
    }

    private suspend fun lyricsClear(payload: JSONObject): AgentBridge.ActionResult {
        val song = repository.library.value.songsById[payload.getString("songId")] ?: error("Song missing")
        withContext(Dispatchers.IO) {
            repository.db.clearLyrics(song.id)
            repository.reload()
        }
        return AgentBridge.ActionResult()
    }

    private suspend fun lyricsImport(): AgentBridge.ActionResult {
        val roots = repository.lyricRoots()
        check(roots.isNotEmpty()) { "请先设置歌词文件夹" }
        withContext(Dispatchers.IO) {
            com.longzhousz.ami.data
                .LyricImporter(library.contextForAgent, repository)
                .folders(roots)
        }
        return AgentBridge.ActionResult()
    }

    private fun modelsRead(): AgentBridge.ActionResult {
        val state = playback.translationModels.state.value
        return result(
            JSONObject()
                .put("selection", state.selection)
                .put("models", JSONArray(state.models.map { it.id }))
                .put("automaticUpdates", state.automaticUpdates),
        )
    }

    private fun modelSet(payload: JSONObject): AgentBridge.ActionResult {
        playback.selectTranslationModel(payload.getString("selection"))
        if (payload.has("automaticUpdates")) playback.setAutomaticModelUpdates(payload.getBoolean("automaticUpdates"))
        return AgentBridge.ActionResult()
    }

    private fun styleSet(payload: JSONObject): AgentBridge.ActionResult {
        val style = payload.getString("style")
        require(style.length <= 2000)
        playback.setTranslationStyle(style)
        return AgentBridge.ActionResult()
    }

    private fun libraryScan(payload: JSONObject): AgentBridge.ActionResult {
        check(repository.roots().isNotEmpty() || repository.lyricRoots().isNotEmpty()) { "请先设置音乐文件夹" }
        check(!repository.importState.value.running) { "导入正在进行" }
        repository.scan(payload.optBoolean("afterCurrent", false))
        return AgentBridge.ActionResult()
    }

    private suspend fun libraryRemove(payload: JSONObject): AgentBridge.ActionResult {
        val song = repository.library.value.songsById[payload.getString("songId")] ?: error("Song missing")
        player.remove(song.id)
        withContext(Dispatchers.IO) { repository.remove(song) }
        return AgentBridge.ActionResult()
    }

    private fun rootsRead(): AgentBridge.ActionResult {
        val music = repository.roots().map { JSONObject().put("id", digest(it)).put("scope", "music") }
        val lyrics = repository.lyricRoots().map { JSONObject().put("id", digest(it)).put("scope", "lyric") }
        return result(JSONObject().put("roots", JSONArray(music + lyrics)))
    }

    private fun rootsRemove(payload: JSONObject): AgentBridge.ActionResult {
        val scope = payload.getString("scope")
        val id = payload.getString("id")
        require(scope in setOf("music", "lyric"))
        val roots = if (scope == "music") repository.roots() else repository.lyricRoots()
        val target = roots.firstOrNull { digest(it) == id } ?: error("Root missing")
        if (scope == "music") repository.forgetRoot(target) else repository.forgetLyricRoot(target)
        return AgentBridge.ActionResult()
    }

    private suspend fun cacheStats(): AgentBridge.ActionResult =
        withContext(Dispatchers.IO) {
            val stats = repository.cacheInfo()
            result(JSONObject().put("bytes", stats.bytes).put("files", stats.files).put("reclaimableBytes", stats.reclaimable))
        }

    private suspend fun statsReport(payload: JSONObject): AgentBridge.ActionResult {
        val scale = ListeningScale.valueOf(payload.getString("scale").uppercase())
        val anchor = LocalDate.parse(payload.getString("anchor"))
        val report = repository.listeningStats.report(ListeningPeriod(scale, anchor))
        val ranks = { items: List<com.longzhousz.ami.core.ListeningRank> ->
            JSONArray(items.take(10).map { item -> JSONObject().put("title", item.title).put("millis", item.millis) })
        }
        return result(
            JSONObject()
                .put("totalMillis", report.totalMillis)
                .put("songs", report.songs)
                .put("activeDays", report.activeDays)
                .put("longestStreak", report.longestStreak)
                .put("topSongs", ranks(report.topSongs))
                .put("artists", ranks(report.artists))
                .put("albums", ranks(report.albums)),
        )
    }

    private fun requests(): AgentBridge.ActionResult =
        result(
            JSONObject().put(
                "requests",
                JSONArray(
                    library.bridgeRequests().map { request ->
                        JSONObject()
                            .put("requestId", request.requestId)
                            .put("action", request.action)
                            .put("state", request.state)
                            .put(
                                "createdAt",
                                java.time.Instant
                                    .ofEpochMilli(request.createdAt)
                                    .toString(),
                            ).put("bytes", request.bytes)
                    },
                ),
            ),
        )

    private fun bridgeStatus(): AgentBridge.ActionResult {
        val permissions = JSONObject()
        AgentProtocol.permissionDefaults.keys.forEach { key -> permissions.put(key, repository.prefs.getBoolean(key, AgentProtocol.permissionDefaults.getValue(key))) }
        return result(
            JSONObject()
                .put("enabled", repository.prefs.getBoolean("bridgeEnabled", false))
                .put("tokenConfigured", library.bridgeTokenConfigured())
                .put("folderConfigured", !repository.prefs.getString("bridgeFolder", null).isNullOrBlank())
                .put("permissions", permissions),
        )
    }

    private fun resolveSong(id: String) = playback.findSong(id) ?: error("Song missing")

    private fun songIds(payload: JSONObject): List<String> {
        val array = payload.getJSONArray("songIds")
        val ids = array.strings()
        require(ids.isNotEmpty() && ids.size <= 1000 && ids.toSet().size == ids.size)
        return ids
    }

    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun result(data: JSONObject) = AgentBridge.ActionResult(data = data)

    private suspend fun pendingCleanup(): AgentBridge.ActionResult = library.cleanBridgePending()
}
