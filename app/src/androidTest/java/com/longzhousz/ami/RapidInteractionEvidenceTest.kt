package com.longzhousz.ami

import android.os.ParcelFileDescriptor
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longzhousz.ami.core.Song
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class RapidInteractionEvidenceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private fun shell(command:String):ByteArray = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    @Test fun rapidTrackChangesAndPanelTransitionsKeepTheSelectedLyricsAndRecordEvidence() {
        val model=ViewModelProvider(compose.activity)[AmiViewModel::class.java]
        val prefix="qa-rapid-${System.nanoTime()}"
        val songs=(0..2).map { Song("$prefix-$it","content://com.longzhousz.ami.test.fixtures/document/album.wav",folder="qa-rapid",title="Rapid $it",duration=6000,lyrics="[00:00]Line $it\n[00:03]End $it") }
        val output=File(compose.activity.filesDir,"qa/performance").apply { mkdirs() }
        val trace="/data/misc/perfetto-traces/ami-rapid.pftrace"
        var traceStarted=false
        try {
            songs.forEach(model.repository.db::upsert);model.repository.reload()
            compose.waitUntil(30000) { model.player.state.value.connected && model.repository.library.value.songsById.containsKey(songs[0].id) }
            compose.runOnIdle { model.play(songs,0);model.player.pause() }
            compose.waitUntil(10000) { model.lyrics.value.document!=null && model.lyrics.value.songId==songs[0].id }
            shell("dumpsys gfxinfo ${compose.activity.packageName} reset")
            val traceOutput=shell("perfetto --background-wait -o $trace -t 15s --app ${compose.activity.packageName} sched freq idle am wm gfx view binder_driver dalvik").toString(Charsets.UTF_8)
            traceStarted=Regex("\\b[0-9]+\\b").containsMatchIn(traceOutput) && !traceOutput.contains("error",true)
            File(output,"rapid-perfetto-start.txt").writeText(traceOutput)
            val started=System.nanoTime()
            repeat(6) { n ->
                compose.runOnIdle {
                    model.play(songs,n%3);model.player.pause()
                    model.player.seek(2400);model.player.seek(900)
                }
                compose.waitUntil(10000) { model.lyrics.value.songId==songs[n%3].id && model.lyrics.value.document?.original==songs[n%3].lyrics }
                compose.onNodeWithTag("mini-player").performClick()
                compose.onNodeWithTag("full-player").assertIsDisplayed()
                compose.onNodeWithContentDescription("收起播放页").performClick()
                compose.onNodeWithTag("full-player").assertDoesNotExist()
            }
            assertEquals(songs[2].id,model.player.state.value.id)
            assertEquals(songs[2].lyrics,model.lyrics.value.document?.original)
            File(output,"rapid-gfxinfo.txt").writeBytes(shell("dumpsys gfxinfo ${compose.activity.packageName} framestats"))
            val windows=shell("dumpsys window windows").toString(Charsets.UTF_8)
            File(output,"rapid-windows.txt").writeText(windows)
            File(output,"rapid-flow.json").writeText(JSONObject().put("flow","6 track changes, 12 seeks, 6 panel open/close cycles").put("durationMs",(System.nanoTime()-started)/1_000_000.0)
                .put("finalSongCorrect",true).put("frameSampleUsable",!windows.contains("Application Not Responding")).put("perfettoRequested",traceStarted)
                .put("caveat","Emulator debug capture; absolute frame times are not vivo phone benchmarks").toString(2))
            if(traceStarted) {
                val remaining=15500-(System.nanoTime()-started)/1_000_000
                if (remaining>0) Thread.sleep(remaining)
                File(output,"rapid.pftrace").writeBytes(shell("cat $trace"))
            }
        } finally {
            if(traceStarted) shell("rm -f $trace")
            compose.runOnIdle { model.player.pause();songs.forEach { model.player.remove(it.id) } }
            songs.forEach { model.repository.db.remove(it.id) };model.repository.reload()
        }
    }
}
