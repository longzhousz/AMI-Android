package com.longzhousz.ami.data

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil.ImageLoader
import coil.decode.DataSource
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class OnlineArtworkCacheTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private lateinit var png: ByteArray
    private var now = 0L
    private val url = "https://p1.music.126.net/qa-cover.png"
    @Before fun prepare() {
        dir = File(context.cacheDir, "qa-artwork-${System.nanoTime()}").apply { mkdirs() }
        now = System.currentTimeMillis()
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply { eraseColor(0xffd85644.toInt()) }
        png = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
        bitmap.recycle()
    }
    @After fun cleanup() { dir.deleteRecursively() }

    @Test fun visibleCoverAndBackdropShareOneDownloadAndSurviveLoaderRecreationOffline() = runBlocking(Dispatchers.IO) {
        val calls = AtomicInteger()
        val cache = OnlineArtworkCache(context, dir, download = { calls.incrementAndGet(); delay(50); png }, clock = { now }, reserve = 0)
        val loader = ImageLoader.Builder(context).components { add(cache.factory()) }.memoryCache(null).diskCache(null).build()
        try {
            val results = listOf(48, 180, 600).map { size -> async {
                loader.execute(ImageRequest.Builder(context).data(url).size(size).allowHardware(false).build())
            } }.awaitAll()
            assertTrue(results.all { it is SuccessResult })
            assertEquals(1, calls.get())
            assertArrayEquals("The original file must not be recompressed", png, dir.listFiles()!!.single().readBytes())
        } finally { loader.shutdown() }
        val offline = OnlineArtworkCache(context, dir, download = { error("No network allowed") }, clock = { now }, reserve = 0)
        val reopened = ImageLoader.Builder(context).components { add(offline.factory()) }.memoryCache(null).diskCache(null).build()
        try {
            val result = reopened.execute(ImageRequest.Builder(context).data(url).size(400).allowHardware(false).build())
            assertTrue(result is SuccessResult)
            assertEquals(DataSource.DISK, (result as SuccessResult).dataSource)
        } finally { reopened.shutdown() }
    }
    @Test fun recentUseKeepsTheCoverForAWeekAndOnlyStaleArtworkIsReclaimed() = runBlocking(Dispatchers.IO) {
        val cache = OnlineArtworkCache(context, dir, download = { png }, clock = { now }, reserve = 0)
        cache.load(url).source.close()
        now += 6 * 86_400_000L
        cache.touch(url)
        now += 2 * 86_400_000L
        cache.cleanup(force = true)
        assertEquals(1, dir.listFiles()!!.size)
        now += OnlineArtworkCache.WEEK
        cache.cleanup(force = true)
        assertTrue(dir.listFiles()!!.isEmpty())
    }
    @Test fun invalidImagesNeverEnterTheCacheAndUnusedUrlsAreNeverFetched() = runBlocking(Dispatchers.IO) {
        var calls = 0
        val cache = OnlineArtworkCache(context, dir, download = { calls++; "not an image".toByteArray() }, reserve = 0)
        cache.factory(); cache.cleanup(force = true)
        assertEquals(0, calls)
        assertTrue(runCatching { cache.load(url) }.isFailure)
        assertTrue(dir.listFiles()!!.isEmpty())
    }
    @Test fun lowStorageStillDisplaysTheCurrentImageWithoutPersistingMoreFiles() = runBlocking(Dispatchers.IO) {
        val cache = OnlineArtworkCache(context, dir, download = { png }, reserve = Long.MAX_VALUE / 2)
        val loader = ImageLoader.Builder(context).components { add(cache.factory()) }.memoryCache(null).diskCache(null).build()
        try {
            assertTrue(loader.execute(ImageRequest.Builder(context).data(url).allowHardware(false).build()) is SuccessResult)
            assertTrue(dir.listFiles()!!.isEmpty())
        } finally { loader.shutdown() }
    }
    @Test fun visibleImageRequestsNeverSweepTheDirectoryAndMaintenanceCanYield() = runBlocking(Dispatchers.IO) {
        val cache = OnlineArtworkCache(context, dir, download = { png }, clock = { now }, reserve = 0)
        cache.load(url).source.close()
        val stale = dir.listFiles()!!.single()
        now += OnlineArtworkCache.WEEK + 1000
        cache.load("https://p1.music.126.net/qa-new.png").source.close()
        assertTrue("A visible image load must not sweep other covers", stale.exists())
        cache.cleanup(force = true, active = { false })
        assertTrue(stale.exists())
        cache.cleanup(force = true)
        assertFalse(stale.exists())
        assertEquals(1, dir.listFiles()!!.size)
    }
}
