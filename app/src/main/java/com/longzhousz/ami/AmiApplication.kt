package com.longzhousz.ami

import android.app.Application
import com.longzhousz.ami.data.LibraryRepository
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache

class AmiApplication : Application(), ImageLoaderFactory {
    val repository by lazy { LibraryRepository(this) }
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(.12).build() }.build()
}
