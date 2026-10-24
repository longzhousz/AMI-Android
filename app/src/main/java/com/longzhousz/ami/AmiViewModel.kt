package com.longzhousz.ami

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.longzhousz.ami.core.Lyrics
import com.longzhousz.ami.core.NeteaseLyrics
import com.longzhousz.ami.core.Song
import com.longzhousz.ami.data.TranslationClient
import com.longzhousz.ami.data.TranslationModelCatalog
import com.longzhousz.ami.data.AgentBridge
import com.longzhousz.ami.data.LyricImporter
import android.net.Uri
import com.longzhousz.ami.core.albumsOf
import org.json.JSONObject
import com.longzhousz.ami.playback.PlayerConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AmiViewModel(app: Application) : AndroidViewModel(app) {
    val repository = (app as AmiApplication).repository
    val player = PlayerConnection(app)
    val theme = MutableStateFlow(repository.prefs.getString("theme", "system")!!)
    val message = MutableStateFlow<String?>(null)
    val translations = MutableStateFlow<List<String>?>(null)
    val translating = MutableStateFlow(false)
    val hasKey = MutableStateFlow(repository.secrets.configured())
    val bridge = AgentBridge(repository)
    val bridgeEnabled = MutableStateFlow(repository.prefs.getBoolean("bridgeEnabled", false))
    val bridgeAlbum = MutableStateFlow<String?>(null)
    val translationModels = TranslationModelCatalog(repository)
    val translationStyle = MutableStateFlow(repository.prefs.getString("style", TranslationClient.DEFAULT_STYLE)!!)
    private val client = TranslationClient(repository, translationModels)
    private var translationJob: Job? = null
    init {
        viewModelScope.launch {
            combine(player.state.map { it.id }.distinctUntilChanged(), repository.songs,
                translationModels.state.map { it.selection }.distinctUntilChanged(), translationStyle) { id, songs, selection, style ->
                Triple(songs.find { it.id == id }, selection, style)
            }.distinctUntilChanged().collectLatest { (song, _, _) ->
                    translationJob?.cancel(); translations.value = null; translating.value = false
                    if (song != null) {
                        val lines = Lyrics.parse(song.lyrics)
                        translations.value = client.cached(song, lines) ?: NeteaseLyrics.translations(lines, song.lyricTranslation)
                    }
                }
        }
    }
    fun act(name: String, block: () -> Unit) {
        try { block(); repository.scope.launch { repository.db.log(name, "user", "success") } }
        catch (_: Exception) { message.value = "操作未完成，播放服务可能正在连接，请稍后重试" }
    }
    fun play(songs: List<Song>, index: Int = 0) = act("player.play") { player.play(songs, index) }
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
    fun translate(song: Song, force: Boolean = false) {
        if (translating.value) return
        translationJob = viewModelScope.launch {
            translating.value = true
            try { translations.value = client.translate(song, Lyrics.parse(song.lyrics), force); message.value = "翻译已保存，可离线查看" }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message.value = e.message?.take(150) ?: "翻译失败，请检查网络" }
            finally {
                // A cancelled request may finish unwinding after the user starts another one.
                if (translationJob === coroutineContext[Job]) translating.value = false
            }
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
    fun sleep(minutes: Int) {
        repository.prefs.edit().putLong("sleepAt", if (minutes > 0) System.currentTimeMillis() + minutes * 60000L else 0).apply()
        message.value = if (minutes > 0) "$minutes 分钟后暂停播放" else "已关闭定时停止"
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
                val album = albumsOf(repository.songs.value).find { it.key == payload.getString("albumKey") } ?: error("Album missing")
                if (action == "album.play") player.play(album.songs) else bridgeAlbum.value = album.key
            }
            "album.reorderTracks" -> {
                val ids = payload.getJSONArray("songIds").let { array -> (0 until array.length()).map { array.getString(it) } }
                val album = albumsOf(repository.songs.value).find { it.key == payload.getString("albumKey") } ?: error("Album missing")
                require(ids.size == album.songs.size && ids.toSet() == album.songs.map { it.id }.toSet())
                withContext(Dispatchers.IO) { repository.reorder(ids) }
            }
            "lyrics.translateCurrent" -> {
                val song = repository.songs.value.find { it.id == player.state.value.id } ?: error("No song")
                translations.value = client.translate(song, Lyrics.parse(song.lyrics))
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
