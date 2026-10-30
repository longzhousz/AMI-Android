package com.longzhousz.ami.data

import android.graphics.Bitmap
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longzhousz.ami.core.*
import com.longzhousz.ami.ui.AmiTheme
import com.longzhousz.ami.ui.ListeningStatsScreen
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

/** Synthetic listening only; these fixtures are never included in the distribution APK. */
@RunWith(AndroidJUnit4::class)
class ListeningStatsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "qa-listening-ui-${System.nanoTime()}.db"
    private lateinit var db: LibraryDatabase
    private lateinit var store: ListeningStatsStore
    private lateinit var job: Job
    private val today get() = LocalDate.now()
    private lateinit var tracks: List<ListeningTrack>

    @Before fun prepare() {
        db = LibraryDatabase(context, name); job = SupervisorJob(); store = ListeningStatsStore(db, CoroutineScope(job + Dispatchers.IO))
        val titles = listOf("晚风", "河岸的灯", "晨间列车", "无声的夏天", "回声", "星夜", "远山", "留白", "旧时光", "海的另一边", "日落之后", "慢慢", "不眠之城", "风经过")
        val artists = listOf("林间回声", "北岸", "The Morning Lines", "Blue Hours", "纸飞机", "云的轨迹", "南方旅人")
        val albums = listOf("晚风与回声", "海边来信", "Morning Light", "Blue Hours", "纸上的远方", "云的形状", "漫长的夏天")
        tracks = titles.mapIndexed { index, title -> Song("netease:${9000000 + index}", "ami-cloud://song/${9000000 + index}", folder = "qa", title = title,
            artist = artists[index / 2], album = albums[index / 2], neteaseId = "${9000000 + index}", primaryArtistId = "${8000000 + index / 2}",
            primaryArtistName = artists[index / 2], albumId = "${7000000 + index / 2}").listeningTrack() }
        val rows = buildList {
            var date = today.withDayOfMonth(1).minusMonths(14); var index = 0
            while (date <= today) {
                if (index % 11 != 0) repeat(7) { n -> add(ListeningSlice(tracks[(index + n) % tracks.size], date, (18 + n + index % 3) % 24, (2 + (index + n) % 9) * 60_000L)) }
                date = date.plusDays(1); index++
            }
        }
        store.write(rows)
    }
    @After fun cleanup() { runBlocking { job.cancelAndJoin() }; db.close(); context.deleteDatabase(name) }
    private fun screenshot(name: String) {
        compose.mainClock.advanceTimeBy(600); compose.waitForIdle()
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.filesDir, "qa/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun ready() { compose.waitUntil(15000) { compose.onAllNodesWithTag("listening-duration").fetchSemanticsNodes().isNotEmpty() } }

    @Test fun weekMonthYearChartsRemainInteractiveAndSupportBothThemes() {
        var dark by mutableStateOf(false)
        compose.setContent { AmiTheme(if (dark) "dark" else "light") { Surface { ListeningStatsScreen(store, {}) } } }
        ready(); screenshot("listening-month")
        compose.onNodeWithTag("listening-WEEK").performClick(); compose.onNodeWithTag("listening-WEEK").assertIsSelected(); ready()
        compose.onNodeWithTag("listening-report").performScrollToIndex(2)
        compose.onNodeWithTag("listening-calendar").assertIsDisplayed()
        compose.onNodeWithTag("listening-YEAR").performClick(); compose.onNodeWithTag("listening-YEAR").assertIsSelected()
        ready()
        screenshot("listening-year")
        compose.onNodeWithTag("listening-report").performScrollToIndex(2); screenshot("listening-calendar")
        compose.onNodeWithTag("listening-report").performScrollToIndex(3)
        compose.onNodeWithText("反复相伴的歌").assertIsDisplayed()
        compose.onNodeWithTag("listening-report").performScrollToIndex(4); screenshot("listening-artists")
        compose.onNodeWithTag("listening-report").performScrollToIndex(5)
        compose.onNodeWithText("常驻的唱片").assertIsDisplayed()
        compose.onNodeWithTag("listening-report").performScrollToIndex(6); screenshot("listening-hours")
        compose.onNodeWithTag("listening-report").performScrollToIndex(7)
        compose.onNodeWithText("初遇，也重逢").assertIsDisplayed()
        compose.runOnIdle { dark = true }
        compose.onNodeWithTag("listening-MONTH").performClick()
        ready()
        compose.onNodeWithTag("listening-report").performScrollToIndex(0); screenshot("listening-dark")
    }

    @Test fun dataIsASnapshotUntilManualRefresh() {
        compose.setContent { AmiTheme("light") { Surface { ListeningStatsScreen(store, {}) } } }
        ready()
        fun number() = compose.onNodeWithTag("listening-duration").fetchSemanticsNode().config[SemanticsProperties.Text].single().text
        val before = number()
        store.write(listOf(ListeningSlice(tracks.first(), today, 12, 3_600_000)))
        compose.mainClock.advanceTimeBy(20_000)
        assertEquals(before, number())
        compose.onNodeWithTag("listening-report").performTouchInput { swipeDown() }
        compose.waitUntil(10000) { number() != before }
    }

    @Test fun clearingNeedsConfirmationAndLeavesSourceMusicUntouched() {
        db.upsert(Song("protected", "file:///protected", folder = "qa", title = "保留的本地歌曲", favorite = true))
        compose.setContent { AmiTheme("dark") { Surface { ListeningStatsScreen(store, {}) } } }
        ready()
        compose.onNodeWithContentDescription("聆听选项").performClick()
        compose.onNodeWithText("清除聆听记录").performClick()
        compose.onNodeWithText("取消").performClick()
        assertTrue(runBlocking { store.report(ListeningPeriod(ListeningScale.MONTH, today)) }.totalMillis > 0)
        compose.onNodeWithContentDescription("聆听选项").performClick()
        compose.onNodeWithText("清除聆听记录").performClick()
        compose.onNodeWithText("清除记录").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("从下一首歌，开始积累").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(db.songs().single().favorite)
        screenshot("listening-empty")
    }
}
