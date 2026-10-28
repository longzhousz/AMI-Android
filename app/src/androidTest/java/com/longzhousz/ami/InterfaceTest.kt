package com.longzhousz.ami

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import java.io.File

@RunWith(AndroidJUnit4::class)
class InterfaceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun openLocalLibrary() {
        compose.onNodeWithContentDescription("设置").performClick()
        compose.onNodeWithText("浏览本地音乐").performScrollTo().performClick()
    }

    @Test fun songSelectionStartsOnArtworkAndReopeningRemembersEitherPage() {
        val model = ViewModelProvider(compose.activity)[AmiViewModel::class.java]
        val songs = (0..1).map { n -> Song("qa-player-page-$n", "content://com.longzhousz.ami.test.fixtures/document/album.wav",
            folder = "qa-player-page", title = "播放页记忆 $n", duration = 6000, lyrics = "[00:00]当前歌曲 $n\n[00:03]继续听歌") }
        fun open() { compose.onNodeWithTag("mini-player").performClick() }
        fun close() { compose.onNodeWithContentDescription("收起播放页").performClick() }
        try {
            songs.forEach(model.repository.db::upsert); model.repository.load(); model.repository.reload()
            compose.waitUntil(30000) { model.player.state.value.connected }
            compose.runOnIdle { model.play(songs); model.player.pause() }
            compose.waitUntil(10000) { model.player.state.value.id == songs[0].id }
            open()
            compose.onNodeWithContentDescription("切换到歌词").assertIsDisplayed()
            compose.onNodeWithTag("lyrics-stage").assertDoesNotExist()
            compose.onNodeWithContentDescription("切换到歌词").performClick()
            compose.onNodeWithTag("lyrics-stage").assertIsDisplayed()
            close(); open()
            compose.onNodeWithContentDescription("切换到封面").assertIsDisplayed()
            compose.onNodeWithTag("lyrics-stage").assertIsDisplayed()
            compose.onNodeWithContentDescription("切换到封面").performClick()
            close(); open()
            compose.onNodeWithContentDescription("切换到歌词").assertIsDisplayed()
            compose.onNodeWithContentDescription("切换到歌词").performClick()
            close()
            // Explicitly choosing even the same song is a new selection, unlike reopening its bar.
            compose.runOnIdle { model.play(songs); model.player.pause() }
            open()
            compose.onNodeWithContentDescription("切换到歌词").assertIsDisplayed()
            compose.onNodeWithContentDescription("切换到歌词").performClick()
            close()
            compose.runOnIdle { model.player.next() }
            compose.waitUntil(10000) { model.player.state.value.id == songs[1].id }
            open()
            compose.onNodeWithContentDescription("切换到歌词").assertIsDisplayed()
        } finally {
            compose.runOnIdle { model.player.pause(); songs.forEach { model.player.remove(it.id) } }
            songs.forEach { model.repository.db.remove(it.id) }; model.repository.reload()
        }
    }

    @Test fun frequentSongCacheLimitIsAdjustableAndPersisted() {
        val model = ViewModelProvider(compose.activity)[AmiViewModel::class.java]
        val previous = model.repository.audioCacheLimit.value
        try {
            compose.runOnIdle { model.repository.setAudioCacheLimit(512L * 1024 * 1024) }
            compose.onNodeWithContentDescription("设置").performClick()
            compose.onNodeWithText("常听歌曲缓存上限").performScrollTo().performClick()
            compose.onNodeWithText("1 GB").performClick()
            compose.runOnIdle {
                assertEquals(1024L * 1024 * 1024, model.repository.audioCacheLimit.value)
                assertEquals(model.repository.audioCacheLimit.value, model.repository.prefs.getLong("onlineCacheLimit", 0))
            }
        } finally { compose.runOnIdle { model.repository.setAudioCacheLimit(previous) } }
    }

    @Test fun returningFromSettingsPreservesLibraryScroll() {
        val model = ViewModelProvider(compose.activity)[AmiViewModel::class.java]
        val songs = (0 until 60).map { index ->
            Song("qa-scroll-$index", "file:///unused-$index", folder = "qa-scroll", title = "滚动验证 ${index.toString().padStart(2, '0')}", importedAt = index.toLong())
        }
        try {
            songs.forEach { model.repository.db.upsert(it) }
            model.repository.load()
            model.repository.reload()
            openLocalLibrary()
            compose.waitUntil(30000) { compose.onAllNodesWithText(songs.first().title).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("library-song-list").performScrollToIndex(35)
            compose.onNodeWithText(songs[35].title).assertIsDisplayed()
            openLocalLibrary()
            compose.onNodeWithText(songs[35].title).assertIsDisplayed()
            compose.onNodeWithText(songs.first().title).assertDoesNotExist()
        } finally {
            songs.forEach { model.repository.db.remove(it.id) }
            model.repository.reload()
        }
    }

    @Test fun emptyLibraryNavigationAndDarkAppearanceRender() {
        compose.waitUntil(30000) { compose.onAllNodesWithText("登录网易云").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("登录后，音乐就在这里").assertIsDisplayed()
        compose.onNodeWithContentDescription("曲库").assertIsDisplayed()
        compose.onNodeWithContentDescription("网易云").assertDoesNotExist()
        compose.onNodeWithContentDescription("刷新网易云").assertDoesNotExist()
        compose.onNodeWithContentDescription("导入音乐文件夹").assertDoesNotExist()
        screenshot("empty-library")
        compose.onNodeWithContentDescription("我喜欢").performClick()
        compose.onAllNodesWithText("我喜欢").assertCountEquals(1)
        compose.onNodeWithText("登录网易云").assertIsDisplayed()
        compose.onNodeWithContentDescription("设置").performClick()
        compose.onNodeWithText("网易云账号").assertIsDisplayed()
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
            model.repository.load()
            model.repository.reload()
            openLocalLibrary()
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
            compose.onNodeWithText("本地喜欢").performClick()
            compose.onAllNodesWithText("本地喜欢").assertCountEquals(1)
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
            compose.onNodeWithText("本地喜欢").performClick()
            compose.onNodeWithText("专辑").performClick()
            screenshot("library-albums")
            compose.onNodeWithText("晚风与回声").performClick()
            compose.onNodeWithText(songs.first().title).assertIsDisplayed()
            compose.onNodeWithText("曲序").assertIsDisplayed()
            screenshot("album-tracks")
            compose.onNodeWithContentDescription("返回曲库").performClick()
            compose.onNodeWithText("歌曲").performClick()
            compose.waitUntil(30000) { model.player.state.value.connected }
            // This flow verifies the UI with a two-second fixture. Pause in the same main-thread
            // action so a slow renderer cannot advance the queue before the window assertions.
            compose.onNodeWithText(songs.first().title).performSemanticsAction(SemanticsActions.OnClick) { click ->
                assertTrue(click())
                model.player.pause()
            }
            compose.waitUntil(10000) { model.player.state.value.id == songs.first().id }
            val mini = compose.onNodeWithTag("mini-player").fetchSemanticsNode().boundsInRoot
            val perf = File(compose.activity.filesDir, "qa/performance").apply { mkdirs() }
            shell("dumpsys gfxinfo ${compose.activity.packageName} reset")
            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag("mini-player").performClick()
            compose.mainClock.advanceTimeBy(1500)
            compose.runOnIdle { assertFalse(compose.activity.window.isNavigationBarContrastEnforced) }
            val distance = mini.top * .85f
            compose.onNodeWithTag("player-header").performTouchInput {
                down(center); moveBy(Offset(0f, distance), delayMillis = 400)
            }
            compose.mainClock.advanceTimeBy(32)
            val face = compose.onNodeWithTag("player-mini-face", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals("The handoff face must have the floating bar's left edge", mini.left, face.left, 1f)
            assertEquals("The handoff face must have the floating bar's width", mini.width, face.width, 1f)
            screenshot("player-collapse-handoff")
            compose.runOnIdle { assertFalse(compose.activity.window.isNavigationBarContrastEnforced) }
            compose.onNodeWithTag("player-header").performTouchInput {
                moveBy(Offset(0f, -distance), delayMillis = 400); advanceEventTime(400); up()
            }
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            assertEquals(0f, compose.onNodeWithTag("player-sheet").fetchSemanticsNode().boundsInRoot.top, 1f)
            File(perf, "player-collapse-reverse-gfxinfo.txt").writeText(shell("dumpsys gfxinfo ${compose.activity.packageName} framestats"))
            compose.onNodeWithTag("full-player").assertIsDisplayed()
            compose.onNodeWithContentDescription("切换到歌词").assertIsDisplayed().performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("lyrics-stage").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("lyrics-stage").assertIsDisplayed()
            val bitmap = compose.onNodeWithTag("full-player").captureToImage().asAndroidBitmap()
            val directory = File(compose.activity.filesDir, "qa").apply { mkdirs() }
            File(directory, "lyrics-player-window.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            compose.onNodeWithContentDescription("播放队列").performClick()
            compose.onNodeWithText("播放队列 · ${songs.size}").assertIsDisplayed()
            compose.waitUntil(10000) { !compose.activity.hasWindowFocus() }
            pressSystemBack()
            compose.waitUntil(10000) { compose.onAllNodesWithText("播放队列 · ${songs.size}").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("full-player").assertIsDisplayed()
            compose.onNodeWithContentDescription("收起播放页").performClick()
            compose.onNodeWithTag("mini-player").assertIsDisplayed()
            val landed = compose.onNodeWithTag("mini-player").fetchSemanticsNode().boundsInRoot
            assertEquals(mini.left, landed.left, 1f); assertEquals(mini.top, landed.top, 1f); assertEquals(mini.width, landed.width, 1f)
            screenshot("player-minibar-corners")
            compose.onNodeWithTag("mini-player").performClick()
            compose.onNodeWithTag("player-header").performTouchInput {
                swipe(Offset(centerX, height * .4f), Offset(centerX, height + 200f), durationMillis = 300)
            }
            compose.onNodeWithTag("full-player").assertDoesNotExist()
            compose.onNodeWithTag("mini-player").performClick()
            compose.onNodeWithTag("full-player").assertIsDisplayed()
            compose.waitUntil(10000) { compose.activity.hasWindowFocus() }
            pressSystemBack()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("full-player").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("full-player").assertDoesNotExist()
            compose.onNodeWithTag("mini-player").assertIsDisplayed()
        } catch (failure: Throwable) {
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            val windows = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("dumpsys window windows"))
                .bufferedReader().use { it.readText() }
            val focus = windows.lineSequence().filter {
                it.contains("mCurrentFocus") || it.contains("mFocusedApp") || it.contains("Window #")
            }.joinToString("\n")
            throw AssertionError("${failure.message}\nWindows at failure:\n$focus", failure)
        } finally {
            compose.runOnIdle { model.player.pause(); songs.forEach { model.player.remove(it.id) } }
            songs.forEach { model.repository.db.remove(it.id) }; model.repository.reload()
        }
    }
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }
    private fun pressSystemBack() {
        recoverEmulatorSystemUiOverlay()
        // Route through the system so native sheets and the activity receive the same Back
        // action as accessibility/navigation controls, without Espresso choosing an old root.
        assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation
            .performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
        compose.waitForIdle()
    }

    private fun recoverEmulatorSystemUiOverlay() {
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.serviceInfo=automation.serviceInfo.apply { flags=flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        val root=(automation.windows.mapNotNull { it.root }+listOfNotNull(automation.rootInActiveWindow)).firstOrNull { window ->
            window.findAccessibilityNodeInfosByText("System UI isn't responding")
                .any { it.packageName?.toString()=="android" && it.text?.toString()=="System UI isn't responding" }
        } ?: return
        val emulator=ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("getprop ro.kernel.qemu"))
            .bufferedReader().use { it.readText().trim() }
        check(emulator=="1") { "System UI ANR recovery is emulator-only" }
        val output=File(compose.activity.filesDir,"qa/performance").apply { mkdirs() }
        val stamp=System.nanoTime()
        File(output,"system-ui-anr-$stamp.txt").writeText(ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand("dumpsys window windows"))
            .bufferedReader().use { it.readText() })
        automation.takeScreenshot()?.let { bitmap -> File(output,"system-ui-anr-$stamp.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle() }
        val wait=root.findAccessibilityNodeInfosByText("Wait")
            .single { it.packageName?.toString()=="android" && it.text?.toString()=="Wait" }
        check(wait.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "System UI Wait action failed" }
        compose.waitUntil(10000) {
            automation.rootInActiveWindow?.findAccessibilityNodeInfosByText("System UI isn't responding")
                ?.none { it.packageName?.toString()=="android" }==true
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val directory = File(compose.activity.filesDir, "qa").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
