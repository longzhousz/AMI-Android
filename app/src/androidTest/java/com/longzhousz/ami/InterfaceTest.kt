package com.longzhousz.ami

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longzhousz.ami.core.Song
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertTrue
import java.io.File

@RunWith(AndroidJUnit4::class)
class InterfaceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun emptyLibraryNavigationAndDarkAppearanceRender() {
        compose.waitUntil(30000) { compose.onAllNodesWithText("导入我的音乐").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("导入我的音乐").assertIsDisplayed()
        val labelHeight = compose.onNodeWithText("导入我的音乐", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.height
        val fontHeight = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 14f, compose.activity.resources.displayMetrics)
        assertTrue("Import label must retain its full text height", labelHeight >= fontHeight)
        screenshot("empty-library")
        compose.onNodeWithText("我喜欢").performClick()
        compose.onNodeWithText("点亮歌曲旁的爱心，收藏喜欢的声音。").assertIsDisplayed()
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("网易云歌词").performScrollTo().performClick()
        compose.onNodeWithText("选择歌词文件夹").assertIsDisplayed()
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithText("外观").performScrollTo().performClick()
        compose.onNodeWithText("深色").performClick()
        compose.onNodeWithText("外观").assertIsDisplayed()
        screenshot("settings-dark")
        compose.activityRule.scenario.onActivity { ViewModelProvider(it)[AmiViewModel::class.java].setTheme("system") }
        verifyFullPlayerWindow()
    }
    private fun verifyFullPlayerWindow() {
        val model = ViewModelProvider(compose.activity)[AmiViewModel::class.java]
        val song = Song("qa-player-window", "content://com.longzhousz.ami.test.fixtures/document/single.wav",
            folder = "qa", title = "全屏歌词验证", album = "AMI", duration = 2000, format = "WAV",
            lyrics = "[00:00.00]让歌词成为主角\n[00:01.00]让音乐留在身边")
        try {
            model.repository.db.upsert(song); model.repository.reload()
            compose.waitUntil(30000) { model.player.state.value.connected }
            compose.runOnIdle { model.play(listOf(song)); model.player.pause() }
            compose.waitUntil(10000) { model.player.state.value.id == song.id }
            compose.onNodeWithTag("mini-player").performClick()
            compose.onNodeWithTag("full-player").assertIsDisplayed()
            compose.onNodeWithTag("lyrics-stage").assertIsDisplayed()
            val bitmap = compose.onNodeWithTag("full-player").captureToImage().asAndroidBitmap()
            val directory = File(compose.activity.filesDir, "qa").apply { mkdirs() }
            File(directory, "lyrics-player-window.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            compose.onNodeWithText("队列").performClick()
            compose.onNodeWithText("播放队列 · 1").assertIsDisplayed()
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            compose.onNodeWithTag("full-player").assertIsDisplayed()
            compose.onNodeWithContentDescription("收起播放页").performClick()
            compose.onNodeWithTag("mini-player").assertIsDisplayed()
            compose.onNodeWithTag("mini-player").performClick()
            compose.onNodeWithTag("player-header").performTouchInput {
                swipe(Offset(centerX, height * .4f), Offset(centerX, height + 200f), durationMillis = 300)
            }
            compose.onNodeWithTag("full-player").assertDoesNotExist()
        } finally {
            compose.runOnIdle { model.player.pause(); model.player.remove(song.id) }
            model.repository.db.remove(song.id); model.repository.reload()
        }
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val directory = File(compose.activity.filesDir, "qa").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
