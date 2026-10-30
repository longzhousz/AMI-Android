package com.longzhousz.ami

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longzhousz.ami.core.Song
import com.longzhousz.ami.ui.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PlaybackQueueSheetTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val songs = (0 until 1000).map { Song("queue-$it", "file:///unused-$it", folder = "qa", title = "队列歌曲 $it", artist = "AMI QA", duration = 180000) }
    private val ids = songs.map { it.id }
    private fun screenshot(name: String) {
        val bitmap = compose.onNodeWithTag("queue-surface").captureToImage().asAndroidBitmap()
        File(context.filesDir, "qa/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun repeatedOpenCloseAndLongQueueSelectionStayResponsive() {
        var show by mutableStateOf(false)
        var selected = -1
        compose.setContent { AmiTheme("light") {
            GlassBackdropHost(Modifier.fillMaxSize()) {
                Button({ show = true }) { Text("打开队列") }
                if (show) PlaybackQueueSheet(ids, ids[500], null, { id -> songs.firstOrNull { it.id == id } },
                    { show = false }, { selected = it }, {})
            }
        } }
        repeat(3) {
            compose.onNodeWithText("打开队列").performClick()
            compose.onNodeWithTag("queue-track-500").assertIsDisplayed()
            compose.onNodeWithContentDescription("关闭播放队列").performClick()
            compose.onNodeWithTag("queue-surface").assertDoesNotExist()
        }
        compose.onNodeWithText("打开队列").performClick()
        compose.onNodeWithTag("queue-tracks").performScrollToIndex(700)
        compose.onNodeWithTag("queue-track-700").assertIsDisplayed()
        screenshot("queue-long-list")
        compose.onNodeWithTag("queue-track-700").performClick()
        compose.onNodeWithTag("queue-surface").assertDoesNotExist()
        compose.runOnIdle { assertEquals(700, selected) }
    }

    @Test fun queueUsesItsOwnCoverPaletteAndDarkThemeOverALightPlayerHost() {
        val cover = File(context.cacheDir, "qa-queue-green.png")
        val source = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF16884B.toInt()) }
        cover.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }; source.recycle()
        try {
            compose.setContent { AmiTheme("light") { GlassBackdropHost(Modifier.fillMaxSize()) {
                PlaybackQueueSheet(ids.take(7), ids[2], cover.path, { id -> songs.firstOrNull { it.id == id } }, {}, {}, {})
            } } }
            compose.onNodeWithText("播放队列 · 7").assertIsDisplayed()
            compose.waitUntil(15000) {
                val bitmap = compose.onNodeWithTag("queue-surface").captureToImage().asAndroidBitmap()
                val pixel = bitmap.getPixel(bitmap.width / 10, bitmap.height / 20)
                android.graphics.Color.green(pixel) > android.graphics.Color.blue(pixel) + 8 &&
                    android.graphics.Color.red(pixel) + android.graphics.Color.green(pixel) + android.graphics.Color.blue(pixel) < 380
            }
            val sheet = compose.onNodeWithTag("queue-surface").fetchSemanticsNode().boundsInRoot
            val close = compose.onNodeWithContentDescription("关闭播放队列").fetchSemanticsNode().boundsInRoot
            assertTrue("The native sheet must finish opening, not clip its lower half", close.top < sheet.top + sheet.height * .25f)
            compose.onNodeWithTag("queue-track-6").assertIsDisplayed()
            screenshot("queue-dark-cover")
        } finally { cover.delete() }
    }

    @Test fun interruptingDismissalDoesNotLeaveTheQueueLocked() {
        var show by mutableStateOf(true)
        compose.setContent { AmiTheme("dark") {
            if (show) PlaybackQueueSheet(ids.take(7), ids[2], null, { id -> songs.firstOrNull { it.id == id } }, { show = false }, {}, {})
        } }
        compose.onNodeWithContentDescription("关闭播放队列").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithContentDescription("关闭播放队列").performClick()
        compose.mainClock.advanceTimeBy(32)
        compose.onNodeWithTag("queue-drag-handle").performTouchInput {
            down(center); moveBy(Offset(0f, -180f), delayMillis = 180); advanceEventTime(300); up()
        }
        compose.mainClock.advanceTimeBy(1800)
        compose.mainClock.autoAdvance = true
        compose.onNodeWithContentDescription("关闭播放队列").assertIsDisplayed().performClick()
        compose.onNodeWithTag("queue-surface").assertDoesNotExist()
    }
}
