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
import com.longzhousz.ami.core.Lyrics
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
class LyricsPresentationTest {
    @get:Rule val compose = createComposeRule()
    private val phrases = listOf(
        "风从海面吹来", "把昨天轻轻翻过", "光落在你经过的地方", "我们沿着晚风，走向下一场日落",
        "把每个平凡的瞬间，唱成一首温柔的歌", "遠い街の灯りが、静かな夜に溶けてゆく",
        "Let the quiet moments carry us home", "还有未曾说完的故事", "留给明天慢慢听", "让这首歌，陪你走远一些",
    )
    private val raw = phrases.mapIndexed { i, text -> "[00:${(i * 6).toString().padStart(2, '0')}.00]$text" }.joinToString("\n")
    private val translations = listOf(
        "A breeze arrives from the sea", "Gently turn the page on yesterday", "Light falls where you have been",
        "Follow the evening breeze into another sunset", "Turn the ordinary into a gentle song", "远处的灯光，融入安静的夜色",
        "让安静的时光，带我们回家", "There are stories still to tell", "Listen slowly tomorrow", "May this song stay with you",
    )
    private val song = Song("presentation", "file:///unused", folder = "qa", title = "光经过的地方", artist = "AMI · 听见日常", album = "晚风与回声", duration = 90000, format = "FLAC", lyrics = raw)

    @Test fun bilingualLyricsFollowSeekAndRespectManualBrowsing() {
        val position = mutableLongStateOf(13000)
        var sought = -1L
        compose.setContent {
            AmiTheme("light") {
                PlayerContent(song, PlaybackState(position = position.longValue, duration = 90000), translations, false,
                    PlayerActions(seek = { sought = it; position.longValue = it }))
            }
        }
        compose.onNodeWithTag("lyric-2").assertIsSelected().assertIsDisplayed()
        compose.onNodeWithText(translations[2]).assertIsDisplayed()
        assertAnchored(2)
        screenshot("lyrics-immersive")
        compose.onNodeWithTag("lyric-3").performClick()
        compose.runOnIdle { assertEquals(18000, sought) }
        compose.onNodeWithTag("lyric-3").assertIsSelected()
        compose.onNodeWithTag("lyrics-list").performTouchInput { swipeUp() }
        compose.onNodeWithText("回到当前句").assertIsDisplayed()
        compose.runOnIdle { position.longValue = 6000 }
        compose.onNodeWithText("回到当前句").assertIsDisplayed().performClick()
        compose.onNodeWithTag("lyric-1").assertIsSelected().assertIsDisplayed()
        assertAnchored(1)
        compose.onNodeWithContentDescription("隐藏译文").performClick()
        compose.onNodeWithText(translations[1]).assertDoesNotExist()
        compose.onNodeWithTag("lyric-1").assertIsDisplayed()
        compose.onNodeWithContentDescription("切换到封面").performClick()
        compose.onNodeWithTag("player-cover").assertIsDisplayed()
        compose.onNodeWithContentDescription("切换到歌词").performClick()
        compose.onNodeWithTag("lyric-1").assertIsDisplayed()
        compose.onNodeWithTag("playback-seek").performSemanticsAction(SemanticsActions.SetProgress) { it(42000f) }
        compose.runOnIdle { assertEquals(42000, sought) }
        compose.onNodeWithTag("lyric-7").assertIsSelected().assertIsDisplayed()
    }

    @Test fun narrowScreenAndLargeFontsKeepLongLyricsAndControlsReadable() {
        val longLine = "我们沿着晚风，把每一个平凡的瞬间，慢慢唱成一首温柔的歌。"
        val largeSong = song.copy(lyrics = "[00:00.00]$longLine\n[00:30.00]还有未曾说完的故事")
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                AmiTheme("dark") {
                    Box(Modifier.width(320.dp).fillMaxHeight()) {
                        PlayerContent(largeSong, PlaybackState(position = 1000, duration = 90000), listOf("Let all the ordinary moments become a gentle song.", ""), false, PlayerActions())
                    }
                }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(longLine, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("Long text must wrap rather than truncate", layouts.single().lineCount >= 3)
        assertFalse(layouts.single().didOverflowHeight)
        assertFalse(layouts.single().didOverflowWidth)
        compose.onNodeWithContentDescription("播放").assertIsDisplayed()
        compose.onNodeWithContentDescription("播放队列").assertIsDisplayed()
        compose.onNodeWithContentDescription("隐藏译文").assertIsDisplayed()
        screenshot("lyrics-large-font")
    }

    @Test fun untimedLyricsStayReadableWithoutFalseHighlightOrSeek() {
        val plain = song.copy(lyrics = "这是一段没有时间轴的歌词\n可以从容地阅读\n也可以随意向下翻阅")
        compose.setContent {
            AmiTheme("light") { PlayerContent(plain, PlaybackState(), null, false, PlayerActions()) }
        }
        compose.onNodeWithTag("lyric-0").assertIsNotSelected().assertIsNotEnabled()
        compose.onNodeWithText("暂无时间轴 · 可自由滚动阅读").assertIsDisplayed()
        screenshot("lyrics-untimed")
    }

    @Test fun cloudPlayerUsesIconsAndShowsTheCurrentSourceQualityInItsMenu() {
        compose.setContent {
            AmiTheme("dark") { PlayerContent(song.copy(id = "netease:123", uri = "ami-cloud://song/123"), PlaybackState(), null, false, PlayerActions(), audioQuality = "无损") }
        }
        compose.onNodeWithText("封面").assertDoesNotExist()
        compose.onNodeWithText("翻译").assertDoesNotExist()
        compose.onNodeWithText("队列").assertDoesNotExist()
        compose.onNodeWithContentDescription("切换到封面").assertIsDisplayed()
        compose.onNodeWithContentDescription("翻译歌词").assertIsDisplayed()
        compose.onNodeWithContentDescription("播放队列").assertIsDisplayed()
        compose.onNodeWithContentDescription("歌词选项").performClick()
        compose.onNodeWithText("当前音质：无损").assertIsDisplayed()
    }

    private fun assertAnchored(index: Int) {
        compose.waitForIdle()
        val stage = compose.onNodeWithTag("lyrics-stage").fetchSemanticsNode().boundsInRoot
        val line = compose.onNodeWithTag("lyric-$index").fetchSemanticsNode().boundsInRoot
        assertTrue("Active paragraph must be inside the reading viewport", line.top >= stage.top && line.top < stage.top + stage.height * .5f)
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "qa").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
