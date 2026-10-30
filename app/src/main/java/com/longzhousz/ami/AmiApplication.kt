package com.longzhousz.ami

import android.app.Application
import com.longzhousz.ami.data.LibraryRepository
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache
import coil.EventListener
import coil.decode.DataSource
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.longzhousz.ami.data.OnlineArtworkCache
import kotlinx.coroutines.launch
import android.content.ComponentCallbacks2

class AmiApplication : Application(), ImageLoaderFactory {
    private val repositoryHolder = lazy { LibraryRepository(this) }
    val repository by repositoryHolder
    internal val updates by lazy { com.longzhousz.ami.data.AppUpdates(this) }
    private var activeImageLoader: ImageLoader? = null
    internal val artwork by lazy { OnlineArtworkCache(this) }
    internal fun maintainArtworkCache() { artwork.cleanup(active = { repository.canMaintainCaches }) }
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(.12).build() }
        .diskCache(null)
        .components { add(artwork.factory()) }
        .eventListener(object : EventListener {
            override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                if (result.dataSource == DataSource.MEMORY_CACHE && request.data.toString().startsWith("https://"))
                    repository.scope.launch { artwork.touch(request.data.toString()) }
            }
        }).build().also {
            activeImageLoader=it
        }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level==ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level>=ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) clearOptionalCaches()
    }
    override fun onLowMemory() { super.onLowMemory();clearOptionalCaches() }
    private fun clearOptionalCaches() {
        if (repositoryHolder.isInitialized()) { repository.lyrics.trim(); repository.trimCloud() }
        activeImageLoader?.memoryCache?.clear()
        artwork.clearMemory()
        com.longzhousz.ami.ui.clearArtworkColorCache()
    }
}
