package com.longzhousz.ami

import android.app.Application
import android.net.Uri
import com.longzhousz.ami.core.ErrorCode
import com.longzhousz.ami.core.Song
import com.longzhousz.ami.data.AgentActions
import com.longzhousz.ami.data.AgentBridge
import com.longzhousz.ami.data.AgentProtocol
import com.longzhousz.ami.data.AppDiagnostics
import com.longzhousz.ami.data.LibraryRepository
import com.longzhousz.ami.data.LyricImporter
import com.longzhousz.ami.data.PersonalDataBackup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class LibraryStateHolder internal constructor(
    private val app: Application,
    internal val repository: LibraryRepository,
    private val scope: CoroutineScope,
    private val playback: PlaybackStateHolder,
    private val online: OnlineStateHolder,
    private val message: (String?) -> Unit,
    private val restoreSettings: (Map<String, String>) -> Unit,
) {
    private val player get() = playback.player
    val bridge = AgentBridge(repository)
    private val _bridgeEnabled = MutableStateFlow(repository.prefs.getBoolean("bridgeEnabled", false))
    val bridgeEnabled = _bridgeEnabled.asStateFlow()
    private val _bridgeAlbum = MutableStateFlow<String?>(null)
    val bridgeAlbum = _bridgeAlbum.asStateFlow()
    private val _backupBusy = MutableStateFlow(false)
    val backupBusy = _backupBusy.asStateFlow()

    fun exportData(uri: Uri) =
        backupOperation(ErrorCode.BACKUP_EXPORT) {
            withContext(Dispatchers.IO) {
                val temporary = File.createTempFile("ami-export-", ".json", app.cacheDir)
                try {
                    val coroutine = currentCoroutineContext()
                    repository.listeningStats.withFlushedSnapshot {
                        temporary.outputStream().buffered().use { output ->
                            PersonalDataBackup(repository.db, app.cacheDir).write(output, PersonalDataBackup.settings(repository.prefs)) { coroutine.ensureActive() }
                        }
                    }
                    app.contentResolver.openOutputStream(uri, "wt").use { output ->
                        checkNotNull(output)
                        temporary.inputStream().use { input ->
                            val buffer = ByteArray(32 * 1024)
                            while (true) {
                                coroutine.ensureActive()
                                val size = input.read(buffer)
                                if (size < 0) break
                                output.write(buffer, 0, size)
                            }
                        }
                    }
                } finally {
                    temporary.delete()
                }
            }
            message(app.getString(com.longzhousz.ami.R.string.backup_exported))
        }

    fun restoreData(uri: Uri) =
        backupOperation(ErrorCode.BACKUP_RESTORE) {
            val settings =
                withContext(Dispatchers.IO) {
                    val coroutine = currentCoroutineContext()
                    app.contentResolver.openInputStream(uri).use { input ->
                        checkNotNull(input)
                        PersonalDataBackup(repository.db, app.cacheDir).read(input) { coroutine.ensureActive() }.use { document ->
                            repository.listeningStats.withFlushedSnapshot { document.merge { coroutine.ensureActive() } }
                            repository.lyrics.trim()
                            repository.trimCloud()
                            repository.reload()
                            document.settings
                        }
                    }
                }
            restoreSettings(settings)
            playback.reloadPersonalSettings()
            message(app.getString(com.longzhousz.ami.R.string.backup_restored))
        }

    @Suppress("TooGenericExceptionCaught") // SAF, JSON and SQLite failures share one user-visible boundary.
    private fun backupOperation(code: ErrorCode, action: suspend () -> Unit) {
        if (!_backupBusy.compareAndSet(false, true)) return
        scope.launch {
            try {
                repository.importLock.withLock { action() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                AppDiagnostics.errors.record(code, failure)
                message(app.getString(if (code == ErrorCode.BACKUP_EXPORT) com.longzhousz.ami.R.string.backup_export_failed else com.longzhousz.ami.R.string.backup_restore_failed))
            } finally {
                _backupBusy.value = false
            }
        }
    }

    private fun persistAction(
        name: String,
        block: suspend () -> Unit,
    ) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    block()
                    repository.db.log(name, "user", "success")
                }
            } catch (
                e: CancellationException,
            ) {
                throw e
            } catch (failure: Exception) {
                AppDiagnostics.errors.record(ErrorCode.LIBRARY_WRITE, failure)
                message("保存失败，请检查设备存储空间后重试")
            }
        }
    }

    fun reorder(ids: List<String>) = persistAction("album.reorderTracks") { repository.reorder(ids) }

    fun importLyrics(
        songId: String,
        uri: Uri,
    ) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) { LyricImporter(app, repository).attach(songId, uri) }
                message("歌词已关联到这首歌，重新扫描后仍会保留")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppDiagnostics.errors.record(ErrorCode.LYRICS_IMPORT, e)
                message("歌词导入失败，请检查文件格式与读取权限")
            }
        }
    }

    fun clearLyrics(song: Song) =
        persistAction("lyrics.restoreAutomatic") {
            repository.db.clearLyrics(song.id)
            repository.reload()
        }

    fun remove(song: Song) {
        player.remove(song.id)
        persistAction("library.remove") { repository.remove(song) }
    }

    private val agentActions by lazy { AgentActions(this, playback) }

    suspend fun processBridge() = bridge.process { action, payload -> agentActions.execute(action, payload) }

    suspend fun confirmBridgePending(
        requestId: String,
        accept: Boolean,
    ) = bridge.confirm(requestId, accept) { action, payload -> agentActions.execute(action, payload) }

    fun favorite(song: Song) = persistAction("library.favorite") { repository.favorite(song) }

    fun setBridgeEnabled(enabled: Boolean) {
        repository.prefs
            .edit()
            .putBoolean("bridgeEnabled", enabled)
            .apply()
        _bridgeEnabled.value = enabled
    }

    private val permissionDefaults = AgentProtocol.permissionDefaults
    internal val repositoryForAgent get() = repository
    internal val contextForAgent get() = app
    private val _bridgePermissions = MutableStateFlow(permissionDefaults.mapValues { (key, default) -> repository.prefs.getBoolean(key, default) })
    val bridgePermissions = _bridgePermissions.asStateFlow()
    val bridgePending get() = bridge.requestInfo().filter { it.state == AgentProtocol.STATUS_REQUIRES_CONFIRMATION }

    fun setBridgePermission(
        key: String,
        allowed: Boolean,
    ) {
        require(key in permissionDefaults)
        repository.prefs
            .edit()
            .putBoolean(key, allowed)
            .apply()
        _bridgePermissions.value = _bridgePermissions.value + (key to allowed)
    }

    fun setListeningScale(value: com.longzhousz.ami.core.ListeningScale) {
        repository.prefs
            .edit()
            .putString("listeningScale", value.name)
            .apply()
    }

    fun consumeBridgeAlbum(album: String) {
        _bridgeAlbum.compareAndSet(album, null)
    }

    fun setBridgeFolder(uri: Uri) {
        repository.prefs
            .edit()
            .putString("bridgeFolder", uri.toString())
            .apply()
        message("已选择 Agent 交换文件夹")
    }

    fun cleanCache() =
        persistAction("cache.cleanup") {
            val bytes = repository.cleanup()
            message("已清理 ${"%.1f MB".format(java.util.Locale.ROOT, bytes / 1048576.0)}")
        }

    internal fun bridgeTokenConfigured() = bridge.tokenConfigured()

    internal fun openBridgeAlbum(key: String) {
        _bridgeAlbum.value = key
    }

    internal suspend fun cleanBridgePending(): AgentBridge.ActionResult {
        val bytes =
            withContext(Dispatchers.IO) {
                currentCoroutineContext().ensureActive()
                val freed = repository.cleanup()
                currentCoroutineContext().ensureActive()
                val coroutine = currentCoroutineContext()
                repository.onlineCache.cleanup(System.currentTimeMillis(), active = { coroutine.isActive })
                freed
            }
        return AgentBridge.ActionResult(data = JSONObject().put("freedBytes", bytes))
    }

    internal fun refreshBridgeOnlineLibrary() = online.refreshOnlineLibrary()

    internal fun refreshBridgeCloudLikes() = online.refreshCloudLikes(true)

    internal fun cancelBridgeCloudPreparation() = online.cancelCloudPreparation()

    internal fun cancelBridgeTranslation() = playback.cancelTranslation()

    internal fun bridgeRequests() = bridge.requestInfo()

    internal fun playBridgeCloud(
        tracks: List<com.longzhousz.ami.core.CloudTrack>,
        index: Int,
    ) = online.playCloud(tracks, index)

    internal suspend fun clearBridgePending(): AgentBridge.ActionResult {
        withContext(Dispatchers.IO) { repository.listeningStats.clear() }
        return AgentBridge.ActionResult()
    }
}
