package com.longzhousz.ami

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.longzhousz.ami.core.LibrarySnapshot
import com.longzhousz.ami.core.Song
import com.longzhousz.ami.ui.AmiTheme
import com.longzhousz.ami.ui.LocalLibraryScreen
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalLibraryLayoutTest {
    @get:Rule val compose = createComposeRule()

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
        val menu =
            compose
                .onNodeWithContentDescription("本地音乐选项")
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
        assertTrue("Source and actions must not overlap", cloud.right <= menu.left && menu.right <= search.left)
        assertTrue("The album and song options must remain on their own row", albums.top >= cloud.bottom && albums.right <= songs.left)
    }
}
