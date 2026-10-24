package com.longzhousz.ami

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.longzhousz.ami.core.Album
import com.longzhousz.ami.core.Song
import com.longzhousz.ami.ui.AmiTheme
import com.longzhousz.ami.ui.ReorderDialog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReorderMotionTest {
    @get:Rule val compose = createComposeRule()
    private val songs = listOf("第一首", "第二首", "第三首").mapIndexed { i, title ->
        Song("reorder-$i", "file:///unused-$i", folder = "qa", title = title)
    }
    private val album = Album("qa", "曲序验证", "AMI", songs)

    @Test fun arrowReorderSavesTheVisibleOrder() {
        var saved: List<String>? = null
        compose.setContent { AmiTheme("dark") { ReorderDialog(album, {}, { saved = it }) } }
        compose.onNodeWithContentDescription("下移 第一首").performClick()
        val first = compose.onNodeWithTag("reorder-row-reorder-0").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("reorder-row-reorder-1").fetchSemanticsNode().boundsInRoot
        assertTrue(second.top < first.top)
        compose.onNodeWithText("保存曲序").performClick()
        compose.runOnIdle { assertEquals(listOf(songs[1].id, songs[0].id, songs[2].id), saved) }
    }

    @Test fun grabbedRowTracksTheFingerAndSavesItsDestination() {
        var saved: List<String>? = null
        compose.setContent { AmiTheme("dark") { ReorderDialog(album, {}, { saved = it }) } }
        val row = compose.onNodeWithTag("reorder-row-reorder-0").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithContentDescription("长按拖动 第一首").performTouchInput {
            down(center)
            advanceEventTime(650)
            moveBy(Offset(0f, 2f), delayMillis = 16)
            moveBy(Offset(0f, row.height * .85f), delayMillis = 150)
        }
        val dragged = compose.onNodeWithTag("reorder-row-reorder-0").fetchSemanticsNode().boundsInRoot
        assertTrue("The grabbed row must follow the finger", dragged.top > row.top + row.height * .5f)
        compose.onNodeWithContentDescription("长按拖动 第一首").performTouchInput { up() }
        compose.onNodeWithText("保存曲序").performClick()
        compose.runOnIdle { assertEquals(listOf(songs[1].id, songs[0].id, songs[2].id), saved) }
    }
}
