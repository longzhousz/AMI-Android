package com.longzhousz.ami

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performImeAction
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

    @Test fun returningFromSettingsPreservesLibraryScroll() {
        val model = ViewModelProvider(compose.activity)[AmiViewModel::class.java]
        val songs = (0 until 60).map { index ->
            Song("qa-scroll-$index", "file:///unused-$index", folder = "qa-scroll", title = "滚动验证 ${index.toString().padStart(2, '0')}", importedAt = index.toLong())
        }
        try {
            songs.forEach { model.repository.db.upsert(it) }
            model.repository.reload()
            compose.waitUntil(30000) { compose.onAllNodesWithText(songs.first().title).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("library-song-list").performScrollToIndex(35)
            compose.onNodeWithText(songs[35].title).assertIsDisplayed()
            compose.onNodeWithText("设置").performClick()
            compose.onNodeWithText("曲库").performClick()
            compose.onNodeWithText(songs[35].title).assertIsDisplayed()
            compose.onNodeWithText(songs.first().title).assertDoesNotExist()
        } finally {
            songs.forEach { model.repository.db.remove(it.id) }
            model.repository.reload()
        }
    }

    @Test fun emptyLibraryNavigationAndDarkAppearanceRender() {
        compose.waitUntil(30000) { compose.onAllNodesWithText("前往设置").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("暂无音乐").assertIsDisplayed()
        compose.onNodeWithContentDescription("导入音乐文件夹").assertDoesNotExist()
        screenshot("empty-library")
        compose.onNodeWithText("前往设置").performClick()
        compose.onNodeWithText("导入音乐").assertIsDisplayed()
        compose.onNodeWithText("我喜欢").performClick()
        compose.onAllNodesWithText("我喜欢").assertCountEquals(1)
        compose.onNodeWithText("暂无收藏").assertIsDisplayed()
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
            folder = "qa", title = "风经过海面", artist = "AMI · 界面验证", album = "晚风与回声", duration = 2000, format = "WAV",
            lyrics = "[00:00.00]让歌词成为主角\n[00:01.00]让音乐留在身边")
        val songs = listOf("风经过海面", "暮色", "遥远的回声", "城市灯火", "纸飞机", "下一场日落").mapIndexed { index, title ->
            song.copy(id = "qa-player-window-$index", title = title, album = if (index < 3) "晚风与回声" else "海边来信", favorite = index == 0 || index == 2, importedAt = index.toLong())
        }
        try {
            // Import upsert preserves user state; seed favorites through their dedicated API.
            songs.forEach { model.repository.db.upsert(it); model.repository.db.favorite(it.id, it.favorite) }
            model.repository.reload()
            compose.onNodeWithText("曲库").performClick()
            compose.onNodeWithText(songs.first().title).assertIsDisplayed()
            compose.onNodeWithContentDescription("导入音乐文件夹").assertDoesNotExist()
            screenshot("library-songs")
            compose.onNodeWithTag("library-search").performTextInput("遥远")
            compose.onNodeWithText(songs[2].title).assertIsDisplayed()
            compose.onNodeWithText(songs.first().title).assertDoesNotExist()
            compose.onNodeWithTag("library-search").performTextClearance()
            compose.onNodeWithTag("library-search").performImeAction()
            compose.onNodeWithContentDescription("排序").performClick()
            compose.onNodeWithText("最近导入").performClick()
            assertTrue(compose.onNodeWithText(songs.last().title).fetchSemanticsNode().boundsInRoot.top < compose.onNodeWithText(songs[3].title).fetchSemanticsNode().boundsInRoot.top)
            compose.onNodeWithContentDescription("排序").performClick()
            compose.onNodeWithText("默认排序").performClick()
            compose.onNodeWithText("我喜欢").performClick()
            compose.onAllNodesWithText("我喜欢").assertCountEquals(1)
            try {
                compose.onNodeWithText(songs.first().title).assertIsDisplayed()
            } catch (failure: AssertionError) {
                val tree = compose.onRoot().printToString()
                screenshot("favorites-failure")
                throw AssertionError("${failure.message}\n$tree", failure)
            }
            compose.onNodeWithText(songs[2].title).assertIsDisplayed()
            compose.onNodeWithText(songs[1].title).assertDoesNotExist()
            screenshot("favorites-songs")
            compose.onNodeWithText("曲库").performClick()
            compose.onNodeWithText("专辑").performClick()
            screenshot("library-albums")
            compose.onNodeWithText("晚风与回声").performClick()
            compose.onNodeWithText(songs.first().title).assertIsDisplayed()
            compose.onNodeWithText("曲序").assertIsDisplayed()
            screenshot("album-tracks")
            compose.onNodeWithContentDescription("返回曲库").performClick()
            compose.onNodeWithText("歌曲").performClick()
            compose.waitUntil(30000) { model.player.state.value.connected }
            compose.onNodeWithText(songs.first().title).performClick()
            compose.runOnIdle { model.player.pause() }
            compose.waitUntil(10000) { model.player.state.value.id == songs.first().id }
            compose.onNodeWithTag("mini-player").performClick()
            compose.onNodeWithTag("full-player").assertIsDisplayed()
            compose.onNodeWithTag("lyrics-stage").assertIsDisplayed()
            val bitmap = compose.onNodeWithTag("full-player").captureToImage().asAndroidBitmap()
            val directory = File(compose.activity.filesDir, "qa").apply { mkdirs() }
            File(directory, "lyrics-player-window.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            compose.onNodeWithText("队列").performClick()
            compose.onNodeWithText("播放队列 · ${songs.size}").assertIsDisplayed()
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            compose.onNodeWithTag("full-player").assertIsDisplayed()
            compose.onNodeWithContentDescription("收起播放页").performClick()
            compose.onNodeWithTag("mini-player").assertIsDisplayed()
            compose.onNodeWithTag("mini-player").performClick()
            compose.onNodeWithTag("player-header").performTouchInput {
                swipe(Offset(centerX, height * .4f), Offset(centerX, height + 200f), durationMillis = 300)
            }
            compose.onNodeWithTag("full-player").assertDoesNotExist()
            compose.onNodeWithTag("mini-player").performClick()
            compose.onNodeWithTag("full-player").assertIsDisplayed()
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            compose.onNodeWithTag("full-player").assertDoesNotExist()
            compose.onNodeWithTag("mini-player").assertIsDisplayed()
        } finally {
            compose.runOnIdle { model.player.pause(); songs.forEach { model.player.remove(it.id) } }
            songs.forEach { model.repository.db.remove(it.id) }; model.repository.reload()
        }
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val directory = File(compose.activity.filesDir, "qa").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
