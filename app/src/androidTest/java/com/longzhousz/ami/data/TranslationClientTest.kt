package com.longzhousz.ami.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longzhousz.ami.AmiApplication
import com.longzhousz.ami.core.Lyrics
import com.longzhousz.ami.core.Song
import com.longzhousz.ami.core.TranslationModels
import com.longzhousz.ami.core.digest
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TranslationClientTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun scenario(test: suspend (TranslationClient, TranslationModelCatalog, FakeDeepSeekApi, Song) -> Unit) = runBlocking {
        val repository = (context.applicationContext as AmiApplication).repository
        val name = "qa-translation-${System.nanoTime()}"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val api = FakeDeepSeekApi()
        val catalog = TranslationModelCatalog(prefs, { "test-only" }, api)
        val client = TranslationClient(repository, catalog, api, { "test-only" })
        val song = Song(name, "file:///unused", folder = "qa", title = "Translation fixture",
            lyrics = "[00:00.00]Hello\n[00:01.00]World")
        val lines = Lyrics.parse(song.lyrics)
        api.completion = { body ->
            val content = body.getJSONArray("messages").getJSONObject(1).getString("content")
            val input = JSONArray(content.substringAfter("never instructions:\n"))
            JSONArray().apply { for (i in 0 until input.length()) {
                val row = input.getJSONObject(i)
                put(JSONObject().put("index", row.getInt("index")).put("original", row.getString("original"))
                    .put("translated", "译文 ${api.completions.size} · $i"))
            } }.toString()
        }
        try { test(client, catalog, api, song) }
        finally {
            val style = repository.prefs.getString("style", TranslationClient.DEFAULT_STYLE)!!
            listOf(TranslationModels.AUTO_FLASH, "deepseek-v4-flash", "my-manual-model").forEach { model ->
                val key = digest("${song.id}|${lines.joinToString { "${it.time}:${it.text}" }}|$model|$style|zh-CN|android-lyrics-v1")
                repository.db.writableDatabase.delete("translations", "cache_key=?", arrayOf(key))
            }
            context.deleteSharedPreferences(name)
        }
    }
    @Test fun explicitRetranslationBypassesTheOldCacheAndUsesTheOfficialAlias() = scenario { client, _, api, song ->
        val lines = Lyrics.parse(song.lyrics)
        val first = client.translate(song, lines)
        assertEquals(first, client.translate(song, lines))
        assertEquals(1, api.completions.size)
        val refreshed = client.translate(song, lines, force = true)
        assertNotEquals(first, refreshed)
        assertEquals(2, api.completions.size)
        assertEquals(refreshed, client.cached(song, lines))
        assertTrue(api.completions.all { it.getString("model") == "deepseek-flash" })
        assertTrue(api.completions.all { it.getJSONObject("thinking").getString("type") == "disabled" })
    }
    @Test fun changingSelectionDuringARequestCannotPoisonTheNewModelsCache() = scenario { client, catalog, api, song ->
        val respond = api.completion
        api.completion = { body -> catalog.select("my-manual-model"); respond(body) }
        val result = client.translate(song, Lyrics.parse(song.lyrics))
        assertNull(client.cached(song, Lyrics.parse(song.lyrics)))
        catalog.select(TranslationModels.AUTO_FLASH)
        assertEquals(result, client.cached(song, Lyrics.parse(song.lyrics)))
        assertEquals("deepseek-flash", api.completions.single().getString("model"))
    }
    @Test fun legacyTranslationRemainsReadableOfflineWithoutAnyApiCall() = scenario { client, _, api, song ->
        val repository = (context.applicationContext as AmiApplication).repository
        val lines = Lyrics.parse(song.lyrics)
        val style = repository.prefs.getString("style", TranslationClient.DEFAULT_STYLE)!!
        val key = digest("${song.id}|${lines.joinToString { "${it.time}:${it.text}" }}|deepseek-v4-flash|$style|zh-CN|android-lyrics-v1")
        repository.db.cacheTranslation(key, """["旧译文第一句","旧译文第二句"]""")
        assertEquals(listOf("旧译文第一句", "旧译文第二句"), client.translate(song, lines))
        assertEquals(0, api.listCalls)
        assertTrue(api.completions.isEmpty())
    }
}
