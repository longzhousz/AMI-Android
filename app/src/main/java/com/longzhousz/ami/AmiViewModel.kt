package com.longzhousz.ami

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.longzhousz.ami.core.CloudTrack
import com.longzhousz.ami.core.Song
import com.longzhousz.ami.core.isCloudSong
import com.longzhousz.ami.data.LibraryRepository
import com.longzhousz.ami.data.TranslationClient
import com.longzhousz.ami.data.TranslationModelCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class AmiViewModel internal constructor(
    app: Application,
    clientFactory: (LibraryRepository, TranslationModelCatalog) -> TranslationClient,
    catalogFactory: (LibraryRepository) -> TranslationModelCatalog = { TranslationModelCatalog(it) },
) : AndroidViewModel(app) {
    constructor(app: Application) : this(app, { repository, models -> TranslationClient(repository, models) })

    val repository = (app as AmiApplication).repository
    val cloud = repository.cloud
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _theme = MutableStateFlow(repository.prefs.getString("theme", "system")!!)
    val theme = _theme.asStateFlow()
    val playback = PlaybackStateHolder(app, repository, viewModelScope, ::showMessage, clientFactory, catalogFactory)
    val online = OnlineStateHolder(repository, viewModelScope, playback, ::showMessage)
    val library = LibraryStateHolder(app, repository, viewModelScope, playback, ::showMessage, ::restoreSettings)
    val player get() = playback.player
    val playerSelection get() = playback.playerSelection
    val lyrics get() = playback.lyrics
    val translations get() = playback.translations
    val translating get() = playback.translating
    val hasKey get() = playback.hasKey
    val translationModels get() = playback.translationModels
    val translationStyle get() = playback.translationStyle
    val onlineLibrary get() = online.onlineLibrary
    val onlineRefresh get() = online.onlineRefresh
    val discovering get() = online.discovering
    val bridge get() = library.bridge
    val bridgeEnabled get() = library.bridgeEnabled
    val bridgeAlbum get() = library.bridgeAlbum

    fun showMessage(value: String?) {
        _message.value = value
    }

    fun consumeMessage(value: String?) {
        _message.compareAndSet(value, null)
    }

    fun setTheme(value: String) {
        require(value in setOf("system", "light", "dark"))
        repository.prefs
            .edit()
            .putString("theme", value)
            .apply()
        _theme.value = value
    }

    private fun restoreSettings(values: Map<String, String>) {
        values["theme"]?.let(::setTheme)
        values["style"]?.let(playback::setTranslationStyle)
        values["translationModels.selection"]?.let(playback::selectTranslationModel)
        values["translationModels.autoUpdate"]?.let { playback.setAutomaticModelUpdates(it.toBooleanStrict()) }
        values["onlineCacheLimit"]?.let { repository.setAudioCacheLimit(it.toLong()) }
        values["listeningScale"]?.let {
            library.setListeningScale(
                com.longzhousz.ami.core.ListeningScale
                    .valueOf(it),
            )
        }
    }

    fun setBridgeEnabled(value: Boolean) = library.setBridgeEnabled(value)

    fun consumeBridgeAlbum(value: String) = library.consumeBridgeAlbum(value)

    fun setUiActive(value: Boolean) = playback.setUiActive(value)

    fun findSong(id: String?) = playback.findSong(id)

    fun act(
        name: String,
        block: () -> Unit,
    ) = playback.act(name, block)

    fun requestOnlineRefresh(page: Int) = online.requestOnlineRefresh(page)

    fun consumeOnlineRefresh(page: Int) = online.consumeOnlineRefresh(page)

    fun resetOnlineLibrary() = online.resetOnlineLibrary()

    fun refreshOnlineLibrary() = online.refreshOnlineLibrary()

    fun refreshCloudLikes(force: Boolean = false) = online.refreshCloudLikes(force)

    fun favoriteCloud(track: CloudTrack) = online.favoriteCloud(track)

    fun playCloud(
        tracks: List<CloudTrack>,
        index: Int,
    ) = online.playCloud(tracks, index)

    fun discover(
        kind: String,
        onReady: () -> Unit,
    ) = online.discover(kind, onReady)

    fun cancelCloudPreparation() = online.cancelCloudPreparation()

    fun play(
        songs: List<Song>,
        index: Int = 0,
    ) {
        online.cancelCloudPreparation()
        cloud.stopRadio()
        repository.prefs
            .edit()
            .putBoolean("phoneFollowing", false)
            .apply()
        playback.act("player.play") { player.play(songs, index) }
    }

    fun favorite(song: Song) {
        if (song.isCloudSong()) online.favorite(song) else library.favorite(song)
    }

    fun reorder(ids: List<String>) = library.reorder(ids)

    fun remove(song: Song) = library.remove(song)

    fun clearLyrics(song: Song) = library.clearLyrics(song)

    fun importLyrics(
        id: String,
        uri: Uri,
    ) = library.importLyrics(id, uri)

    fun translate(
        song: Song,
        force: Boolean = false,
    ) = playback.translate(song, force)

    fun refreshModels(force: Boolean = false) = playback.refreshModels(force)

    fun selectTranslationModel(selection: String) = playback.selectTranslationModel(selection)

    fun setAutomaticModelUpdates(enabled: Boolean) = playback.setAutomaticModelUpdates(enabled)

    fun setTranslationStyle(style: String) = playback.setTranslationStyle(style)

    fun saveKey(value: String) = playback.saveKey(value)

    fun testConnection() = playback.testConnection()

    suspend fun processBridge() = library.processBridge()

    override fun onCleared() {
        playback.close()
        super.onCleared()
    }
}
