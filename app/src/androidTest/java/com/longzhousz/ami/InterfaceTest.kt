package com.longzhousz.ami

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.swipe
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil.imageLoader
import com.longzhousz.ami.core.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class InterfaceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun titleOnlySettingsKeepBackupInformationAndSubmenuBackNavigation() {
        compose.onNodeWithContentDescription("设置").performClick()
        compose.onNodeWithText("我的聆听").assertIsDisplayed()
        compose.onNodeWithText("备份与恢复").assertDoesNotExist()
        compose.onNodeWithText("周、月、年 · 回看音乐留下的足迹").assertDoesNotExist()
        screenshot("settings-home")
        compose.onNodeWithText("存储与数据").performScrollTo().performClick()
        compose.onNodeWithTag("settings-storage").assertIsDisplayed()
        screenshot("settings-storage")
        compose.onNodeWithText("备份与恢复").performScrollTo().performClick()
        compose.onNodeWithText("导出数据").assertIsDisplayed()
        compose.onNodeWithText("导入恢复").assertIsDisplayed()
        compose.onNodeWithText("不包含登录凭据", substring = true).assertIsDisplayed()
        val bitmap = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
        File(compose.activity.filesDir, "qa/backup-restore-settings.png").apply { parentFile!!.mkdirs() }.outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5000) { compose.activity.hasWindowFocus() }
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithTag("settings-storage").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitUntil(5000) { compose.onAllNodesWithTag("settings-home").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("settings-home").assertIsDisplayed()
        compose.onNodeWithText("歌词与翻译").performScrollTo().performClick()
        compose.onNodeWithText("DeepSeek API Key").performClick()
        compose.onNodeWithText("用于翻译与获取官方模型列表").assertIsDisplayed()
        compose.onNodeWithText("密钥使用设备密钥加密", substring = true).assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("settings-lyrics").assertIsDisplayed()
        screenshot("settings-lyrics")
        compose.onNodeWithContentDescription("返回设置").performClick()
        compose.onNodeWithText("我的聆听").assertIsDisplayed()
    }

    @Test fun consumingAnOlderSnackbarCannotEraseANewerFailure() {
        val model = ViewModelProvider(compose.activity)[AmiViewModel::class.java]
        compose.runOnIdle {
            model.showMessage("older")
            model.showMessage("newer")
            model.consumeMessage("older")
            assertEquals("newer", model.message.value)
            model.consumeMessage("newer")
            assertEquals(null, model.message.value)
        }
    }

    @Test fun favoritesFollowTheLightThemeAndSettingsKeepDarkSystemIcons() {
        val model = ViewModelProvider(compose.activity)[AmiViewModel::class.java]
        val previous = model.theme.value
        try {
            compose.runOnIdle { model.setTheme("light") }
            compose.waitUntil(30000) { !model.repository.loading.value }
            compose.onNodeWithContentDescription("我喜欢").performClick()
            compose.waitUntil(10000) {
                compose.onAllNodesWithTag("favorites-hero").fetchSemanticsNodes().isNotEmpty() ||
                    compose.onAllNodesWithTag("local-library").fetchSemanticsNodes().isNotEmpty()
            }
            compose.runOnIdle {
                val bars =
                    androidx.core.view.WindowCompat
                        .getInsetsController(compose.activity.window, compose.activity.window.decorView)
                assertTrue(bars.isAppearanceLightStatusBars)
                assertTrue(bars.isAppearanceLightNavigationBars)
            }
            compose.onNodeWithContentDescription("设置").performClick()
            compose.onNodeWithText("我的聆听").assertIsDisplayed()
            compose.runOnIdle {
                val bars =
                    androidx.core.view.WindowCompat
                        .getInsetsController(compose.activity.window, compose.activity.window.decorView)
                assertTrue(bars.isAppearanceLightStatusBars)
                assertTrue(bars.isAppearanceLightNavigationBars)
            }
        } finally {
            compose.runOnIdle { model.setTheme(previous) }
        }
    }

    @Test fun listeningIsADirectSettingsEntryAndReturnsToSettings() {
        val model = ViewModelProvider(compose.activity)[AmiViewModel::class.java]
        val loading = model.repository.loading.value
        compose.onNodeWithContentDescription("设置").performClick()
        compose.onNodeWithText("我的聆听").assertIsDisplayed().performClick()
        compose.onNodeWithTag("listening-screen").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回设置").performClick()
        compose.onNodeWithText("我的聆听").assertIsDisplayed()
        assertEquals("The report must not request the local library", loading, model.repository.loading.value)
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(compose.activity.filesDir, "qa/listening-settings.png")
            .apply {
                parentFile!!.mkdirs()
            }.outputStream()
            .use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun openLocalLibrary() {
        compose.onNodeWithContentDescription("曲库").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("library-source-local").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("library-source-local").performClick()
    }

    @Test fun homeOffersImportAndShowsLocalAndCloudAfterMusicIsAdded() {
        val model = ViewModelProvider(compose.activity)[AmiViewModel::class.java]
        val previousTheme = model.theme.value
        val song =
            Song(
                "qa-local-source",
                "content://com.longzhousz.ami.test.fixtures/document/single.wav",
                folder = "qa-local-source",
                title = "本地来源验证",
                artist = "AMI QA",
                album = "本地专辑验证",
                duration = 2000,
            )
        val second = song.copy(id = "qa-local-source-2", title = "本地曲序验证", importedAt = song.importedAt + 1)
        try {
            compose.waitUntil(30000) { !model.repository.loading.value }
            compose.onNodeWithText("登录网易云").assertIsDisplayed()
            compose.onNodeWithText("导入本地音乐").assertIsDisplayed()
            assertEquals(
                compose
                    .onNodeWithText("登录网易云")
                    .fetchSemanticsNode()
                    .boundsInRoot.center.y,
                compose
                    .onNodeWithText("导入本地音乐")
                    .fetchSemanticsNode()
                    .boundsInRoot.center.y,
                2f,
            )
            compose.onNodeWithTag("library-source-local").assertDoesNotExist()
            model.repository.db.upsert(song)
            model.repository.db.upsert(second)
            model.repository.reload()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("library-source-local").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("library-source-local").assertIsSelected()
            compose.onNodeWithTag("library-mode-albums").assertIsSelected()
            compose.onNodeWithText("本地专辑验证").assertIsDisplayed()
            screenshot("local-library-albums")
            val album =
                model.repository.library.value.albums
                    .first { it.title == "本地专辑验证" }
            val card = compose.onNodeWithTag("local-album:${album.key}")
            val initial = card.fetchSemanticsNode().boundsInRoot
            compose.mainClock.autoAdvance = false
            compose.onNodeWithTag("library-mode-songs").performClick()
            compose.mainClock.advanceTimeBy(96)
            compose.onNodeWithTag("library-mode-albums").performClick()
            compose.mainClock.advanceTimeBy(16)
            val rootAtSwitch = compose.onRoot().fetchSemanticsNode().boundsInRoot
            compose.onRoot().performTouchInput { click(initial.center - rootAtSwitch.topLeft) }
            compose.mainClock.advanceTimeBy(1500)
            compose.mainClock.autoAdvance = true
            val cover = compose.onNodeWithTag("local-collection-cover").fetchSemanticsNode().boundsInRoot
            assertTrue("The local album cover should expand like the online shelf", cover.width > initial.width + 10)
            compose.onNodeWithText("曲序").performClick()
            compose.onNodeWithText("调整曲序").assertIsDisplayed()
            compose.onNodeWithContentDescription("下移 本地来源验证").performClick()
            compose.onNodeWithText("保存曲序").performClick()
            compose.waitUntil(10000) {
                model.repository.library.value.albumsByKey[album.key]
                    ?.songs
                    ?.map { it.id } == listOf(second.id, song.id)
            }
            compose.onNodeWithContentDescription("本地来源验证的更多操作").performClick()
            compose.onNodeWithText("加入喜欢").performClick()
            compose.waitUntil(10000) {
                model.repository.library.value.songsById[song.id]
                    ?.favorite == true
            }
            compose.onNodeWithContentDescription("返回曲库").performClick()
            assertEquals(initial.top, card.fetchSemanticsNode().boundsInRoot.top, 2f)
            compose.mainClock.autoAdvance = false
            card.performClick()
            compose.mainClock.advanceTimeBy(96)
            compose.onNodeWithContentDescription("返回曲库").performClick()
            compose.mainClock.advanceTimeBy(16)
            val point = card.fetchSemanticsNode().boundsInRoot.center
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            compose.onRoot().performTouchInput { click(point - root.topLeft) }
            compose.mainClock.advanceTimeBy(1500)
            compose.mainClock.autoAdvance = true
            compose.onNodeWithTag("local-collection-cover").assertIsDisplayed()
            compose.onNodeWithContentDescription("返回曲库").performClick()
            compose.onNodeWithTag("library-mode-songs").performClick()
            compose.onNodeWithText("本地来源验证").assertIsDisplayed()
            compose.onNodeWithTag("library-source-cloud").performClick()
            compose.onNodeWithTag("library-source-cloud").assertIsSelected()
            compose.onNodeWithText("登录网易云").assertIsDisplayed()
            compose.onNodeWithTag("library-source-local").performClick()
            compose.onNodeWithTag("library-mode-songs").assertIsSelected()
            compose.onNodeWithText("本地来源验证").assertIsDisplayed()
            compose.runOnIdle { model.setTheme("dark") }
            screenshot("local-library-dark")
            compose.onNodeWithContentDescription("设置").performClick()
            compose.onNodeWithText("本地音乐").performScrollTo().performClick()
            compose.onNodeWithText("浏览本地音乐").assertDoesNotExist()
            compose.onNodeWithText("导入音乐").assertIsDisplayed()
        } finally {
            compose.mainClock.autoAdvance = true
            compose.runOnIdle { model.setTheme(previousTheme) }
            model.repository.db.remove(song.id)
            model.repository.db.remove(second.id)
            model.repository.reload()
        }
    }

    @Test fun localAlbumCoverAnimatesContinuouslyInBothDirections() {
        val model = ViewModelProvider(compose.activity)[AmiViewModel::class.java]
        val image = File(compose.activity.cacheDir, "qa-local-transition.png")
        android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFE91E63.toInt()) }.let { bitmap ->
            image.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val song = Song("qa-local-transition", "content://com.longzhousz.ami.test.fixtures/document/single.wav", folder = "qa", title = "动效歌曲", artist = "AMI QA", album = "动效专辑", cover = image.path, duration = 2000)

        fun frame(): android.graphics.Rect {
            compose.waitForIdle()
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()

            fun red(
                x: Int,
                y: Int,
            ): Boolean {
                val pixel = bitmap.getPixel(x, y)
                return android.graphics.Color.red(pixel) > 220 && android.graphics.Color.green(pixel) < 50 && android.graphics.Color.blue(pixel) in 75..120
            }
            var left = 0
            var right = -1
            var centerY = 0
            for (y in 0 until bitmap.height step 2) {
                var start = -1
                for (x in 0..bitmap.width + 1 step 2) {
                    if (x < bitmap.width && red(x, y)) {
                        if (start < 0) start = x
                    } else if (start >= 0) {
                        if (x - start > right - left) {
                            left = start
                            right = x
                            centerY = y
                        }
                        start = -1
                    }
                }
            }
            check(right > left) { "Local cover disappeared" }
            val centerX = ((left + right) / 4) * 2
            var top = centerY
            var bottom = centerY + 2
            while (top >= 2 && red(centerX, top - 2)) top -= 2
            while (bottom < bitmap.height && red(centerX, bottom)) bottom += 2
            return android.graphics.Rect(left, top, right, bottom)
        }

        try {
            model.repository.db.upsert(song)
            model.repository.load()
            model.repository.reload()
            openLocalLibrary()
            compose.waitUntil(10000) {
                compose.activity.imageLoader.memoryCache
                    ?.keys
                    ?.any { it.key.contains(image.path) } == true
            }
            val album =
                model.repository.library.value.albums
                    .first { it.title == song.album }
            val card = compose.onNodeWithTag("local-album:${album.key}")
            val start = frame()
            compose.mainClock.autoAdvance = false
            card.performClick()
            compose.mainClock.advanceTimeBy(80)
            val opening = frame()
            compose.mainClock.advanceTimeBy(1500)
            val end = frame()
            assertTrue("The cover must expand through an intermediate size: $start -> $opening -> $end", opening.width() > start.width() + 4 && opening.width() < end.width() - 4)
            compose.onNodeWithContentDescription("返回曲库").performClick()
            compose.mainClock.advanceTimeBy(16)
            val returning = frame()
            assertTrue("The cover must not jump at the start of the return: $end -> $returning", kotlin.math.abs(returning.left - end.left) <= 24 && kotlin.math.abs(returning.top - end.top) <= 24 && returning.width() >= end.width() - 24)
            compose.mainClock.advanceTimeBy(80)
            val shrinking = frame()
            assertTrue("The cover must shrink through an intermediate size: $returning -> $shrinking -> $start", shrinking.width() < returning.width() - 4 && shrinking.width() > start.width() + 4)
            compose.mainClock.advanceTimeBy(1500)
            compose.mainClock.autoAdvance = true
            card.assertIsDisplayed()
        } finally {
            compose.mainClock.autoAdvance = true
            model.repository.db.remove(song.id)
            model.repository.reload()
            image.delete()
        }
    }

    @Test fun localAlbumGridScrollsBehindTheTransparentStatusBar() {
        val model = ViewModelProvider(compose.activity)[AmiViewModel::class.java]
        val image = File(compose.activity.cacheDir, "qa-local-status.png")
        android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(0xFFE91E63.toInt()) }.let { bitmap ->
            image.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val songs =
            (0 until 24).map { index ->
                Song("qa-local-status-$index", "content://com.longzhousz.ami.test.fixtures/document/single.wav", folder = "qa", title = "歌曲 $index", artist = "AMI QA", album = "状态栏专辑 ${index.toString().padStart(2, '0')}", cover = image.path, duration = 2000)
            }
        try {
            songs.forEach { model.repository.db.upsert(it) }
            model.repository.load()
            model.repository.reload()
            openLocalLibrary()
            compose.waitUntil(10000) {
                compose.activity.imageLoader.memoryCache
                    ?.keys
                    ?.any { it.key.contains(image.path) } == true
            }
            val status =
                requireNotNull(
                    androidx.core.view.ViewCompat
                        .getRootWindowInsets(compose.activity.window.decorView),
                ).getInsets(
                    androidx.core.view.WindowInsetsCompat.Type
                        .statusBars(),
                ).top
            assertTrue("The test must include a status-bar inset", status > 0)
            val grid = compose.onNodeWithTag("local-album-grid")
            val rootTop =
                compose
                    .onRoot()
                    .fetchSemanticsNode()
                    .boundsInRoot.top
            assertEquals("The local grid must extend behind the status bar", rootTop, grid.fetchSemanticsNode().boundsInRoot.top, 1f)
            val firstAlbum =
                model.repository.library.value.albums
                    .first { it.title == songs.first().album }
            val first = compose.onNodeWithTag("local-album:${firstAlbum.key}")
            assertTrue("The first cover must start below the status bar", first.fetchSemanticsNode().boundsInRoot.top >= status)

            val targetAlbum = model.repository.library.value.albums[10]
            grid.performScrollToIndex(10)
            val card = compose.onNodeWithTag("local-album:${targetAlbum.key}")
            val before = card.fetchSemanticsNode().boundsInRoot
            val distance = before.top - status / 2f + 24f
            grid.performTouchInput { swipe(Offset(width * .5f, height * .5f), Offset(width * .5f, height * .5f - distance), 3000) }
            val after = card.fetchSemanticsNode().boundsInRoot
            assertTrue("The local cover must pass under the status bar: $after", after.top < status && after.bottom > status)
            val screenshot = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            val redUnderStatus =
                (0 until status step 2).any { y ->
                    (before.left.toInt() until before.right.toInt() step 2).any { x ->
                        val pixel = screenshot.getPixel(x, y)
                        android.graphics.Color.red(pixel) > 220 && android.graphics.Color.green(pixel) < 50 && android.graphics.Color.blue(pixel) in 75..120
                    }
                }
            assertTrue("The scrolling cover must remain visible behind the status bar", redUnderStatus)
            screenshot("local-library-scrolled-status")
        } finally {
            songs.forEach { model.repository.db.remove(it.id) }
            model.repository.reload()
            image.delete()
        }
    }

    @Test fun songSelectionStartsOnArtworkAndReopeningRemembersEitherPage() {
        val model = ViewModelProvider(compose.activity)[AmiViewModel::class.java]
        val songs =
            (0..1).map { n ->
                Song(
                    "qa-player-page-$n",
                    "content://com.longzhousz.ami.test.fixtures/document/album.wav",
                    folder = "qa-player-page",
                    title = "播放页记忆 $n",
                    duration = 6000,
                    lyrics = "[00:00]当前歌曲 $n\n[00:03]继续听歌",
                )
            }

        fun open() {
            compose.onNodeWithTag("mini-player").performClick()
        }

        fun close() {
            compose.onNodeWithContentDescription("收起播放页").performClick()
        }
        try {
            songs.forEach(model.repository.db::upsert)
            model.repository.load()
            model.repository.reload()
            compose.waitUntil(30000) { model.player.state.value.connected }
            compose.runOnIdle {
                model.play(songs)
                model.player.pause()
            }
            compose.waitUntil(10000) { model.player.state.value.id == songs[0].id }
            open()
            compose.onNodeWithContentDescription("切换到歌词").assertIsDisplayed()
            compose.onNodeWithTag("lyrics-stage").assertDoesNotExist()
            compose.onNodeWithContentDescription("切换到歌词").performClick()
            compose.onNodeWithTag("lyrics-stage").assertIsDisplayed()
            close()
            open()
            compose.onNodeWithContentDescription("切换到封面").assertIsDisplayed()
            compose.onNodeWithTag("lyrics-stage").assertIsDisplayed()
            compose.onNodeWithContentDescription("切换到封面").performClick()
            close()
            open()
            compose.onNodeWithContentDescription("切换到歌词").assertIsDisplayed()
            compose.onNodeWithContentDescription("切换到歌词").performClick()
            close()
            // Explicitly choosing even the same song is a new selection, unlike reopening its bar.
            compose.runOnIdle {
                model.play(songs)
                model.player.pause()
            }
            open()
            compose.onNodeWithContentDescription("切换到歌词").assertIsDisplayed()
            compose.onNodeWithContentDescription("切换到歌词").performClick()
            close()
            compose.runOnIdle { model.player.next() }
            compose.waitUntil(10000) { model.player.state.value.id == songs[1].id }
            open()
            compose.onNodeWithContentDescription("切换到歌词").assertIsDisplayed()
        } finally {
            compose.runOnIdle {
                model.player.pause()
                songs.forEach { model.player.remove(it.id) }
            }
            songs.forEach { model.repository.db.remove(it.id) }
            model.repository.reload()
        }
    }

    @Test fun frequentSongCacheLimitIsAdjustableAndPersisted() {
        val model = ViewModelProvider(compose.activity)[AmiViewModel::class.java]
        val previous = model.repository.audioCacheLimit.value
        try {
            compose.runOnIdle { model.repository.setAudioCacheLimit(512L * 1024 * 1024) }
            compose.onNodeWithContentDescription("设置").performClick()
            compose.onNodeWithText("存储与数据").performScrollTo().performClick()
            compose.onNodeWithText("常听歌曲缓存上限").performScrollTo().performClick()
            compose.onNodeWithText("1 GB").performClick()
            compose.runOnIdle {
                assertEquals(1024L * 1024 * 1024, model.repository.audioCacheLimit.value)
                assertEquals(model.repository.audioCacheLimit.value, model.repository.prefs.getLong("onlineCacheLimit", 0))
            }
        } finally {
            compose.runOnIdle { model.repository.setAudioCacheLimit(previous) }
        }
    }

    @Test fun returningFromSettingsPreservesLibraryScroll() {
        val model = ViewModelProvider(compose.activity)[AmiViewModel::class.java]
        val songs =
            (0 until 60).map { index ->
                Song(
                    "qa-scroll-$index",
                    "file:///unused-$index",
                    folder = "qa-scroll",
                    title = "滚动验证 ${index.toString().padStart(2, '0')}",
                    importedAt = index.toLong(),
                )
            }
        try {
            songs.forEach { model.repository.db.upsert(it) }
            model.repository.load()
            model.repository.reload()
            openLocalLibrary()
            compose.onNodeWithTag("library-mode-songs").performClick()
            compose.waitUntil(30000) { compose.onAllNodesWithText(songs.first().title).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("library-song-list").performScrollToIndex(35)
            compose.onNodeWithText(songs[35].title).assertIsDisplayed()
            compose.onNodeWithContentDescription("设置").performClick()
            openLocalLibrary()
            compose.onNodeWithText(songs[35].title).assertIsDisplayed()
            compose.onNodeWithText(songs.first().title).assertDoesNotExist()
        } finally {
            songs.forEach { model.repository.db.remove(it.id) }
            model.repository.reload()
        }
    }

    @Test fun bottomFavoritesOpenLocalHeartsWithoutAskingForNeteaseLogin() {
        val model = ViewModelProvider(compose.activity)[AmiViewModel::class.java]
        val songs =
            listOf(
                Song("qa-bottom-favorite-0", "file:///unused-favorite-0", folder = "qa-bottom-favorites", title = "本地红心验证", importedAt = 2),
                Song("qa-bottom-favorite-1", "file:///unused-favorite-1", folder = "qa-bottom-favorites", title = "本地非红心验证", importedAt = 1),
            )
        try {
            compose.waitUntil(30000) { !model.repository.loading.value }
            songs.forEach { model.repository.db.upsert(it) }
            model.repository.db.favorite(songs[0].id, true)
            model.repository.reload()
            compose.onNodeWithContentDescription("我喜欢").performClick()
            if (model.cloud.configured.value) {
                // Both sources: the favorites tab starts on NetEase and offers the source switch.
                compose.waitUntil(10000) {
                    compose.onAllNodesWithTag("online-favorites").fetchSemanticsNodes().isNotEmpty() &&
                        compose.onAllNodesWithTag("library-source-local").fetchSemanticsNodes().size == 1
                }
                compose.onNodeWithTag("library-source-local").performClick()
            }
            compose.waitUntil(10000) { compose.onAllNodesWithTag("local-library").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("本地喜欢").assertIsDisplayed()
            compose.onNodeWithText(songs[0].title).assertIsDisplayed()
            compose.onNodeWithText(songs[1].title).assertDoesNotExist()
            compose.waitUntil(10000) { compose.onAllNodesWithText("登录网易云").fetchSemanticsNodes().isEmpty() }
            screenshot("local-favorites-tab")
            compose.onNodeWithContentDescription("${songs[0].title}的更多操作").performClick()
            compose.onNodeWithText("取消喜欢").performClick()
            compose.waitUntil(10000) {
                !model.repository.db
                    .songSummaries()
                    .single { it.id == songs[0].id }
                    .favorite
            }
        } finally {
            songs.forEach { model.repository.db.remove(it.id) }
            model.repository.reload()
        }
    }

    @Test fun emptyLibraryNavigationAndDarkAppearanceRender() {
        compose.waitUntil(30000) { compose.onAllNodesWithText("登录网易云").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("选择音乐来源").assertIsDisplayed()
        compose.onNodeWithText("导入本地音乐").assertIsDisplayed()
        compose.onNodeWithTag("library-source-local").assertDoesNotExist()
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
        compose.onNodeWithText("歌词与翻译").performScrollTo().performClick()
        compose.onNodeWithText("网易云歌词").performScrollTo().performClick()
        compose.onNodeWithText("选择歌词文件夹").assertIsDisplayed()
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithContentDescription("返回设置").performClick()
        compose.onNodeWithText("外观").performScrollTo().performClick()
        compose.onNodeWithText("深色").performClick()
        compose.onNodeWithText("外观").assertIsDisplayed()
        screenshot("settings-dark")
        compose.activityRule.scenario.onActivity { ViewModelProvider(it)[AmiViewModel::class.java].setTheme("system") }
        verifyFullPlayerWindow()
    }

    private fun verifyFullPlayerWindow() {
        val model = ViewModelProvider(compose.activity)[AmiViewModel::class.java]
        val song =
            Song(
                "qa-player-window",
                "content://com.longzhousz.ami.test.fixtures/document/single.wav",
                folder = "qa",
                title = "风经过海面",
                artist = "AMI · 界面验证",
                album = "晚风与回声",
                duration = 2000,
                format = "WAV",
                lyrics = "[00:00.00]让歌词成为主角\n[00:01.00]让音乐留在身边",
            )
        val songs =
            listOf("风经过海面", "暮色", "遥远的回声", "城市灯火", "纸飞机", "下一场日落").mapIndexed { index, title ->
                song.copy(
                    id = "qa-player-window-$index",
                    title = title,
                    album =
                        if (index <
                            3
                        ) {
                            "晚风与回声"
                        } else {
                            "海边来信"
                        },
                    favorite = index == 0 || index == 2,
                    importedAt = index.toLong(),
                )
            }
        try {
            // Import upsert preserves user state; seed favorites through their dedicated API.
            songs.forEach {
                model.repository.db.upsert(it)
                model.repository.db.favorite(it.id, it.favorite)
            }
            model.repository.load()
            model.repository.reload()
            openLocalLibrary()
            compose.onNodeWithTag("library-mode-songs").performClick()
            compose.onNodeWithText(songs.first().title).assertIsDisplayed()
            compose.onNodeWithContentDescription("导入音乐文件夹").assertDoesNotExist()
            screenshot("library-songs")
            compose.onNodeWithContentDescription("搜索本地音乐").performClick()
            compose.onNodeWithTag("library-search").performTextInput("遥远")
            compose.onNodeWithText(songs[2].title).assertIsDisplayed()
            compose.onNodeWithText(songs.first().title).assertDoesNotExist()
            compose.onNodeWithTag("library-search").performTextClearance()
            compose.onNodeWithTag("library-search").performImeAction()
            compose.onNodeWithContentDescription("收起搜索").performClick()
            compose.onNodeWithContentDescription("曲库").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("local-library").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("我喜欢").performClick()
            compose.waitUntil(10000) {
                compose.onAllNodesWithText("本地喜欢").fetchSemanticsNodes().isNotEmpty() ||
                    compose.onAllNodesWithTag("library-source-local").fetchSemanticsNodes().isNotEmpty()
            }
            if (compose.onAllNodesWithTag("library-source-local").fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithTag("library-source-local").performClick()
            }
            compose.waitUntil(10000) { compose.onAllNodesWithText("本地喜欢").fetchSemanticsNodes().isNotEmpty() }
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
            compose.onNodeWithContentDescription("曲库").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("library-source-local").fetchSemanticsNodes().isNotEmpty() }
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
                down(center)
                moveBy(Offset(0f, distance), delayMillis = 400)
            }
            compose.mainClock.advanceTimeBy(32)
            val face = compose.onNodeWithTag("player-mini-face", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals("The handoff face must have the floating bar's left edge", mini.left, face.left, 1f)
            assertEquals("The handoff face must have the floating bar's width", mini.width, face.width, 1f)
            screenshot("player-collapse-handoff")
            compose.runOnIdle { assertFalse(compose.activity.window.isNavigationBarContrastEnforced) }
            compose.onNodeWithTag("player-header").performTouchInput {
                moveBy(Offset(0f, -distance), delayMillis = 400)
                advanceEventTime(400)
                up()
            }
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            assertEquals(
                0f,
                compose
                    .onNodeWithTag("player-sheet")
                    .fetchSemanticsNode()
                    .boundsInRoot.top,
                1f,
            )
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
            compose.onNodeWithContentDescription("播放队列").performClick()
            compose.onNodeWithContentDescription("关闭播放队列").assertIsDisplayed().performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("播放队列 · ${songs.size}").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithContentDescription("播放队列").performClick()
            compose.onNodeWithTag("queue-track-0").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithText("播放队列 · ${songs.size}").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithContentDescription("收起播放页").performClick()
            compose.onNodeWithTag("mini-player").assertIsDisplayed()
            val landed = compose.onNodeWithTag("mini-player").fetchSemanticsNode().boundsInRoot
            assertEquals(mini.left, landed.left, 1f)
            assertEquals(mini.top, landed.top, 1f)
            assertEquals(mini.width, landed.width, 1f)
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
            val windows =
                ParcelFileDescriptor
                    .AutoCloseInputStream(automation.executeShellCommand("dumpsys window windows"))
                    .bufferedReader()
                    .use { it.readText() }
            val focus =
                windows
                    .lineSequence()
                    .filter {
                        it.contains("mCurrentFocus") || it.contains("mFocusedApp") || it.contains("Window #")
                    }.joinToString("\n")
            throw AssertionError("${failure.message}\nWindows at failure:\n$focus", failure)
        } finally {
            compose.runOnIdle {
                model.player.pause()
                songs.forEach { model.player.remove(it.id) }
            }
            songs.forEach { model.repository.db.remove(it.id) }
            model.repository.reload()
        }
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).bufferedReader().use {
            it.readText()
        }

    private fun pressSystemBack() {
        recoverEmulatorSystemUiOverlay()
        // Route through the system so native sheets and the activity receive the same Back
        // action as accessibility/navigation controls, without Espresso choosing an old root.
        assertTrue(
            InstrumentationRegistry
                .getInstrumentation()
                .uiAutomation
                .performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK),
        )
        compose.waitForIdle()
    }

    private fun recoverEmulatorSystemUiOverlay() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        val root =
            (automation.windows.mapNotNull { it.root } + listOfNotNull(automation.rootInActiveWindow)).firstOrNull { window ->
                window
                    .findAccessibilityNodeInfosByText("System UI isn't responding")
                    .any { it.packageName?.toString() == "android" && it.text?.toString() == "System UI isn't responding" }
            } ?: return
        val emulator =
            ParcelFileDescriptor
                .AutoCloseInputStream(automation.executeShellCommand("getprop ro.kernel.qemu"))
                .bufferedReader()
                .use { it.readText().trim() }
        check(emulator == "1") { "System UI ANR recovery is emulator-only" }
        val output = File(compose.activity.filesDir, "qa/performance").apply { mkdirs() }
        val stamp = System.nanoTime()
        File(output, "system-ui-anr-$stamp.txt").writeText(
            ParcelFileDescriptor
                .AutoCloseInputStream(automation.executeShellCommand("dumpsys window windows"))
                .bufferedReader()
                .use { it.readText() },
        )
        automation.takeScreenshot()?.let { bitmap ->
            File(output, "system-ui-anr-$stamp.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val wait =
            root
                .findAccessibilityNodeInfosByText("Wait")
                .single { it.packageName?.toString() == "android" && it.text?.toString() == "Wait" }
        check(wait.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "System UI Wait action failed" }
        compose.waitUntil(10000) {
            automation.rootInActiveWindow
                ?.findAccessibilityNodeInfosByText("System UI isn't responding")
                ?.none { it.packageName?.toString() == "android" } == true
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val directory = File(compose.activity.filesDir, "qa").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
