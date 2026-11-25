package com.longzhousz.ami

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.longzhousz.ami.core.Album
import com.longzhousz.ami.core.LibrarySnapshot
import com.longzhousz.ami.core.Song
import com.longzhousz.ami.ui.AmiTheme
import com.longzhousz.ami.ui.LocalAlbumDetail
import com.longzhousz.ami.ui.LocalLibraryScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalLibraryLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun trackOrderIsOfferedOnlyWhenTheLocalAlbumHasAtLeastThreeSongs() {
        val songs = (1..3).map { Song("qa-order-$it", "file:///qa/$it", folder = "qa", title = "歌曲 $it") }
        var count by mutableIntStateOf(1)
        compose.setContent {
            AmiTheme("light") {
                Surface {
                    LocalAlbumDetail(
                        Album("qa-order", "专辑", "艺人", songs.take(count)),
                        currentId = null,
                        onBack = {},
                        onPlay = { _, _ -> },
                        onFavorite = {},
                        onRemove = {},
                        onReorder = {},
                        bottomInset = 24.dp,
                    )
                }
            }
        }
        val order = compose.onNodeWithText("曲序")
        order.assertDoesNotExist()
        compose.runOnIdle { count = 2 }
        order.assertDoesNotExist()
        compose.runOnIdle { count = 3 }
        order.assertIsDisplayed().performClick()
        compose.onNodeWithText("调整曲序").assertIsDisplayed()
        compose.runOnIdle { count = 2 }
        order.assertDoesNotExist()
        compose.onNodeWithText("调整曲序").assertDoesNotExist()
        compose.runOnIdle { count = 3 }
        order.assertIsDisplayed()
        compose.onNodeWithText("调整曲序").assertDoesNotExist()
    }

    @Test fun albumBrowsingHidesTheToolbarAndRestoresItAtTheSameCardOnReturn() {
        val library = LibrarySnapshot((10..49).map { Song("qa-immersion-$it", "file:///qa/$it", folder = "qa", title = "歌曲 $it", album = "专辑 $it") })
        var selectedAlbum by mutableStateOf<Album?>(null)
        compose.setContent {
            AmiTheme("light") {
                Surface {
                    LocalLibraryScreen(
                        library,
                        false,
                        false,
                        null,
                        24.dp,
                        selectedAlbum,
                        onCloud = {},
                        onRefresh = {},
                        onAlbum = { selectedAlbum = it },
                        onBack = { selectedAlbum = null },
                        onPlay = { _, _ -> },
                        onFavorite = {},
                        onRemove = {},
                        onReorder = {},
                    )
                }
            }
        }
        val grid = compose.onNodeWithTag("local-album-grid")
        val search = compose.onNodeWithContentDescription("搜索本地音乐")
        val source = compose.onNodeWithTag("library-source-local")
        val mode = compose.onNodeWithTag("library-mode-albums")
        val first = compose.onNodeWithTag("local-album:${library.albums.first().key}")
        grid.performScrollToIndex(12)
        search.assertIsDisplayed()
        grid.performScrollToIndex(0)
        grid.dragLibraryContentBy(25.dp)
        search.assertIsDisplayed()
        grid.performScrollToIndex(0)
        grid.dragLibraryContentBy(76.dp)
        search.assertDoesNotExist()
        source.assertDoesNotExist()
        mode.assertDoesNotExist()
        compose.mainClock.advanceTimeBy(700)
        search.assertDoesNotExist()

        val before = first.fetchSemanticsNode().boundsInRoot.top
        first.performClick()
        compose.onNodeWithTag("collection-cover").assertIsDisplayed()
        compose.onNodeWithContentDescription("返回曲库").performClick()
        search.assertIsDisplayed()
        source.assertIsDisplayed()
        mode.assertIsDisplayed()
        assertEquals(before, first.fetchSemanticsNode().boundsInRoot.top, 2f)

        grid.dragLibraryContentBy(72.dp)
        search.assertDoesNotExist()
        grid.dragLibraryContentBy((-30).dp)
        search.assertIsDisplayed()
        assertTrue(first.fetchSemanticsNode().boundsInRoot.top < before)
        search.performClick()
        val field = compose.onNodeWithTag("library-search")
        field.performImeAction()
        grid.dragLibraryContentBy(80.dp)
        field.assertIsDisplayed()
        source.assertIsDisplayed()

        compose.onNodeWithContentDescription("收起搜索").performClick()
        val songs = compose.onNodeWithTag("library-mode-songs")
        songs.performClick()
        val songList = compose.onNodeWithTag("library-song-list")
        songList.dragLibraryContentBy(74.dp)
        songs.assertDoesNotExist()
        compose.onNodeWithContentDescription("歌曲 12的更多操作").performClick()
        compose.onNodeWithText("加入喜欢").assertIsDisplayed()
        songs.assertIsSelected()
        compose.onNodeWithText("加入喜欢").performClick()
        songList.dragLibraryContentBy(74.dp)
        songs.assertDoesNotExist()
        songList.dragLibraryContentBy((-30).dp)
        songs.assertIsSelected()
    }

    @Test fun localFavoritesKeepControlsOnShortListsAndRestoreThemAtTheTop() {
        val tracks = (10..49).map { Song("qa-liked-$it", "file:///qa/$it", folder = "qa", title = "红心 $it", favorite = true) }
        var library by mutableStateOf(LibrarySnapshot(tracks.take(1)))
        var request by mutableIntStateOf(0)
        var refreshes = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                AmiTheme("dark") {
                    Surface {
                        Box(Modifier.width(320.dp).height(700.dp)) {
                            LocalLibraryScreen(
                                library,
                                false,
                                false,
                                null,
                                24.dp,
                                null,
                                onCloud = {},
                                onRefresh = { refreshes++ },
                                refreshRequest = request,
                                onAlbum = {},
                                onBack = {},
                                onPlay = { _, _ -> },
                                onFavorite = {},
                                onRemove = {},
                                onReorder = {},
                                favorites = true,
                            )
                        }
                    }
                }
            }
        }
        val list = compose.onNodeWithTag("library-song-list")
        val search = compose.onNodeWithContentDescription("搜索本地音乐")
        list.dragLibraryContentBy(92.dp)
        search.assertIsDisplayed()
        compose.runOnIdle { library = LibrarySnapshot(tracks) }
        list.dragLibraryContentBy(92.dp)
        search.assertDoesNotExist()
        compose.runOnIdle { request++ }
        search.assertIsDisplayed()
        compose.onNodeWithText("红心 10").assertIsDisplayed()
        list.dragLibraryContentBy((-220).dp)
        search.assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, refreshes) }
    }

    @Test fun stackedControlsFitANarrowScreenWithLargeText() {
        val library = LibrarySnapshot(listOf(Song("qa-narrow", "file:///qa", folder = "qa", title = "歌曲", album = "专辑")))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                AmiTheme("dark") {
                    Surface {
                        Box(Modifier.width(320.dp).height(700.dp)) {
                            LocalLibraryScreen(
                                library,
                                false,
                                false,
                                null,
                                24.dp,
                                null,
                                onCloud = {},
                                onRefresh = {},
                                onAlbum = {},
                                onBack = {},
                                onPlay = { _, _ -> },
                                onFavorite = {},
                                onRemove = {},
                                onReorder = {},
                            )
                        }
                    }
                }
            }
        }
        val cloud =
            compose
                .onNodeWithTag("library-source-cloud")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val search =
            compose
                .onNodeWithContentDescription("搜索本地音乐")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val albums =
            compose
                .onNodeWithTag("library-mode-albums")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        val songs =
            compose
                .onNodeWithTag("library-mode-songs")
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue("Source and search must not overlap", cloud.right <= search.left)
        assertTrue("The album and song options must remain on their own row", albums.top >= cloud.bottom && albums.right <= songs.left)
    }

    @Test fun refreshReturnsTheRestoredLocalShelfToTheTop() {
        val songs = (1..40).map { index -> Song("qa-refresh-$index", "file:///qa/$index", folder = "qa", title = "歌曲 $index", album = "专辑 $index") }
        val library = LibrarySnapshot(songs)
        var selectedAlbum by mutableStateOf<Album?>(null)
        var refreshes = 0
        var request by mutableIntStateOf(0)
        compose.setContent {
            AmiTheme("light") {
                Surface {
                    LocalLibraryScreen(
                        library,
                        false,
                        false,
                        null,
                        24.dp,
                        selectedAlbum = selectedAlbum,
                        onCloud = {},
                        onRefresh = { refreshes++ },
                        refreshRequest = request,
                        onAlbum = { selectedAlbum = it },
                        onBack = { selectedAlbum = null },
                        onPlay = { _, _ -> },
                        onFavorite = {},
                        onRemove = {},
                        onReorder = {},
                    )
                }
            }
        }
        val grid = compose.onNodeWithTag("local-album-grid")
        val first = compose.onNodeWithTag("local-album:${library.albums.first().key}")
        val top = first.fetchSemanticsNode().boundsInRoot.top
        grid.performScrollToIndex(24)
        first.assertDoesNotExist()
        compose.runOnIdle { request++ }
        assertEquals(
            top,
            first
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot.top,
            1f,
        )

        grid.performScrollToIndex(24)
        compose.onNodeWithTag("local-album:${library.albums[24].key}").performClick()
        compose.onNodeWithTag("collection-cover").assertIsDisplayed()
        grid.assertDoesNotExist()

        compose.runOnIdle {
            request++
            selectedAlbum = null
        }
        assertEquals(
            top,
            first
                .assertIsDisplayed()
                .fetchSemanticsNode()
                .boundsInRoot.top,
            1f,
        )
        compose.runOnIdle { assertEquals(2, refreshes) }
    }

    @Test fun expandedAlbumTitleAndDetailReturnPreserveTheFilteredShelf() {
        val songs =
            (10..49).map { index ->
                val album = "合集 $index · 晚风吹过城南的街道时我们一起听见远处的海浪与歌声"
                Song("qa-search-$index", "file:///qa/$index", folder = "qa", title = "曲目 $index", album = album, duration = 90_000)
            }
        val library = LibrarySnapshot(songs + Song("qa-unmatched", "file:///qa/other", folder = "qa", title = "其他歌曲", album = "安静的专辑"))
        var selectedAlbum by mutableStateOf<Album?>(null)
        compose.setContent {
            AmiTheme("light") {
                Surface {
                    LocalLibraryScreen(
                        library,
                        false,
                        false,
                        null,
                        24.dp,
                        selectedAlbum = selectedAlbum,
                        onCloud = {},
                        onRefresh = {},
                        onAlbum = { selectedAlbum = it },
                        onBack = { selectedAlbum = null },
                        onPlay = { _, _ -> },
                        onFavorite = {},
                        onRemove = {},
                        onReorder = {},
                    )
                }
            }
        }

        fun titleLayout(): TextLayoutResult {
            val layouts = mutableListOf<TextLayoutResult>()
            compose
                .onNodeWithTag("collection-title", useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            return layouts.single()
        }
        compose.onNodeWithText("安静的专辑").assertIsDisplayed()
        compose.onNodeWithContentDescription("搜索本地音乐").performClick()
        val search = compose.onNodeWithTag("library-search")
        search.performTextInput("合集")
        search.performImeAction()
        val matches = library.search("合集", false, false).albums
        val card = compose.onNodeWithTag("local-album:${matches[20].key}")
        compose.waitUntil(5000) { compose.onAllNodesWithText("安静的专辑").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("local-album-grid").performScrollToIndex(20)
        val before = card.fetchSemanticsNode().boundsInRoot
        card.performClick()
        compose.onNodeWithText("1 首歌曲 · 1:30").assertIsDisplayed()
        compose.onNodeWithText("播放").assertDoesNotExist()
        compose.onNodeWithText("随机播放").assertDoesNotExist()
        assertTrue(titleLayout().isLineEllipsized(0))
        val heading = compose.onNodeWithTag("collection-heading")
        heading.performClick()
        val expanded = titleLayout()
        assertTrue(expanded.lineCount > 1)
        assertFalse(expanded.hasVisualOverflow)
        heading.performClick()
        assertTrue(titleLayout().isLineEllipsized(0))
        compose.onNodeWithContentDescription("返回曲库").performClick()
        search.assertIsNotFocused()
        assertEquals(before.top, card.fetchSemanticsNode().boundsInRoot.top, 2f)
    }
}

internal fun SemanticsNodeInteraction.dragLibraryContentBy(distance: Dp) =
    performTouchInput {
        down(center)
        moveBy(Offset(0f, -distance.toPx()), delayMillis = 350)
        advanceEventTime(140)
        up()
    }
