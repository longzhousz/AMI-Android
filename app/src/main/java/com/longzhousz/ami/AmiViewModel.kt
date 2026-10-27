package com.longzhousz.ami

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.longzhousz.ami.core.Song
import com.longzhousz.ami.core.CurrentLyrics
import com.longzhousz.ami.core.CloudTrack
import com.longzhousz.ami.core.isCloudSong
import com.longzhousz.ami.data.TranslationClient
import com.longzhousz.ami.data.TranslationModelCatalog
import com.longzhousz.ami.data.AgentBridge
import com.longzhousz.ami.data.LyricImporter
import android.net.Uri
import org.json.JSONObject
import com.longzhousz.ami.playback.PlayerConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AmiViewModel internal constructor(app: Application,
    clientFactory: (com.longzhousz.ami.data.LibraryRepository, TranslationModelCatalog) -> TranslationClient,
    catalogFactory: (com.longzhousz.ami.data.LibraryRepository) -> TranslationModelCatalog = { TranslationModelCatalog(it) },
) : AndroidViewModel(app) {
    constructor(app: Application) : this(app, { repository, models -> TranslationClient(repository, models) })
    val repository = (app as AmiApplication).repository
    val cloud = repository.cloud
    private val songSources = combine(repository.library, cloud.catalog) { library, online -> library to online }
        .stateIn(viewModelScope, SharingStarted.Eagerly, repository.library.value to cloud.catalog.value)
    fun findSong(id: String?) = repository.library.value.songsById[id] ?: cloud.catalog.value[id]
    private suspend fun loadLyrics(song: Song) = if (song.isCloudSong()) cloud.lyrics(song) else repository.lyrics.load(song)
    private var cloudPlayJob: kotlinx.coroutines.Job? = null
    val player = PlayerConnection(app)
    val theme = MutableStateFlow(repository.prefs.getString("theme", "system")!!)
    val message = MutableStateFlow<String?>(null)
    val translations = MutableStateFlow<List<String>?>(null)
    val translating = MutableStateFlow(false)
    val hasKey = MutableStateFlow(repository.secrets.configured())
    val bridge = AgentBridge(repository)
    val bridgeEnabled = MutableStateFlow(repository.prefs.getBoolean("bridgeEnabled", false))
    val bridgeAlbum = MutableStateFlow<String?>(null)
    val translationModels = catalogFactory(repository)
    val translationStyle = MutableStateFlow(repository.prefs.getString("style", TranslationClient.DEFAULT_STYLE)!!)
    private val client = clientFactory(repository, translationModels)
    val lyrics = MutableStateFlow(CurrentLyrics())
    private val uiActive = MutableStateFlow(true)
    private var translationJob: Deferred<List<String>>? = null
    private var translationEpoch = 0L
    private data class LyricsKey(val id: String?, val revision: String, val model: String, val style: String)
    private var stateKey: LyricsKey? = null
    private var translationKey: LyricsKey? = null
    private data class LyricsRequest(val song: Song?, val next: Song?, val key: LyricsKey, val active: Boolean)
    private fun currentKey(): LyricsKey {
        val song = findSong(player.state.value.id)
        return LyricsKey(song?.id,song?.lyricsVersion.orEmpty(),translationModels.state.value.selection,translationStyle.value)
    }
    private fun publish(value: CurrentLyrics) {
        lyrics.value=value;translations.value=value.translation;translating.value=value.translating
    }
    fun setUiActive(active: Boolean) {
        uiActive.value=active
        if (!active) {
            repository.lyrics.trim()
            repository.trimCloud()
            if (lyrics.value.document?.original?.isEmpty()==true && findSong(player.state.value.id)?.hasLyrics==true)
                publish(lyrics.value.copy(document=null))
        }
    }
    init {
        repository.load()
        viewModelScope.launch {
            combine(player.state.map { it.id to it.nextId }.distinctUntilChanged(), songSources,
                translationModels.state.map { it.selection }.distinctUntilChanged(), translationStyle, uiActive) { playback, library, selection, style, active ->
                val song=library.first.songsById[playback.first] ?: library.second[playback.first]
                val next=library.first.songsById[playback.second] ?: library.second[playback.second]
                LyricsRequest(song,next,LyricsKey(song?.id,song?.lyricsVersion.orEmpty(),selection,style),active)
            }.distinctUntilChanged().collectLatest { request ->
                val key=request.key
                if (stateKey!=key) {
                    stateKey=key;translationEpoch++;translationJob?.cancel()
                    publish(CurrentLyrics(key.id,key.revision))
                }
                val song=request.song
                if (!request.active || song==null) return@collectLatest
                try {
                    if (lyrics.value.document==null) {
                        val epoch=translationEpoch
                        val document=loadLyrics(song)
                        if (!uiActive.value || currentKey()!=key || document.revision!=key.revision) return@collectLatest
                        val cached=if (epoch==translationEpoch && document.lines.isNotEmpty()) client.cached(song,document.lines,key.model,key.style) else null
                        if (!uiActive.value || currentKey()!=key) return@collectLatest
                        if (epoch==translationEpoch) publish(CurrentLyrics(song.id,key.revision,document,cached ?: document.embeddedTranslation))
                        else publish(lyrics.value.copy(document=document))
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    if (currentKey()==key) {
                        publish(CurrentLyrics(song.id,key.revision,com.longzhousz.ami.core.SongLyrics(revision=key.revision)))
                        message.value="歌词暂时无法读取，音乐仍可播放"
                    }
                    return@collectLatest
                }
                // Only one neighbor, within a byte budget, and cancelled as soon as the UI stops or the track changes.
                try { if (uiActive.value) request.next?.takeIf { it.id!=song.id && !it.isCloudSong() }?.let { repository.lyrics.load(it) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* A failed prefetch must not interrupt the current song. */ }
            }
        }
    }
    fun act(name: String, block: () -> Unit) {
        try { block(); repository.scope.launch { repository.db.log(name, "user", "success") } }
        catch (_: Exception) { message.value = "操作未完成，播放服务可能正在连接，请稍后重试" }
    }
    fun cancelCloudPreparation() { cloudPlayJob?.cancel() }
    fun play(songs: List<Song>, index: Int = 0) {
        cancelCloudPreparation()
        act("player.play") { player.play(songs, index) }
    }
    fun playCloud(tracks: List<CloudTrack>, index: Int) {
        if (tracks.getOrNull(index)?.available != true) { message.value = "这首歌暂不支持在本试用版内播放，可在网易云音乐中收听"; return }
        val chosen = tracks[index].id
        val available = tracks.filter { it.available }
        val start = (available.indexOfFirst { it.id == chosen } - 500).coerceIn(0, (available.size - 1000).coerceAtLeast(0))
        val queue = available.drop(start).take(1000)
        cloudPlayJob?.cancel()
        cloudPlayJob = viewModelScope.launch {
            try { cloud.rememberQueue(queue); ensureActive(); act("player.play") { player.play(queue.map { it.song() }, queue.indexOfFirst { it.id == chosen }) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message.value = "在线队列暂时无法保存，请稍后重试" }
        }
    }
    private fun persistAction(name: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { block(); repository.db.log(name, "user", "success") } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { message.value = "保存失败，请检查设备存储空间后重试" }
        }
    }
    fun favorite(song: Song) = persistAction("library.favorite") { repository.favorite(song) }
    fun reorder(ids: List<String>) = persistAction("album.reorderTracks") { repository.reorder(ids) }
    fun importLyrics(songId: String, uri: Uri) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { LyricImporter(getApplication(), repository).attach(songId, uri) }
                message.value = "歌词已关联到这首歌，重新扫描后仍会保留"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message.value = e.message?.take(120) ?: "歌词导入失败" }
        }
    }
    fun clearLyrics(song: Song) = persistAction("lyrics.restoreAutomatic") { repository.db.clearLyrics(song.id); repository.reload() }
    fun remove(song: Song) {
        player.remove(song.id)
        persistAction("library.remove") { repository.remove(song) }
    }
    fun setTheme(value: String) { theme.value = value; repository.prefs.edit().putString("theme", value).apply() }
    private fun startTranslation(song: Song, force: Boolean): Deferred<List<String>>? {
        val key=currentKey()
        if (key.id!=song.id) return null
        translationJob?.takeIf { it.isActive && translationKey==key }?.let { return it }
        translationJob?.cancel()
        val epoch=++translationEpoch
        stateKey=key;translationKey=key
        val current=lyrics.value.takeIf { it.songId==song.id && it.revision==key.revision } ?: CurrentLyrics(song.id,key.revision)
        publish(current.copy(translating=true))
        val task=viewModelScope.async(start=CoroutineStart.LAZY) {
            try {
                val summary=findSong(song.id) ?: throw CancellationException("Song removed")
                val document=loadLyrics(summary)
                ensureActive()
                if (currentKey()!=key || document.revision!=key.revision) throw CancellationException("Lyrics changed")
                val result=client.translate(summary.copy(lyrics=document.original,lyricTranslation=document.translated),document.lines,force)
                ensureActive()
                if (translationEpoch==epoch && currentKey()==key) {
                    publish(CurrentLyrics(song.id,key.revision,document,result))
                    message.value="翻译已保存，可离线查看"
                }
                result
            } finally {
                if (translationEpoch==epoch && currentKey()==key) publish(lyrics.value.copy(translating=false))
            }
        }
        translationJob=task;task.start()
        return task
    }
    fun translate(song: Song, force: Boolean = false) {
        if (currentKey().id!=song.id) return
        val key=currentKey()
        val task=startTranslation(song,force) ?: return
        viewModelScope.launch {
            try { task.await() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (currentKey()==key) message.value=e.message?.take(150) ?: "翻译失败，请检查网络" }
        }
    }
    fun refreshModels(force: Boolean = false) {
        viewModelScope.launch {
            val state = translationModels.refresh(force)
            if (force) message.value = state.error ?: "模型预设已更新"
        }
    }
    fun selectTranslationModel(selection: String) { translationModels.select(selection) }
    fun setAutomaticModelUpdates(enabled: Boolean) {
        translationModels.setAutomaticUpdates(enabled)
        if (enabled) refreshModels()
    }
    fun setTranslationStyle(style: String) {
        repository.prefs.edit().putString("style", style).apply()
        translationStyle.value = style
    }
    fun saveKey(value: String) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { repository.secrets.save(value) } }
                .onSuccess {
                    hasKey.value = repository.secrets.configured()
                    message.value = if (value.isBlank()) "API Key 已删除" else "API Key 已加密保存"
                    if (hasKey.value && translationModels.state.value.automaticUpdates) refreshModels(force = true)
                }
                .onFailure { message.value = "密钥保存失败，请重试" }
        }
    }
    fun testConnection() {
        viewModelScope.launch {
            message.value = "正在测试连接…"
            runCatching { client.testConnection() }.onSuccess { message.value = "DeepSeek 连接正常" }
                .onFailure { message.value = it.message?.take(150) ?: "连接失败" }
        }
    }
    suspend fun processBridge() = bridge.process { action, payload ->
        when (action) {
            "player.pause" -> player.pause()
            "player.play" -> player.resume()
            "player.toggle" -> player.toggle()
            "player.next" -> player.next()
            "player.previous" -> player.previous()
            "player.seek" -> { val ms = payload.getLong("positionMs"); require(ms >= 0 && ms <= player.state.value.duration); player.seek(ms) }
            "album.open", "album.play" -> {
                val album = repository.library.value.albumsByKey[payload.getString("albumKey")] ?: error("Album missing")
                if (action == "album.play") player.play(album.songs) else bridgeAlbum.value = album.key
            }
            "album.reorderTracks" -> {
                val ids = payload.getJSONArray("songIds").let { array -> (0 until array.length()).map { array.getString(it) } }
                val album = repository.library.value.albumsByKey[payload.getString("albumKey")] ?: error("Album missing")
                require(ids.size == album.songs.size && ids.toSet() == album.songs.map { it.id }.toSet())
                withContext(Dispatchers.IO) { repository.reorder(ids) }
            }
            "lyrics.translateCurrent" -> {
                val song = repository.songs.value.find { it.id == player.state.value.id } ?: error("No song")
                (startTranslation(song,false) ?: error("当前歌曲已经切换")).await()
            }
            "cache.getStats", "cache.cleanupDryRun" -> {
                val stats = withContext(Dispatchers.IO) { repository.cacheInfo() }
                return@process AgentBridge.ActionResult(data = JSONObject().put("bytes", stats.bytes).put("files", stats.files).put("reclaimableBytes", stats.reclaimable))
            }
            else -> return@process AgentBridge.ActionResult("blocked")
        }
        AgentBridge.ActionResult()
    }
    override fun onCleared() { player.release(); super.onCleared() }
}
