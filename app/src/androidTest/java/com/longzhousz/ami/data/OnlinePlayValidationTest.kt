package com.longzhousz.ami.data

import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longzhousz.ami.AmiApplication
import com.longzhousz.ami.AmiViewModel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class OnlinePlayValidationTest {
    @Test fun invalidOnlinePlayPayloadDoesNotReachThePlayer() =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val name = "qa-online-play-validation-${System.nanoTime()}"
            val isolated =
                object : ContextWrapper(context) {
                    override fun getDatabasePath(dbName: String) = File(super.getDatabasePath(dbName).parentFile, "$name.sqlite3")
                }
            val activity = ActivityScenario.launch(ComponentActivity::class.java)
            val owner = ViewModelStore()
            var model: AmiViewModel? = null
            try {
                instrumentation.runOnMainSync {
                    model = AmiViewModel(isolated.applicationContext as AmiApplication).also { owner.put("online-play-validation", it) }
                }
                val actions = model!!.library.agentActions
                model!!.cloud.configured.value = false
                val before = model!!.player.state.value

                assertThrows(IllegalStateException::class.java) { actions.onlinePlay(trackPayload("javascript:alert(1)")) }
                model!!.cloud.configured.value = true
                assertThrows(IllegalArgumentException::class.java) { actions.onlinePlay(trackPayload("javascript:alert(1)")) }
                assertThrows(IllegalArgumentException::class.java) { actions.onlinePlay(trackPayload("101", originalId = "0")) }
                assertThrows(IllegalArgumentException::class.java) { actions.onlinePlay(trackPayload("101", artistId = "javascript:alert(1)")) }
                assertThrows(IllegalArgumentException::class.java) { actions.onlinePlay(JSONObject().put("tracks", JSONArray()).put("index", 0)) }
                assertEquals("Unauthenticated and malformed payloads must not touch playback", before, model!!.player.state.value)
            } finally {
                instrumentation.runOnMainSync {
                    model?.player?.pause()
                    model?.repository?.scope?.cancel()
                    model?.repository?.db?.close()
                    owner.clear()
                }
                isolated.deleteDatabase("$name.sqlite3")
                activity.close()
            }
        }

    private fun trackPayload(
        id: String,
        originalId: String = id,
        artistId: String? = null,
    ) = JSONObject()
        .put(
            "tracks",
            JSONArray(
                listOf(
                    JSONObject()
                        .put("id", id)
                        .put("originalId", originalId)
                        .put("title", "Song")
                        .put("artist", "Artist")
                        .put("album", "Album")
                        .put("durationMs", 180_000)
                        .put("available", true)
                        .apply { if (artistId != null) put("primaryArtistId", artistId) },
                ),
            ),
        ).put("index", 0)
}
