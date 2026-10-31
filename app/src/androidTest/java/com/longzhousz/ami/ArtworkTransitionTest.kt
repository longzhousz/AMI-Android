package com.longzhousz.ami

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longzhousz.ami.core.Song
import com.longzhousz.ami.playback.PlaybackState
import com.longzhousz.ami.ui.AmiTheme
import com.longzhousz.ami.ui.PlayerActions
import com.longzhousz.ami.ui.PlayerContent
import com.longzhousz.ami.ui.Cover
import com.longzhousz.ami.ui.LocalVisualsActive
import com.longzhousz.ami.ui.preparePlayerArtwork
import com.longzhousz.ami.ui.clearArtworkColorCache
import coil.Coil
import coil.ImageLoader
import coil.imageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class ArtworkTransitionTest {
    @get:Rule val compose=createComposeRule()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture():Song {
        val cover=File(context.cacheDir,"qa-artwork.jpg")
        val bitmap=Bitmap.createBitmap(600,600,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap)
        canvas.drawPaint(Paint().apply { shader=LinearGradient(0f,0f,600f,600f,0xff788799.toInt(),0xff29334b.toInt(),Shader.TileMode.CLAMP) })
        canvas.drawCircle(360f,260f,155f,Paint(Paint.ANTI_ALIAS_FLAG).apply { color=0xffe5cfc4.toInt() })
        canvas.drawRect(0f,400f,600f,600f,Paint().apply { color=0xff697e91.toInt() })
        cover.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,95,it) };bitmap.recycle()
        return Song("qa-artwork","file:///unused",folder="qa",title="把晚风留在唱片里",artist="AMI · 慢慢听",album="晚风与回声",duration=90000,cover=cover.path,
            lyrics="[00:00]让封面自由生长\n[00:12]让旋律继续流动\n[00:30]把每个平凡的瞬间，唱成一首温柔的歌")
    }
    private fun screenshot(name:String) {
        val image=compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.filesDir,"qa/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    @Test fun coverTravelsContinuouslyFromThumbnailAndDownwardSwipeReturnsToLyrics() {
        val song=fixture()
        compose.setContent { AmiTheme("dark") { PlayerContent(song,PlaybackState(duration=90000),null,false,PlayerActions()) } }
        compose.onNodeWithContentDescription("定时停止").assertDoesNotExist()
        compose.onNodeWithContentDescription("切换到歌词").performClick()
        val small=compose.onNodeWithTag("player-cover").fetchSemanticsNode().boundsInRoot
        val large=compose.onNodeWithTag("artwork-expanded-anchor").fetchSemanticsNode().boundsInRoot
        screenshot("player-lyrics-redesign")
        compose.mainClock.autoAdvance=false
        compose.onNodeWithContentDescription("展开专辑封面").performClick()
        compose.mainClock.advanceTimeBy(120)
        val middle=compose.onNodeWithTag("player-cover").fetchSemanticsNode().boundsInRoot
        assertTrue("Artwork should grow continuously",middle.width>small.width+5 && middle.width<large.width-5)
        compose.onAllNodesWithTag("player-cover").assertCountEquals(1)
        screenshot("player-artwork-mid")
        compose.mainClock.advanceTimeBy(1500)
        assertEquals(large.width,compose.onNodeWithTag("player-cover").fetchSemanticsNode().boundsInRoot.width,2f)
        screenshot("player-artwork-expanded")
        compose.mainClock.autoAdvance=true
        val bounds=compose.onNodeWithTag("player-cover").fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput { swipe(bounds.center,Offset(bounds.center.x,bounds.bottom+100f),450) }
        compose.onNodeWithContentDescription("展开专辑封面").assertIsDisplayed()
        assertEquals(small.width,compose.onNodeWithTag("player-cover").fetchSemanticsNode().boundsInRoot.width,2f)
        compose.onNodeWithTag("lyric-0").assertIsDisplayed()
    }
    @Test fun partialDragCanReverseAndASecondTapCanReverseAnInFlightExpansion() {
        val song=fixture()
        compose.setContent { AmiTheme("dark") { PlayerContent(song,PlaybackState(duration=90000),null,false,PlayerActions()) } }
        compose.onNodeWithContentDescription("切换到歌词").performClick()
        val small=compose.onNodeWithTag("player-cover").fetchSemanticsNode().boundsInRoot.width
        compose.mainClock.autoAdvance=false
        compose.onNodeWithContentDescription("展开专辑封面").performClick()
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithContentDescription("切换到歌词").performClick()
        compose.mainClock.advanceTimeBy(1500)
        assertEquals(small,compose.onNodeWithTag("player-cover").fetchSemanticsNode().boundsInRoot.width,2f)
        compose.mainClock.autoAdvance=true
        compose.onNodeWithContentDescription("展开专辑封面").performClick()
        val large=compose.onNodeWithTag("player-cover").fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance=false
        val movement=large.height*.30f
        compose.onRoot().performTouchInput { down(large.center);moveBy(Offset(0f,movement)) }
        compose.mainClock.advanceTimeByFrame()
        val middle=compose.onNodeWithTag("player-cover").fetchSemanticsNode().boundsInRoot.width
        assertTrue(middle<large.width-5 && middle>small+5)
        compose.onRoot().performTouchInput { moveBy(Offset(0f,-movement));up() }
        compose.mainClock.advanceTimeBy(1500)
        assertEquals(large.width,compose.onNodeWithTag("player-cover").fetchSemanticsNode().boundsInRoot.width,2f)
        compose.onAllNodesWithTag("player-cover").assertCountEquals(1)
    }

    @Test fun slowArtworkKeepsTheLastDecodedImageAndLateResultsCannotReplaceANewerCover() {
        val original = context.imageLoader
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val returned = CompletableDeferred<Unit>()
        val red = 0xffd84840.toInt(); val blue = 0xff426fc4.toInt(); val green = 0xff467f61.toInt()
        fun png(color: Int): ByteArray {
            val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            return ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); bitmap.recycle(); it.toByteArray() }
        }
        val loader = ImageLoader.Builder(context).components { add(object : Fetcher.Factory<Uri> {
            override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
                if (data.host != "p1.music.126.net") return null
                return object : Fetcher {
                    override suspend fun fetch(): SourceResult {
                        if (data.lastPathSegment == "slow.png") {
                            entered.complete(Unit)
                            withContext(NonCancellable) { release.await() }
                            returned.complete(Unit)
                        }
                        val color = when (data.lastPathSegment) { "first.png" -> red; "slow.png" -> blue; else -> green }
                        return SourceResult(ImageSource(Buffer().write(png(color)), context), "image/png", DataSource.NETWORK)
                    }
                }
            }
        }) }.build()
        var path by mutableStateOf("https://p1.music.126.net/first.png")
        var visible by mutableStateOf(true)
        fun pixel(): Int {
            val bitmap = compose.onNodeWithTag("qa-switch-cover").captureToImage().asAndroidBitmap()
            return bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        }
        Coil.setImageLoader(loader)
        try {
            compose.setContent { if (visible) Box(Modifier.size(180.dp).testTag("qa-switch-cover")) { Cover(path, "换歌验证", Modifier.size(180.dp)) } }
            compose.waitUntil(10000) { pixel() == red }
            loader.memoryCache?.clear() // The retained image must survive eviction of its old key.
            compose.runOnIdle { path = "https://p1.music.126.net/slow.png" }
            compose.waitUntil(10000) { entered.isCompleted }
            assertEquals(red, pixel())
            compose.runOnIdle { path = "https://p1.music.126.net/latest.png" }
            compose.waitUntil(10000) { pixel() == green }
            release.complete(Unit)
            compose.waitUntil(10000) { returned.isCompleted }
            assertEquals(green, pixel())
        } finally {
            release.complete(Unit)
            compose.runOnIdle { visible = false }
            Coil.setImageLoader(original); loader.shutdown()
        }
    }

    @Test fun recycledSeenCoversShowImmediatelyWhileFullRequestsAreStillBlocked() {
        val app = context.applicationContext as AmiApplication
        val original = context.imageLoader
        val release = CompletableDeferred<Unit>()
        val addresses = (0 until 12).map { "https://p1.music.126.net/qa-return-${System.nanoTime()}-$it.png" }
        val files = addresses.map { address ->
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(address.toByteArray()).joinToString("") { "%02x".format(it) }
            File(context.cacheDir, "OnlineArtwork/$hash.img")
        }
        val bytes = ByteArrayOutputStream().use { output ->
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(0xffd84840.toInt()) }
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); bitmap.recycle(); output.toByteArray()
        }
        files.forEach { it.parentFile!!.mkdirs(); it.writeBytes(bytes) }
        runBlocking(Dispatchers.IO) { addresses.forEach { app.artwork.load(it).source.close() } }
        val loader = ImageLoader.Builder(context).memoryCache(null).components { add(object : Fetcher.Factory<Uri> {
            override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? = object : Fetcher {
                override suspend fun fetch(): SourceResult {
                    release.await()
                    return SourceResult(ImageSource(Buffer().write(bytes), context), "image/png", DataSource.DISK)
                }
            }
        }) }.build()
        var path by mutableStateOf(addresses.first())
        var shown by mutableStateOf(true)
        Coil.setImageLoader(loader)
        try {
            compose.setContent { if (shown) Box(Modifier.size(180.dp).testTag("qa-return-cover")) { Cover(path, "浏览过的唱片", Modifier.size(180.dp)) } }
            for (address in addresses.asReversed()) {
                compose.runOnIdle { shown = false }
                compose.waitForIdle()
                compose.runOnIdle { path = address; shown = true }
                val image = compose.onNodeWithTag("qa-return-cover").captureToImage().asAndroidBitmap()
                val pixel = image.getPixel(image.width / 2, image.height / 2)
                assertTrue("A seen cover must be visible before the full decoder returns", ((pixel shr 16) and 255) > 180 && (pixel and 255) < 100)
                assertFalse(release.isCompleted)
            }
            screenshot("shelf-seen-preview")
        } finally {
            compose.runOnIdle { shown = false }
            release.complete(Unit); Coil.setImageLoader(original); loader.shutdown()
            files.forEach { it.delete() }; app.artwork.clearMemory()
        }
    }

    @Test fun preparedNeighborTransitionsTheCoverTitleAndPaletteThroughIntermediateFrames() {
        val original = context.imageLoader
        val red = 0xffd84840.toInt(); val blue = 0xff426fc4.toInt()
        val prefix = "https://p1.music.126.net/qa-transition-${System.nanoTime()}"
        val cachedCoverFades = java.util.concurrent.atomic.AtomicBoolean()
        val loader = ImageLoader.Builder(context).eventListener(object : coil.EventListener {
            override fun onSuccess(request: coil.request.ImageRequest, result: coil.request.SuccessResult) {
                if (request.data.toString() == "$prefix-blue.png" && result.dataSource == DataSource.MEMORY_CACHE && request.placeholderMemoryCacheKey != null) {
                    val target = object : coil.transition.TransitionTarget {
                        override val view: android.view.View get() = error("Not used to create a transition")
                        override val drawable: android.graphics.drawable.Drawable? get() = null
                    }
                    cachedCoverFades.set(request.transitionFactory.create(target, result) is coil.transition.CrossfadeTransition)
                }
            }
        }).components { add(object : Fetcher.Factory<Uri> {
            override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
                if (!data.toString().startsWith(prefix)) return null
                return object : Fetcher {
                    override suspend fun fetch(): SourceResult {
                        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
                            eraseColor(if (data.lastPathSegment?.endsWith("red.png") == true) red else blue)
                        }
                        val bytes = ByteArrayOutputStream().use { output ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); bitmap.recycle(); output.toByteArray()
                        }
                        return SourceResult(ImageSource(Buffer().write(bytes), context), "image/png", DataSource.NETWORK)
                    }
                }
            }
        }) }.build()
        var song by mutableStateOf(fixture().copy(id = "switch-red", title = "红色唱片", cover = "$prefix-red.png"))
        var shown by mutableStateOf(true)
        val output = File(context.filesDir, "qa/performance").apply { mkdirs() }
        fun rootFrame(name: String): Int {
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            File(output, "switch-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return bitmap.getPixel(8, bitmap.height / 2)
        }
        fun coverColor(): Int {
            val bitmap = compose.onNodeWithTag("player-cover").captureToImage().asAndroidBitmap()
            return bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        }
        Coil.setImageLoader(loader)
        clearArtworkColorCache()
        try {
            runBlocking(Dispatchers.IO) { preparePlayerArtwork(context, song.cover) }
            compose.setContent { if (shown) AmiTheme("dark") {
                CompositionLocalProvider(LocalVisualsActive provides false) {
                    PlayerContent(song, PlaybackState(duration = 90000), null, false, PlayerActions())
                }
            } }
            compose.waitUntil(10000) { coverColor() == red }
            compose.mainClock.advanceTimeBy(1000)
            val start = rootFrame("start")
            val next = song.copy(id = "switch-blue", title = "蓝色唱片", cover = "$prefix-blue.png")
            runBlocking(Dispatchers.IO) { preparePlayerArtwork(context, next.cover) }
            assertNotNull(loader.memoryCache?.get(coil.memory.MemoryCache.Key(next.cover!!)))
            compose.mainClock.autoAdvance = false
            compose.runOnIdle { song = next }
            compose.mainClock.advanceTimeBy(160)
            val middle = rootFrame("160ms")
            compose.mainClock.advanceTimeBy(1000)
            val end = rootFrame("end")
            compose.onNodeWithText("蓝色唱片").assertIsDisplayed()
            compose.onNodeWithText("红色唱片").assertDoesNotExist()
            // Coil's painter uses Android uptime, independently of the controlled Compose clock.
            compose.mainClock.autoAdvance = true
            compose.waitUntil(10000) { coverColor() == blue }
            assertTrue("A prewarmed cover must still crossfade; Coil's default skips memory hits", cachedCoverFades.get())
            rootFrame("settled")
            assertTrue(android.graphics.Color.red(start) > android.graphics.Color.blue(start))
            assertTrue(android.graphics.Color.blue(end) > android.graphics.Color.red(end))
            fun distance(a: Int, b: Int) = kotlin.math.abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)) +
                kotlin.math.abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b))
            assertTrue("Palette must have a real intermediate frame", distance(start, middle) > 5 && distance(middle, end) > 5)
            File(output, "switch-palette.json").writeText(org.json.JSONObject().put("start", start).put("160ms", middle)
                .put("end", end).put("phoneFrameBenchmark", false).toString(2))
        } finally {
            compose.mainClock.autoAdvance = true
            compose.runOnIdle { shown = false }
            Coil.setImageLoader(original); loader.shutdown(); clearArtworkColorCache()
        }
    }
}
