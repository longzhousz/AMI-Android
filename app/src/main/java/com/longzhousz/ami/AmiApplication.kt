package com.longzhousz.ami

import android.app.Application
import com.longzhousz.ami.data.LibraryRepository
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache
import android.content.ComponentCallbacks2

class AmiApplication : Application(), ImageLoaderFactory {
    private val repositoryHolder = lazy { LibraryRepository(this) }
    val repository by repositoryHolder
    private var activeImageLoader: ImageLoader? = null
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(.12).build() }.build().also { activeImageLoader=it }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level==ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level>=ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) clearOptionalCaches()
    }
    override fun onLowMemory() { super.onLowMemory();clearOptionalCaches() }
    private fun clearOptionalCaches() {
        if (repositoryHolder.isInitialized()) repository.lyrics.trim()
        activeImageLoader?.memoryCache?.clear()
        com.longzhousz.ami.ui.clearArtworkColorCache()
    }
}
