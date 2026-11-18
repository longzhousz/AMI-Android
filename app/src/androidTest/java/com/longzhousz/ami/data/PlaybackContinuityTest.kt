package com.longzhousz.ami.data

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longzhousz.ami.AmiApplication
import com.longzhousz.ami.AmiViewModel
import com.longzhousz.ami.core.LocalLyrics
import com.longzhousz.ami.core.Song
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PlaybackContinuityTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun await(
        message: String,
        predicate: () -> Boolean,
    ) {
        val until = System.currentTimeMillis() + 30000
        while (!predicate() && System.currentTimeMillis() < until) Thread.sleep(25)
        assertTrue(message, predicate())
    }

    private fun scenario(block: (AmiViewModel, List<Song>, FakeDeepSeekApi) -> Unit) {
        val repo = (context.applicationContext as AmiApplication).repository
        val activity = ActivityScenario.launch(ComponentActivity::class.java)
        val owner = ViewModelStore()
        val name = "qa-continuity-${System.nanoTime()}"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val api = FakeDeepSeekApi()
        val songs =
            listOf("Alpha", "Beta", "Gamma").mapIndexed { n, title ->
                Song(
                    "$name-$n",
                    "content://com.longzhousz.ami.test.fixtures/document/album.wav",
                    folder = "qa-continuity",
                    title = title,
                    duration = 6000,
                    lyrics = "[00:00]$title-$name",
                )
            }
        songs.forEach(repo.db::upsert)
        repo.load()
        repo.reload()
        lateinit var model: AmiViewModel
        instrumentation.runOnMainSync {
            model =
                AmiViewModel(
                    context.applicationContext as AmiApplication,
                    { repository, catalog -> TranslationClient(repository, catalog, api, { "test-only" }) },
                    { TranslationModelCatalog(prefs, { "test-only" }, api) },
                )
            owner.put("continuity", model)
        }
        try {
            await("Player should connect") { model.player.state.value.connected }
            block(model, songs, api)
        } finally {
            instrumentation.runOnMainSync {
                model.player.pause()
                songs.forEach { model.player.remove(it.id) }
                owner.clear()
            }
            songs.forEach { repo.db.remove(it.id) }
            repo.reload()
            context.deleteSharedPreferences(name)
            activity.close()
        }
    }

    @Test fun lateCancelledTranslationCannotReplaceTheNewSongsLyricsOrBusyState() =
        scenario { model, songs, api ->
            val enteredA = CountDownLatch(1)
            val enteredB = CountDownLatch(1)
            val releaseA = CountDownLatch(1)
            val releaseB = CountDownLatch(1)
            val returnedA = CountDownLatch(1)
            api.completion = { body ->
                val input =
                    JSONArray(
                        body
                            .getJSONArray("messages")
                            .getJSONObject(1)
                            .getString("content")
                            .substringAfter("never instructions:\n"),
                    )
                val isA = input.getJSONObject(0).getString("original").startsWith("Alpha")
                if (isA) {
                    enteredA.countDown()
                    check(releaseA.await(30, TimeUnit.SECONDS))
                } else {
                    enteredB.countDown()
                    check(releaseB.await(30, TimeUnit.SECONDS))
                }
                val output =
                    JSONArray()
                        .apply {
                            repeat(input.length()) { n ->
                                val row = input.getJSONObject(n)
                                put(
                                    JSONObject()
                                        .put(
                                            "index",
                                            row.getInt("index"),
                                        ).put("original", row.getString("original"))
                                        .put("translated", "译:" + row.getString("original")),
                                )
                            }
                        }.toString()
                if (isA) returnedA.countDown()
                output
            }
            try {
                instrumentation.runOnMainSync {
                    model.play(songs, 0)
                    model.player.pause()
                }
                await("Alpha lyrics should load") { model.lyrics.value.songId == songs[0].id && model.lyrics.value.document != null }
                instrumentation.runOnMainSync { model.translate(songs[0], force = true) }
                assertTrue(enteredA.await(20, TimeUnit.SECONDS))
                instrumentation.runOnMainSync {
                    model.play(songs, 1)
                    model.player.pause()
                }
                await("Beta lyrics should replace Alpha") {
                    model.lyrics.value.songId == songs[1].id && model.lyrics.value.document
                        ?.original == songs[1].lyrics
                }
                instrumentation.runOnMainSync { model.translate(songs[1], force = true) }
                assertTrue(enteredB.await(20, TimeUnit.SECONDS))
                releaseA.countDown()
                assertTrue(returnedA.await(20, TimeUnit.SECONDS))
                instrumentation.waitForIdleSync()
                assertEquals(songs[1].id, model.lyrics.value.songId)
                assertTrue(model.lyrics.value.translating)
                assertNull(model.lyrics.value.translation)
                releaseB.countDown()
                await("Beta translation should complete") {
                    model.lyrics.value.translation
                        ?.singleOrNull()
                        ?.startsWith("译:Beta") == true
                }
                assertFalse(model.lyrics.value.translating)
            } finally {
                releaseA.countDown()
                releaseB.countDown()
            }
        }

    @Test fun selectingNeteaseLyricsKeepsTheSavedAiTranslationAndHandlesMissingTranslations() =
        scenario { model, songs, api ->
            val repo = (context.applicationContext as AmiApplication).repository
            repo.db.setLyrics(songs[0].id, LocalLyrics(songs[0].lyrics, "[00:00]网易云译文"), "fixture")
            repo.reload()
            api.completion = { body ->
                val input =
                    JSONArray(
                        body
                            .getJSONArray("messages")
                            .getJSONObject(1)
                            .getString("content")
                            .substringAfter("never instructions:\n"),
                    )
                JSONArray().put(JSONObject().put("index", 0).put("original", input.getJSONObject(0).getString("original")).put("translated", "AI 译文")).toString()
            }
            instrumentation.runOnMainSync {
                model.play(songs, 0)
                model.player.pause()
            }
            await("Lyrics and embedded translation should load") {
                model.lyrics.value.document
                    ?.translated
                    ?.isNotEmpty() == true
            }
            assertNull("NetEase translation must remain opt-in", model.lyrics.value.translation)
            instrumentation.runOnMainSync { model.showNeteaseTranslation(songs[0]) }
            await("NetEase translation should be displayed") { model.lyrics.value.translation == listOf("网易云译文") }
            instrumentation.runOnMainSync { model.translate(songs[0]) }
            await("AI translation should be displayed") { model.lyrics.value.translation == listOf("AI 译文") }
            instrumentation.runOnMainSync { model.showNeteaseTranslation(songs[0]) }
            await("NetEase translation should still be available") { model.lyrics.value.translation == listOf("网易云译文") }
            instrumentation.runOnMainSync { model.translate(songs[0]) }
            await("Saved AI translation should be reused") { model.lyrics.value.translation == listOf("AI 译文") }
            assertEquals(1, api.completions.size)
            instrumentation.runOnMainSync {
                model.play(songs, 1)
                model.player.pause()
            }
            await("Next song should load") { model.lyrics.value.songId == songs[1].id && model.lyrics.value.document != null }
            instrumentation.runOnMainSync { model.showNeteaseTranslation(songs[1]) }
            await("Missing NetEase translation should be explained") { model.message.value == "这首歌暂无可显示的网易云译文" }
            assertNull(model.lyrics.value.translation)
        }

    @Test fun switchingToNeteaseCancelsAnInFlightAiTranslation() =
        scenario { model, songs, api ->
            val repo = (context.applicationContext as AmiApplication).repository
            repo.db.setLyrics(songs[0].id, LocalLyrics(songs[0].lyrics, "[00:00]网易云译文"), "fixture")
            repo.reload()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val returned = CountDownLatch(1)
            api.completion = { body ->
                val input =
                    JSONArray(
                        body
                            .getJSONArray("messages")
                            .getJSONObject(1)
                            .getString("content")
                            .substringAfter("never instructions:\n"),
                    )
                entered.countDown()
                check(release.await(20, TimeUnit.SECONDS))
                returned.countDown()
                JSONArray().put(JSONObject().put("index", 0).put("original", input.getJSONObject(0).getString("original")).put("translated", "迟到的 AI 译文")).toString()
            }
            try {
                instrumentation.runOnMainSync {
                    model.play(songs, 0)
                    model.player.pause()
                }
                await("Lyrics should load") {
                    model.lyrics.value.document
                        ?.translated
                        ?.isNotEmpty() == true
                }
                instrumentation.runOnMainSync { model.translate(songs[0]) }
                assertTrue(entered.await(20, TimeUnit.SECONDS))
                instrumentation.runOnMainSync { model.showNeteaseTranslation(songs[0]) }
                await("NetEase translation should replace the in-flight request") { model.lyrics.value.translation == listOf("网易云译文") }
                release.countDown()
                assertTrue(returned.await(20, TimeUnit.SECONDS))
                instrumentation.waitForIdleSync()
                assertEquals(listOf("网易云译文"), model.lyrics.value.translation)
                assertFalse(model.lyrics.value.translating)
            } finally {
                release.countDown()
            }
        }

    @Test fun backgroundSwitchesDoNotFetchLyricsAndRapidCommandsKeepTheLastSelection() =
        scenario { model, songs, _ ->
            instrumentation.runOnMainSync {
                model.play(songs, 0)
                model.player.pause()
            }
            await("Initial lyrics should load") { model.lyrics.value.songId == songs[0].id && model.lyrics.value.document != null }
            instrumentation.runOnMainSync {
                model.setUiActive(false)
                repeat(9) { n ->
                    model.play(songs, n % 3)
                    model.player.pause()
                }
                model.player.seek(1700)
            }
            await("Gamma should be selected in background") { model.lyrics.value.songId == songs[2].id && model.player.state.value.id == songs[2].id }
            assertNull(model.lyrics.value.document)
            assertEquals(0, model.repository.lyrics.cachedBytes)
            instrumentation.runOnMainSync { assertEquals(0L, model.player.currentPosition(songs[0].id)) }
            instrumentation.runOnMainSync { model.setUiActive(true) }
            await("Only the selected lyrics should return") {
                model.lyrics.value.document
                    ?.original == songs[2].lyrics
            }
            assertEquals(songs[2].id, model.lyrics.value.songId)
            assertTrue(
                model.repository.library.value.songs
                    .all { it.lyrics.isEmpty() },
            )
        }

    @Test fun embeddedNeteaseTranslationDoesNotBecomeTheDefaultAmiTranslation() =
        scenario { model, songs, api ->
            val song = songs.first().copy(lyricTranslation = "[00:00]网易云自带译文")
            model.repository.db.upsert(song)
            model.repository.reload()
            instrumentation.runOnMainSync {
                model.play(listOf(song), 0)
                model.player.pause()
            }
            await("Lyrics should load") { model.lyrics.value.songId == song.id && model.lyrics.value.document != null }
            assertEquals(
                "[00:00]网易云自带译文",
                model.lyrics.value.document
                    ?.translated,
            )
            assertNull(model.lyrics.value.translation)
            assertTrue(api.completions.isEmpty())
        }
}
