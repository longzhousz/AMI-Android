package com.longzhousz.ami

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longzhousz.ami.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/** PixelCopy runs through Android's hardware renderer, including the library's RuntimeShaders. */
@RunWith(AndroidJUnit4::class)
class LiquidGlassTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun capture(name: String): Bitmap {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.filesDir, "qa/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        return bitmap
    }
    private fun difference(a: Bitmap, b: Bitmap): Double {
        var total = 0L; var count = 0
        for (y in 0 until a.height step 12) for (x in 0 until a.width step 12) {
            val left = a.getPixel(x, y); val right = b.getPixel(x, y)
            total += abs(android.graphics.Color.red(left) - android.graphics.Color.red(right)) +
                abs(android.graphics.Color.green(left) - android.graphics.Color.green(right)) +
                abs(android.graphics.Color.blue(left) - android.graphics.Color.blue(right))
            count++
        }
        return total.toDouble() / count.coerceAtLeast(1)
    }

    @Test fun auroraMovesWhileVisibleAndStopsWhenCovered() {
        var active by mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent { AmiTheme("dark") {
            CompositionLocalProvider(LocalVisualsActive provides active) {
                GlassBackdropHost(Modifier.fillMaxSize(), background = { PlayerBackdrop(null, dark = true) }) {
                    GlassSurface(Modifier.align(Alignment.BottomCenter).padding(32.dp).fillMaxWidth().height(100.dp), prominent = true) {
                        Text("AMI", Modifier.align(Alignment.Center))
                    }
                }
            }
        } }
        compose.mainClock.advanceTimeBy(1000); compose.waitForIdle()
        val start = capture("glass-aurora-start")
        compose.mainClock.advanceTimeBy(6500); compose.waitForIdle()
        val moving = capture("glass-aurora-moving")
        assertTrue("The artwork glow must actually flow", difference(start, moving) > 2)
        compose.runOnIdle { active = false }
        compose.mainClock.advanceTimeBy(600); compose.waitForIdle()
        val paused = capture("glass-aurora-paused")
        compose.mainClock.advanceTimeBy(6500); compose.waitForIdle()
        val still = capture("glass-aurora-still")
        assertTrue("Covered pages must stop drawing animation", difference(paused, still) < .2)
        compose.mainClock.autoAdvance = true
    }

    @Test fun opticsFollowTheSourceAndControlsRemainUsableAcrossThemes() {
        var dark by mutableStateOf(false)
        var shifted by mutableStateOf(false)
        var clicks = 0
        compose.setContent { AmiTheme(if (dark) "dark" else "light") {
            GlassBackdropHost(Modifier.fillMaxSize(), background = {
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(Color(if (shifted) 0xFF243F72 else 0xFFA8CCDB))
                    repeat(12) { i ->
                        drawCircle(if (i % 2 == 0) Color(0xFFDE917E) else Color(0xFF5B83B9), size.width * .24f,
                            Offset(size.width * (if (shifted) .85f else .15f), size.height * i / 10))
                    }
                }
            }) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    GlassSurface(Modifier.size(260.dp, 120.dp).testTag("optical-panel"), prominent = true) {
                        Text("AMI · 液态玻璃", Modifier.align(Alignment.Center))
                    }
                    Spacer(Modifier.height(24.dp))
                    ActionIcon(Icons.Rounded.PlayArrow, "玻璃播放", { clicks++ })
                }
            }
        } }
        compose.waitForIdle(); val light = capture("glass-optics-light")
        compose.onNodeWithContentDescription("玻璃播放").performClick()
        compose.runOnIdle { assertEquals(1, clicks); shifted = true; dark = true }
        compose.waitForIdle(); val changed = capture("glass-optics-dark")
        assertTrue("Glass must update when the underlying layer changes", difference(light, changed) > 10)
        compose.onNodeWithContentDescription("玻璃播放").performClick()
        compose.runOnIdle { assertEquals(2, clicks) }
    }
}
