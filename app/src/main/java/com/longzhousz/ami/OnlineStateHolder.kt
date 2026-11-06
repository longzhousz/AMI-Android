package com.longzhousz.ami

import com.longzhousz.ami.core.CloudLibraryState
import com.longzhousz.ami.core.CloudTrack
import com.longzhousz.ami.core.ErrorCode
import com.longzhousz.ami.core.Song
import com.longzhousz.ami.core.shuffledShelf
import com.longzhousz.ami.data.AppDiagnostics
import com.longzhousz.ami.data.LibraryRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class OnlineStateHolder internal constructor(
    private val repository: LibraryRepository,
    private val scope: CoroutineScope,
    private val playback: PlaybackStateHolder,
    private val message: (String?) -> Unit,
) {
    val cloud = repository.cloud
    private val player get() = playback.player
    private var cloudPlayJob: kotlinx.coroutines.Job? = null
    private var libraryJob: kotlinx.coroutines.Job? = null
    private var likesJob: kotlinx.coroutines.Job? = null
    private var likesSession = -1L
    private val _onlineLibrary = MutableStateFlow(CloudLibraryState())
    val onlineLibrary = _onlineLibrary.asStateFlow()
    private val _onlineRefresh = MutableStateFlow<Int?>(null)
    val onlineRefresh = _onlineRefresh.asStateFlow()
    private val _discovering = MutableStateFlow(false)
    val discovering = _discovering.asStateFlow()

    fun requestOnlineRefresh(page: Int) {
        if (page in 0..1) _onlineRefresh.value = page
    }

    fun consumeOnlineRefresh(page: Int) = _onlineRefresh.compareAndSet(page, null)

    fun cancelCloudPreparation() {
        cloudPlayJob?.cancel()
        _discovering.value = false
    }

    fun resetOnlineLibrary() {
        libraryJob?.cancel()
        cloud.clearPages()
        _onlineLibrary.value = CloudLibraryState()
    }

    fun refreshOnlineLibrary() {
        if (!cloud.configured.value || libraryJob?.isActive == true) return
        refreshCloudLikes(force = true)
        libraryJob =
            scope.launch {
                _onlineLibrary.value = _onlineLibrary.value.copy(refreshing = true, error = null)
                try {
                    cloud.refreshCatalog()
                    val fresh = cloud.library()
                    ensureActive()
                    _onlineLibrary.value =
                        CloudLibraryState(
                            fresh.copy(collections = shuffledShelf(fresh.collections, _onlineLibrary.value.library.collections)),
                            loaded = true,
                            revision = _onlineLibrary.value.revision + 1,
                        )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    AppDiagnostics.errors.record(ErrorCode.ONLINE_LIBRARY, failure)
                    _onlineLibrary.value = _onlineLibrary.value.copy(refreshing = false, error = "曲库暂时无法刷新")
                }
            }
    }

    fun playCloud(
        tracks: List<CloudTrack>,
        index: Int,
    ) {
        if (tracks.getOrNull(index)?.available != true) {
            message("当前账号暂时无法播放这首歌，请检查歌曲权限")
            return
        }
        repository.prefs
            .edit()
            .putBoolean("phoneFollowing", false)
            .apply()
        cancelCloudPreparation()
        cloudPlayJob =
            scope.launch {
                try {
                    startCloudQueue(tracks, index)
                } catch (
                    cancelled: CancellationException,
                ) {
                    throw cancelled
                } catch (failure: Exception) {
                    AppDiagnostics.errors.record(ErrorCode.ONLINE_QUEUE, failure)
                    message("在线队列暂时无法保存，请稍后重试")
                }
            }
    }

    fun discover(
        kind: String,
        onReady: () -> Unit,
    ) {
        if (kind !in setOf("fm", "daily") || !cloud.configured.value) return
        cancelCloudPreparation()
        _discovering.value = true
        cloudPlayJob =
            scope.launch {
                try {
                    val tracks = cloud.discovery(kind)
                    check(tracks.isNotEmpty()) { "暂时没有可播放的推荐歌曲" }
                    ensureActive()
                    repository.prefs
                        .edit()
                        .putBoolean("phoneFollowing", false)
                        .apply()
                    val chosen =
                        startCloudQueue(
                            tracks,
                            0,
                            if (kind == "fm") {
                                java.util.UUID
                                    .randomUUID()
                                    .toString()
                            } else {
                                null
                            },
                        )
                    check(withTimeoutOrNull(5000) { player.state.first { it.id == chosen } } != null)
                    ensureActive()
                    onReady()
                } catch (
                    cancelled: CancellationException,
                ) {
                    throw cancelled
                } catch (
                    failure: Exception,
                ) {
                    AppDiagnostics.errors.record(ErrorCode.ONLINE_DISCOVERY, failure)
                    message("推荐暂时无法播放，请稍后重试")
                } finally {
                    if (currentCoroutineContext().isActive) _discovering.value = false
                }
            }
    }

    private suspend fun startCloudQueue(
        tracks: List<CloudTrack>,
        index: Int,
        radioToken: String? = null,
    ): String {
        val chosen = tracks[index].id
        val available = tracks.filter { it.available }
        val start = (available.indexOfFirst { it.id == chosen } - 500).coerceIn(0, (available.size - 1000).coerceAtLeast(0))
        val queue = available.drop(start).take(1000)
        cloud.rememberQueue(queue, radioToken)
        currentCoroutineContext().ensureActive()
        playback.act("player.play") { player.play(queue.map { it.song() }, queue.indexOfFirst { it.id == chosen }, radioToken != null) }
        return "netease:$chosen"
    }

    fun refreshCloudLikes(force: Boolean = false) {
        if (!cloud.direct || !cloud.configured.value || (cloud.likes.value.ready && !force)) return
        val session = cloud.likes.value.session
        if (likesJob?.isActive == true && likesSession == session) return
        likesJob?.cancel()
        likesSession = session
        likesJob =
            scope.launch {
                try {
                    cloud.refreshLikes(force)
                } catch (
                    cancelled: CancellationException,
                ) {
                    throw cancelled
                } catch (
                    expired: com.longzhousz.ami.data.NeteaseLoginExpired,
                ) {
                    if (cloud.likes.value.session == session) message(expired.message)
                } catch (
                    failure: Exception,
                ) {
                    AppDiagnostics.errors.record(ErrorCode.ONLINE_FAVORITES, failure)
                    if (cloud.likes.value.session == session) message("红心状态暂时无法同步，请下拉刷新后重试")
                }
            }
    }

    private fun changeCloudLike(block: suspend () -> Unit) {
        val session = cloud.likes.value.session
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                block()
                if (_onlineLibrary.value.loaded && _onlineLibrary.value.library.liked == null &&
                    cloud.likes.value.ids
                        .isNotEmpty() &&
                    cloud.likes.value.pending
                        .isEmpty()
                ) {
                    refreshOnlineLibrary()
                }
            } catch (
                cancelled: CancellationException,
            ) {
                throw cancelled
            } catch (
                expired: com.longzhousz.ami.data.NeteaseLoginExpired,
            ) {
                if (cloud.likes.value.session == session) message(expired.message)
            } catch (
                failure: Exception,
            ) {
                AppDiagnostics.errors.record(ErrorCode.ONLINE_FAVORITES, failure)
                if (cloud.likes.value.session == session) message("红心未能确认同步，请刷新后重试")
            }
        }
    }

    fun favorite(song: Song) = changeCloudLike { cloud.toggleLike(song) }

    fun favoriteCloud(track: CloudTrack) = changeCloudLike { cloud.toggleLike(track) }
}
