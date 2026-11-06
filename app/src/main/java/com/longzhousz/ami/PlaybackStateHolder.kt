package com.longzhousz.ami

import android.app.Application
import com.longzhousz.ami.core.CurrentLyrics
import com.longzhousz.ami.core.ErrorCode
import com.longzhousz.ami.core.Song
import com.longzhousz.ami.core.isCloudSong
import com.longzhousz.ami.data.AppDiagnostics
import com.longzhousz.ami.data.LibraryRepository
import com.longzhousz.ami.data.TranslationClient
import com.longzhousz.ami.data.TranslationModelCatalog
import com.longzhousz.ami.playback.PlayerConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlaybackStateHolder internal constructor(
    private val app: Application,
    private val repository: LibraryRepository,
    private val scope: CoroutineScope,
    private val message: (String?) -> Unit,
    clientFactory: (LibraryRepository, TranslationModelCatalog) -> TranslationClient,
    catalogFactory: (LibraryRepository) -> TranslationModelCatalog,
) {
    val cloud = repository.cloud
    val player = PlayerConnection(app)
    private val _playerSelection = MutableStateFlow(0)
    val playerSelection = _playerSelection.asStateFlow()
    private val _translations = MutableStateFlow<List<String>?>(null)
    val translations = _translations.asStateFlow()
    private val _translating = MutableStateFlow(false)
    val translating = _translating.asStateFlow()
    private val _hasKey = MutableStateFlow(repository.secrets.configured())
    val hasKey = _hasKey.asStateFlow()
    val translationModels = catalogFactory(repository)
    private val _translationStyle = MutableStateFlow(repository.prefs.getString("style", TranslationClient.DEFAULT_STYLE)!!)
    val translationStyle = _translationStyle.asStateFlow()
    private val client = clientFactory(repository, translationModels)
    private val _lyrics = MutableStateFlow(CurrentLyrics())
    val lyrics = _lyrics.asStateFlow()
    private val uiActive = MutableStateFlow(true)
    private val restoreRevision = MutableStateFlow(0)
    private var translationJob: Deferred<List<String>>? = null
    private var translationEpoch = 0L
    private var stateKey: LyricsKey? = null
    private var translationKey: LyricsKey? = null
    private val songSources =
        combine(repository.library, cloud.catalog) { library, online -> library to online }
            .stateIn(scope, SharingStarted.Eagerly, repository.library.value to cloud.catalog.value)
    val currentSong: StateFlow<Song?> =
        combine(player.state.map { it.id }.distinctUntilChanged(), repository.library, cloud.catalog) { id, local, online ->
            local.songsById[id] ?: online[id]
        }.distinctUntilChanged().stateIn(scope, SharingStarted.WhileSubscribed(5000), null)

    private data class LyricsKey(
        val id: String?,
        val revision: String,
        val model: String,
        val style: String,
    )

    private data class LyricsRequest(
        val song: Song?,
        val next: Song?,
        val key: LyricsKey,
        val active: Boolean,
        val prefetch: Boolean,
        val restoreRevision: Int = 0,
    )

    fun findSong(id: String?) = repository.library.value.songsById[id] ?: cloud.catalog.value[id]

    private suspend fun loadLyrics(song: Song) = if (song.isCloudSong()) cloud.lyrics(song) else repository.lyrics.load(song)

    private fun currentKey(): LyricsKey {
        val song = findSong(player.state.value.id)
        return LyricsKey(song?.id, song?.lyricsVersion.orEmpty(), translationModels.state.value.selection, _translationStyle.value)
    }

    private fun publish(value: CurrentLyrics) {
        _lyrics.value = value
        _translations.value = value.translation
        _translating.value = value.translating
    }

    fun setUiActive(active: Boolean) {
        uiActive.value = active
        repository.setUiActive(active)
        if (!active) {
            repository.lyrics.trim()
            repository.trimCloud()
            if (_lyrics.value.document
                    ?.original
                    ?.isEmpty() == true && findSong(player.state.value.id)?.hasLyrics == true
            ) {
                publish(_lyrics.value.copy(document = null))
            }
        }
    }

    fun act(
        name: String,
        block: () -> Unit,
    ) {
        try {
            block()
            if (name == "player.play" || name == "player.jump") _playerSelection.value++
            repository.scope.launch { repository.db.log(name, "user", "success") }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            AppDiagnostics.errors.record(ErrorCode.PLAYER_COMMAND, failure)
            message("操作未完成，播放服务可能正在连接，请稍后重试")
        }
    }

    private fun startTranslation(
        song: Song,
        force: Boolean,
    ): Deferred<List<String>>? {
        val key = currentKey()
        if (key.id != song.id) return null
        translationJob?.takeIf { it.isActive && translationKey == key }?.let { return it }
        translationJob?.cancel()
        val epoch = ++translationEpoch
        stateKey = key
        translationKey = key
        val current = _lyrics.value.takeIf { it.songId == song.id && it.revision == key.revision } ?: CurrentLyrics(song.id, key.revision)
        publish(current.copy(translating = true))
        val task =
            scope.async(start = CoroutineStart.LAZY) {
                try {
                    val summary = findSong(song.id) ?: throw CancellationException("Song removed")
                    val document = loadLyrics(summary)
                    ensureActive()
                    if (currentKey() != key || document.revision != key.revision) throw CancellationException("Lyrics changed")
                    val result = client.translate(summary.copy(lyrics = document.original, lyricTranslation = document.translated), document.lines, force)
                    ensureActive()
                    if (translationEpoch == epoch && currentKey() == key) {
                        publish(CurrentLyrics(song.id, key.revision, document, result))
                        message("翻译已保存，可离线查看")
                    }
                    result
                } finally {
                    if (translationEpoch == epoch && currentKey() == key) publish(_lyrics.value.copy(translating = false))
                }
            }
        translationJob = task
        task.start()
        return task
    }

    fun translate(
        song: Song,
        force: Boolean = false,
    ) {
        if (currentKey().id != song.id) return
        val key = currentKey()
        val task = startTranslation(song, force) ?: return
        scope.launch {
            try {
                task.await()
            } catch (
                e: CancellationException,
            ) {
                throw e
            } catch (e: Exception) {
                AppDiagnostics.errors.record(ErrorCode.TRANSLATION, e)
                if (currentKey() == key) message("翻译未完成，请检查密钥与网络后重试")
            }
        }
    }

    fun refreshModels(force: Boolean = false) {
        scope.launch {
            val state = translationModels.refresh(force)
            if (force) message(state.error ?: "模型预设已更新")
        }
    }

    fun selectTranslationModel(selection: String) {
        translationModels.select(selection)
    }

    fun setAutomaticModelUpdates(enabled: Boolean) {
        translationModels.setAutomaticUpdates(enabled)
        if (enabled) refreshModels()
    }

    fun setTranslationStyle(style: String) {
        repository.prefs
            .edit()
            .putString("style", style)
            .apply()
        _translationStyle.value = style
    }

    fun saveKey(value: String) {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { repository.secrets.save(value) } }
                .onSuccess {
                    _hasKey.value = repository.secrets.configured()
                    message(if (value.isBlank()) "API Key 已删除" else "API Key 已加密保存")
                    if (_hasKey.value && translationModels.state.value.automaticUpdates) refreshModels(force = true)
                }.onFailure {
                    if (it is CancellationException) throw it
                    AppDiagnostics.errors.record(ErrorCode.TRANSLATION_KEY, it)
                    message("密钥保存失败，请重试")
                }
        }
    }

    fun testConnection() {
        scope.launch {
            message("正在测试连接…")
            runCatching { client.testConnection() }
                .onSuccess { message("DeepSeek 连接正常") }
                .onFailure {
                    if (it is CancellationException) throw it
                    AppDiagnostics.errors.record(ErrorCode.TRANSLATION_CONNECTION, it)
                    message("连接未完成，请检查密钥与网络后重试")
                }
        }
    }

    init {
        // The online home does not need to load a large local collection into memory.
        scope.launch {
            player.state.map { it.id }.distinctUntilChanged().collect { id ->
                if (id != null && !id.startsWith("netease:")) repository.load()
            }
        }
        scope.launch {
            combine(
                player.state
                    .map {
                        Triple(it.id, it.nextId, it.advancing && it.repeat != androidx.media3.common.Player.REPEAT_MODE_ONE)
                    }.distinctUntilChanged(),
                songSources,
                translationModels.state
                    .map {
                        it.selection
                    }.distinctUntilChanged(),
                translationStyle,
                combine(uiActive, restoreRevision) { active, revision -> active to revision },
            ) { playback, library, selection, style, active ->
                val song = library.first.songsById[playback.first] ?: library.second[playback.first]
                val next = library.first.songsById[playback.second] ?: library.second[playback.second]
                LyricsRequest(song, next, LyricsKey(song?.id, song?.lyricsVersion.orEmpty(), selection, style), active.first, playback.third, active.second)
            }.distinctUntilChanged().collectLatest { request ->
                val key = request.key
                if (stateKey != key) {
                    stateKey = key
                    translationEpoch++
                    translationJob?.cancel()
                    publish(CurrentLyrics(key.id, key.revision))
                }
                val song = request.song
                if (!request.active || song == null) return@collectLatest
                try {
                    if (_lyrics.value.document == null) {
                        val epoch = translationEpoch
                        val document = loadLyrics(song)
                        if (!uiActive.value || currentKey() != key || document.revision != key.revision) return@collectLatest
                        val cached =
                            if (epoch == translationEpoch && document.lines.isNotEmpty()) {
                                client.cached(
                                    song,
                                    document.lines,
                                    key.model,
                                    key.style,
                                )
                            } else {
                                null
                            }
                        if (!uiActive.value || currentKey() != key) return@collectLatest
                        if (epoch == translationEpoch) {
                            publish(CurrentLyrics(song.id, key.revision, document, cached))
                        } else {
                            publish(_lyrics.value.copy(document = document))
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    AppDiagnostics.errors.record(ErrorCode.LYRICS_READ, failure)
                    if (currentKey() == key) {
                        publish(
                            CurrentLyrics(
                                song.id,
                                key.revision,
                                com.longzhousz.ami.core
                                    .SongLyrics(revision = key.revision),
                            ),
                        )
                        message("歌词暂时无法读取，音乐仍可播放")
                    }
                    return@collectLatest
                }
                // Only one neighbor, within a byte budget, and cancelled as soon as the UI stops or the track changes.
                try {
                    if (request.prefetch) {
                        delay(700) // Let the current song and opening animation settle first.
                        request.next?.takeIf { it.id != song.id }?.let { next ->
                            kotlinx.coroutines.coroutineScope {
                                launch {
                                    try {
                                        com.longzhousz.ami.ui
                                            .preparePlayerArtwork(app, next.cover)
                                    } catch (
                                        cancelled: CancellationException,
                                    ) {
                                        throw cancelled
                                    } catch (failure: Exception) {
                                        AppDiagnostics.errors.record(ErrorCode.LYRICS_PREFETCH, failure)
                                        // Optional artwork cannot affect audio/lyrics.
                                    }
                                }
                                loadLyrics(next)
                            }
                        }
                    }
                } catch (
                    cancelled: CancellationException,
                ) {
                    throw cancelled
                } catch (failure: Exception) {
                    AppDiagnostics.errors.record(ErrorCode.LYRICS_PREFETCH, failure)
                    // A failed prefetch must not interrupt the current song.
                }
            }
        }
    }

    internal suspend fun translateForBridge(song: Song) = (startTranslation(song, false) ?: error("当前歌曲已经切换")).await()

    fun reloadPersonalSettings() {
        translationEpoch++
        translationJob?.cancel()
        stateKey = null
        publish(CurrentLyrics(player.state.value.id))
        _translationStyle.value = repository.prefs.getString("style", TranslationClient.DEFAULT_STYLE)!!
        restoreRevision.value++
    }

    fun close() {
        player.release()
    }
}
