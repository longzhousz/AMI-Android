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
        // Shell can read Android/media; it cannot read this app's private files directory.
        val config=File(compose.activity.externalMediaDirs.first(),"ami-perfetto.txt")
        val script=File(config.parentFile,"ami-perfetto.sh")
        var traceStarted=false
        try {
            songs.forEach(model.repository.db::upsert);model.repository.load();model.repository.reload()
            compose.waitUntil(30000) { model.player.state.value.connected && model.repository.library.value.songsById.containsKey(songs[0].id) }
            compose.runOnIdle { model.play(songs,0);model.player.pause() }
            compose.waitUntil(10000) { model.lyrics.value.document!=null && model.lyrics.value.songId==songs[0].id }
            shell("dumpsys gfxinfo ${compose.activity.packageName} reset")
            config.parentFile!!.mkdirs()
            config.writeText("""
                duration_ms: 45000
                buffers { size_kb: 16384 fill_policy: RING_BUFFER }
                data_sources { config { name: "linux.ftrace" ftrace_config {
                    ftrace_events: "sched/sched_switch"
                    ftrace_events: "sched/sched_waking"
                    ftrace_events: "power/cpu_frequency"
                    atrace_categories: "am"
                    atrace_categories: "wm"
                    atrace_categories: "gfx"
                    atrace_categories: "view"
                    atrace_categories: "binder_driver"
                    atrace_categories: "dalvik"
                    atrace_apps: "${compose.activity.packageName}"
                } } }
                data_sources { config { name: "linux.process_stats" process_stats_config { scan_all_processes_on_start: true } } }
            """.trimIndent())
            // --background and text configs work on both Android 11 and 17. Capture stderr too.
            // perfetto's SELinux domain cannot open app-owned external files; shell streams stdin.
            script.writeText("cat ${config.absolutePath} | perfetto --background --txt -c - -o $trace 2>&1\nprintf '\\nAMI_PERFETTO_EXIT=%s\\n' \$?\n")
            val traceOutput=shell("sh ${script.absolutePath}").toString(Charsets.UTF_8)
            traceStarted=traceOutput.contains("AMI_PERFETTO_EXIT=0")
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
                if (n%2==0) {
                    compose.onNodeWithContentDescription("展开专辑封面").performClick()
                    compose.onNodeWithContentDescription("切换到歌词").performClick()
                }
                compose.onNodeWithContentDescription("收起播放页").performClick()
                compose.onNodeWithTag("full-player").assertDoesNotExist()
            }
            assertEquals(songs[2].id,model.player.state.value.id)
            assertEquals(songs[2].lyrics,model.lyrics.value.document?.original)
            File(output,"rapid-gfxinfo.txt").writeBytes(shell("dumpsys gfxinfo ${compose.activity.packageName} framestats"))
            val windows=shell("dumpsys window windows").toString(Charsets.UTF_8)
            File(output,"rapid-windows.txt").writeText(windows)
            val durationMs=(System.nanoTime()-started)/1_000_000.0
            File(output,"rapid-flow.json").writeText(JSONObject().put("flow","6 track changes, 12 seeks, 6 panel cycles, 3 artwork round trips").put("durationMs",durationMs)
                .put("finalSongCorrect",true).put("frameSampleUsable",!windows.contains("Application Not Responding")).put("perfettoRequested",traceStarted)
                .put("traceWindowMs",45000).put("traceCoversWholeFlow",durationMs<=45000).put("phoneFrameBenchmark",false)
                .put("caveat","Compose test clock and software-rendered debug emulator; not a phone frame-rate benchmark").toString(2))
            if(traceStarted) {
                val remaining=45500-(System.nanoTime()-started)/1_000_000
                if (remaining>0) Thread.sleep(remaining)
                File(output,"rapid.pftrace").writeBytes(shell("cat $trace"))
            }
        } finally {
            if(traceStarted) shell("rm -f $trace")
            config.delete();script.delete()
            compose.runOnIdle { model.player.pause();songs.forEach { model.player.remove(it.id) } }
            songs.forEach { model.repository.db.remove(it.id) };model.repository.reload()
        }
    }
}
