package com.longzhousz.ami

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.longzhousz.ami.core.Song
import com.longzhousz.ami.playback.PlaybackState
import com.longzhousz.ami.ui.AmiTheme
import com.longzhousz.ami.ui.PlayerActions
import com.longzhousz.ami.ui.PlayerContent
import com.longzhousz.ami.ui.PlayerSheet
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerMotionTest {
    @get:Rule val compose = createComposeRule()

    private fun sheet(visible: MutableState<Boolean>, onDismiss: () -> Unit = {}) {
        compose.setContent {
            AmiTheme("dark") {
                Box(Modifier.fillMaxSize()) {
                    if (visible.value) PlayerSheet(null, { visible.value = false; onDismiss() }) { close, drag ->
                        Column(Modifier.fillMaxSize().background(Color.DarkGray)) {
                            Box(Modifier.fillMaxWidth().height(160.dp).then(drag).testTag("test-drag-handle"))
                            TextButton(close) { Text("收起") }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun shortSlowDragFollowsTheFingerAndSpringsBack() {
        var dismissed = 0
        sheet(mutableStateOf(true)) { dismissed++ }
        val start = compose.onNodeWithTag("player-sheet").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("test-drag-handle").performTouchInput {
            down(center)
            moveBy(Offset(0f, 80f), delayMillis = 300)
        }
        val dragged = compose.onNodeWithTag("player-sheet").fetchSemanticsNode().boundsInRoot.top
        assertTrue("The surface must move before the finger is released", dragged > start + 40f)
        compose.onNodeWithTag("test-drag-handle").performTouchInput {
            advanceEventTime(400)
            up()
        }
        compose.waitForIdle()
        assertEquals(start, compose.onNodeWithTag("player-sheet").fetchSemanticsNode().boundsInRoot.top, 1f)
        assertEquals(0, dismissed)
    }

    @Test fun reversingALongDragKeepsThePlayerOpen() {
        var dismissed = 0
        sheet(mutableStateOf(true)) { dismissed++ }
        val start = compose.onNodeWithTag("player-sheet").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("test-drag-handle").performTouchInput {
            down(center)
            moveBy(Offset(0f, 300f), delayMillis = 250)
        }
        assertTrue(compose.onNodeWithTag("player-sheet").fetchSemanticsNode().boundsInRoot.top > start + 200f)
        compose.onNodeWithTag("test-drag-handle").performTouchInput {
            moveBy(Offset(0f, -290f), delayMillis = 250)
            advanceEventTime(400)
            up()
        }
        compose.waitForIdle()
        assertEquals(start, compose.onNodeWithTag("player-sheet").fetchSemanticsNode().boundsInRoot.top, 1f)
        assertEquals(0, dismissed)
    }

    @Test fun downwardFlingDismissesOnceAndThePlayerCanReopen() {
        val visible = mutableStateOf(true)
        var dismissed = 0
        sheet(visible) { dismissed++ }
        compose.onNodeWithTag("test-drag-handle").performTouchInput {
            swipe(center, Offset(centerX, height + 400f), durationMillis = 250)
        }
        compose.onNodeWithTag("player-sheet").assertDoesNotExist()
        assertEquals(1, dismissed)
        compose.runOnIdle { visible.value = true }
        compose.onNodeWithTag("player-sheet").assertIsDisplayed()
        compose.onNodeWithText("收起").performClick()
        compose.onNodeWithTag("player-sheet").assertDoesNotExist()
        assertEquals(2, dismissed)
    }

    @Test fun catchingTheClosingAnimationAndDraggingUpCancelsDismissal() {
        var dismissed = 0
        sheet(mutableStateOf(true)) { dismissed++ }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("收起").performClick()
        compose.mainClock.advanceTimeBy(64)
        val closingTop = compose.onNodeWithTag("player-sheet").fetchSemanticsNode().boundsInRoot.top
        assertTrue("The closing animation must still be in flight", closingTop > 0f)
        compose.onNodeWithTag("test-drag-handle").performTouchInput {
            down(center)
            moveBy(Offset(0f, -closingTop - 20f), delayMillis = 200)
            advanceEventTime(400)
            up()
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithTag("player-sheet").assertIsDisplayed()
        assertEquals(0f, compose.onNodeWithTag("player-sheet").fetchSemanticsNode().boundsInRoot.top, 1f)
        assertEquals(0, dismissed)
    }

    @Test fun scrubbingKeepsTheThumbUnderTheFingerAndSeeksOnlyOnRelease() {
        val position = mutableLongStateOf(1000)
        var seeks = 0
        var target = 0L
        val song = Song("scrub", "file:///unused", folder = "qa", title = "拖动进度", duration = 90000)
        compose.setContent {
            AmiTheme("dark") {
                PlayerContent(song, PlaybackState(duration = 90000), null, false,
                    PlayerActions(seek = { seeks++; target = it }), lyricPosition = { position.longValue })
            }
        }
        compose.onNodeWithTag("playback-seek").performTouchInput {
            down(Offset(width * .25f, centerY))
            moveTo(Offset(width * .75f, centerY), delayMillis = 200)
        }
        compose.runOnIdle { position.longValue = 5000; assertEquals(0, seeks) }
        val dragged = compose.onNodeWithTag("playback-seek").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
        assertTrue("A playback tick must not override the user's preview", dragged > 50000f)
        compose.onNodeWithTag("playback-seek").performTouchInput { up() }
        compose.runOnIdle { assertEquals(1, seeks); assertTrue(target > 50000) }
    }
    @Test fun repeatedCloseRequestsDismissThePanelOnlyOnce() {
        val visible=mutableStateOf(true)
        var dismissals=0
        sheet(visible) { dismissals++ }
        compose.mainClock.autoAdvance=false
        compose.onNodeWithText("收起").performClick()
        compose.onNodeWithText("收起").performClick()
        compose.mainClock.advanceTimeBy(2000)
        compose.mainClock.autoAdvance=true
        compose.waitForIdle()
        assertEquals(1,dismissals)
        compose.onNodeWithTag("player-sheet").assertDoesNotExist()
    }
    @Test fun anEarlierSeekAcknowledgementCannotMoveTheNewPreviewBack() {
        val position=mutableLongStateOf(1000)
        val requests=mutableListOf<Long>()
        val song=Song("rapid-seek","file:///unused",folder="qa",title="Rapid seek",duration=90000)
        compose.setContent {
            AmiTheme("dark") { PlayerContent(song,PlaybackState(duration=90000),null,false,
                PlayerActions(seek={ requests+=it }),lyricPosition={ position.longValue }) }
        }
        compose.onNodeWithTag("playback-seek").performTouchInput { click(Offset(width*.75f,centerY)) }
        compose.onNodeWithTag("playback-seek").performTouchInput { click(Offset(width*.30f,centerY)) }
        assertEquals(2,requests.size)
        compose.runOnIdle { position.longValue=requests[0] }
        val progress=compose.onNodeWithTag("playback-seek").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(requests[1].toFloat(),progress.current,1000f)
        compose.runOnIdle { position.longValue=requests[1] }
        assertEquals(requests[1].toFloat(),compose.onNodeWithTag("playback-seek").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current,1000f)
    }

}
