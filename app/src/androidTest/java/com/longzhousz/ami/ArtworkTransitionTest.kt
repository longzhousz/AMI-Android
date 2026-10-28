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
}
