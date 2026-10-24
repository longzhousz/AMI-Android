package com.longzhousz.ami.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longzhousz.ami.core.TranslationModels
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

internal class FakeDeepSeekApi : DeepSeekApi {
    var listCalls = 0
    val completions = mutableListOf<JSONObject>()
    var list: () -> String = { """{"data":[{"id":"deepseek-flash","name":"DeepSeek-V4.1-Flash"},{"id":"deepseek-v4-pro","name":"DeepSeek-V4-Pro"}]}""" }
    var completion: (JSONObject) -> String = { error("No completion expected") }
    override fun models(apiKey: String): String { listCalls++; return list() }
    override fun complete(apiKey: String, body: JSONObject): String { completions += body; return completion(body) }
}

@RunWith(AndroidJUnit4::class)
class TranslationModelCatalogTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun withPrefs(test: suspend (android.content.SharedPreferences) -> Unit) = runBlocking {
        val name = "qa-models-${System.nanoTime()}"
        try { test(context.getSharedPreferences(name, Context.MODE_PRIVATE)) }
        finally { context.deleteSharedPreferences(name) }
    }
    @Test fun dailyRefreshUpdatesAndPersistsOfficialPresetsWithoutCompleting() = withPrefs { prefs ->
        val api = FakeDeepSeekApi()
        var time = 1_000_000L
        val catalog = TranslationModelCatalog(prefs, { "test-only" }, api, { time })
        catalog.refresh()
        assertEquals("DeepSeek-V4.1-Flash", catalog.state.value.resolved().name)
        assertEquals(1, api.listCalls)
        time += 60_000
        catalog.refresh()
        assertEquals(1, api.listCalls)
        api.list = { """{"data":[{"id":"deepseek-flash","name":"DeepSeek-V5-Flash"}]}""" }
        time += 24 * 60 * 60 * 1000L
        catalog.refresh()
        assertEquals(2, api.listCalls)
        assertEquals("DeepSeek-V5-Flash", catalog.state.value.resolved().name)
        val reopened = TranslationModelCatalog(prefs, { null }, api, { time })
        assertEquals("DeepSeek-V5-Flash", reopened.state.value.resolved().name)
        assertEquals(time, reopened.state.value.updatedAt)
        assertTrue(api.completions.isEmpty())
    }
    @Test fun failureRetainsCachedModelsAndThrottlesRetriesButManualRefreshRecovers() = withPrefs { prefs ->
        val api = FakeDeepSeekApi()
        var time = 1_000_000L
        val catalog = TranslationModelCatalog(prefs, { "test-only" }, api, { time })
        catalog.refresh()
        val previous = catalog.state.value.models
        time += 25 * 60 * 60 * 1000L
        api.list = { throw IOException("offline") }
        catalog.refresh()
        assertEquals(previous, catalog.state.value.models)
        assertNotNull(catalog.state.value.error)
        assertFalse(catalog.state.value.refreshing)
        catalog.refresh()
        assertEquals(2, api.listCalls)
        api.list = { """{"data":[{"id":"deepseek-flash","name":"Recovered"}]}""" }
        catalog.refresh(force = true)
        assertNull(catalog.state.value.error)
        assertEquals("Recovered", catalog.state.value.resolved().name)
    }
    @Test fun manualChoiceAndAutomaticUpdateOptOutSurviveNewModels() = withPrefs { prefs ->
        val api = FakeDeepSeekApi()
        val catalog = TranslationModelCatalog(prefs, { "test-only" }, api)
        catalog.select("my-pinned-api-id")
        catalog.setAutomaticUpdates(false)
        catalog.refresh()
        assertEquals(0, api.listCalls)
        catalog.refresh(force = true)
        assertEquals("my-pinned-api-id", catalog.state.value.resolved().id)
        val reopened = TranslationModelCatalog(prefs, { null }, api)
        assertEquals("my-pinned-api-id", reopened.state.value.selection)
        assertFalse(reopened.state.value.automaticUpdates)
    }
    @Test fun noKeyNeverSendsDiscoveryOrCompletionRequests() = withPrefs { prefs ->
        val api = FakeDeepSeekApi()
        val catalog = TranslationModelCatalog(prefs, { null }, api)
        catalog.refresh()
        catalog.refresh(force = true)
        assertEquals(0, api.listCalls)
        assertTrue(api.completions.isEmpty())
        assertNotNull(catalog.state.value.error)
        assertEquals(TranslationModels.FLASH_ALIAS, catalog.state.value.resolved().id)
    }
    @Test fun malformedResponseCannotReplaceAWorkingCatalog() = withPrefs { prefs ->
        val api = FakeDeepSeekApi()
        val catalog = TranslationModelCatalog(prefs, { "test-only" }, api)
        catalog.refresh()
        val previous = catalog.state.value
        api.list = { """{"data":[]}""" }
        catalog.refresh(force = true)
        assertEquals(previous.models, catalog.state.value.models)
        assertEquals(previous.updatedAt, catalog.state.value.updatedAt)
        assertNotNull(catalog.state.value.error)
    }
    @Test fun oldAccountResponseIsDiscardedWhenKeyChangesDuringDiscovery() = withPrefs { prefs ->
        val api = FakeDeepSeekApi()
        var key = "old-test-key"
        api.list = { key = "new-test-key"; """{"data":[{"id":"old-account-model"}]}""" }
        val catalog = TranslationModelCatalog(prefs, { key }, api)
        catalog.refresh()
        assertEquals(0, catalog.state.value.updatedAt)
        assertEquals(TranslationModels.defaults, catalog.state.value.models)
        api.list = { """{"data":[{"id":"deepseek-flash","name":"New account"}]}""" }
        catalog.refresh(force = true)
        assertEquals("New account", catalog.state.value.resolved().name)
    }
    @Test fun concurrentForegroundChecksShareOneSuccessfulDailyRefresh() = withPrefs { prefs ->
        val api = FakeDeepSeekApi()
        val catalog = TranslationModelCatalog(prefs, { "test-only" }, api)
        kotlinx.coroutines.coroutineScope { (0 until 8).map { async { catalog.refresh() } }.awaitAll() }
        assertEquals(1, api.listCalls)
    }
    @Test fun explicitSelectionIsNeverRemigratedAndClockRollbackRefreshes() = withPrefs { prefs ->
        prefs.edit().putString("model", "deepseek-v4-flash").apply()
        val api = FakeDeepSeekApi()
        var time = 1_000_000L
        val catalog = TranslationModelCatalog(prefs, { "test-only" }, api, { time })
        assertEquals(TranslationModels.AUTO_FLASH, catalog.state.value.selection)
        catalog.select("deepseek-v4-flash")
        catalog.refresh()
        val reopened = TranslationModelCatalog(prefs, { "test-only" }, api, { time })
        assertEquals("deepseek-v4-flash", reopened.state.value.selection)
        time -= 1_000
        reopened.refresh()
        assertEquals(2, api.listCalls)
    }
}
