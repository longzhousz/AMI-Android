package com.longzhousz.ami.ui

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryScrollToolbarTest {
    private var atEnd = false
    private val scroll =
        object : ScrollableState by ScrollableState({ it }) {
            override val canScrollForward get() = !atEnd
        }
    private val toolbar = LibraryScrollToolbarState(scroll, Density(1f)) { false }

    @Test fun consumedTravelUsesDifferentHideAndShowThresholds() {
        drag(-39f)
        assertTrue(toolbar.visible)
        drag(-1f)
        assertFalse(toolbar.visible)
        drag(15f)
        assertFalse(toolbar.visible)
        drag(1f)
        assertTrue(toolbar.visible)
    }

    @Test fun velocityShortcutRequiresForwardTravelInTheCurrentDrag() =
        runBlocking {
            drag(-8f)
            toolbar.beginDrag()
            drag(-7f)
            assertEquals(Velocity.Zero, toolbar.onPreFling(Velocity(0f, -1500f)))
            assertTrue(toolbar.visible)
            drag(-8f)
            atEnd = true
            assertEquals(Velocity.Zero, toolbar.onPreFling(Velocity(0f, -1500f)))
            assertFalse(toolbar.visible)
        }

    @Test fun unconsumedAndSideEffectMotionCannotHideTheToolbar() {
        assertEquals(Offset.Zero, toolbar.onPostScroll(Offset.Zero, Offset(0f, -100f), NestedScrollSource.UserInput))
        assertEquals(Offset.Zero, toolbar.onPostScroll(Offset(0f, -100f), Offset.Zero, NestedScrollSource.SideEffect))
        assertTrue(toolbar.visible)
    }

    private fun drag(dy: Float) {
        assertEquals(Offset.Zero, toolbar.onPostScroll(Offset(0f, dy), Offset.Zero, NestedScrollSource.UserInput))
    }
}
