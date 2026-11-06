package com.longzhousz.ami

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longzhousz.ami.core.Song
import com.longzhousz.ami.playback.PlaybackState
import com.longzhousz.ami.ui.AmiTheme
import com.longzhousz.ami.ui.PlayerActions
import com.longzhousz.ami.ui.PlayerContent
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TrackHeadingTest {
    @get:Rule val compose = createComposeRule()
    private val song =
        Song(
            "heading",
            "file:///unused",
            folder = "qa",
            title = "在漫长的旅途中，听见晚风经过我们的身旁 (Live at the End of a Beautiful Day)",
            artist = "AMI",
            duration = 90_000,
            lyrics = "[00:00]听见晚风\n[00:30]经过身旁",
        )

    @Test fun longHeadingUnfoldsContinuouslyAndCanReverseMidAnimation() {
        compose.setContent {
            AmiTheme("dark") {
                PlayerContent(song, PlaybackState(duration = 90_000), null, false, PlayerActions())
            }
        }
        assertTrue(titleLayout().isLineEllipsized(0))
        compose.mainClock.autoAdvance = false
        val folded = headingHeight()
        compose.onNodeWithTag("player-track-heading").performClick()
        compose.mainClock.advanceTimeBy(80)
        val middle = headingHeight()
        compose.onNodeWithText("AMI").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(900)
        val opened = headingHeight()
        assertTrue("Height must animate, not jump", middle > folded && middle < opened)
        assertTrue(titleLayout().lineCount > 1)
        assertFalse(titleLayout().isLineEllipsized(titleLayout().lineCount - 1))
        compose.onNodeWithContentDescription("播放").assertIsDisplayed()
        compose.onNodeWithContentDescription("播放队列").assertIsDisplayed()
        screenshot("player-title-expanded")
        compose.onNodeWithTag("player-track-heading").performClick()
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithTag("player-track-heading").performClick()
        compose.mainClock.advanceTimeBy(900)
        assertEquals(opened, headingHeight(), 1f)
        compose.onNodeWithTag("player-cover").assertIsDisplayed()
    }

    @Test fun headingStaysExpandedAcrossPagesAndResetsForANewSong() {
        val current = mutableStateOf(song)
        compose.setContent {
            AmiTheme("dark") {
                PlayerContent(current.value, PlaybackState(duration = 90_000), null, false, PlayerActions())
            }
        }
        compose.onNodeWithTag("player-track-heading").performClick()
        compose.onNodeWithContentDescription("切换到歌词").performClick()
        assertTrue(titleLayout().lineCount > 1)
        screenshot("player-title-lyrics")
        compose.onNodeWithTag("player-track-heading").performClick()
        assertTrue(titleLayout().isLineEllipsized(0))
        compose.onNodeWithTag("player-track-heading").performClick()
        compose.runOnIdle { current.value = song.copy(id = "next-heading") }
        assertEquals(1, titleLayout().lineCount)
        assertTrue(titleLayout().isLineEllipsized(0))
    }

    @Test fun largeFontAndVeryLongTitleLeaveTransportControlsAccessible() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                AmiTheme("dark") {
                    Box(Modifier.width(320.dp).fillMaxHeight()) {
                        PlayerContent(song.copy(title = song.title.repeat(4)), PlaybackState(duration = 90_000), null, false, PlayerActions())
                    }
                }
            }
        }
        compose.onNodeWithTag("player-track-heading").performClick()
        val layout = titleLayout()
        assertTrue(layout.lineCount > 5)
        assertFalse(layout.isLineEllipsized(layout.lineCount - 1))
        compose.onNodeWithContentDescription("播放").assertIsDisplayed()
        compose.onNodeWithContentDescription("播放队列").assertIsDisplayed()
        screenshot("player-title-large-font")
        compose.onNodeWithTag("player-track-title", useUnmergedTree = true).performTouchInput { swipeUp() }
        compose.onNodeWithTag("player-track-heading").performClick()
        assertEquals(1, titleLayout().lineCount)
    }

    private fun headingHeight() =
        compose
            .onNodeWithTag("player-track-heading")
            .fetchSemanticsNode()
            .boundsInRoot.height

    private fun titleLayout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose
            .onNodeWithTag("player-track-title", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }

    private fun screenshot(name: String) {
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "qa/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
