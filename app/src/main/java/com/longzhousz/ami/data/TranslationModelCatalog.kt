package com.longzhousz.ami.data

import android.content.SharedPreferences
import com.longzhousz.ami.core.TranslationModel
import com.longzhousz.ami.core.TranslationModels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

data class TranslationModelState(
    val models: List<TranslationModel>,
    val selection: String,
    val automaticUpdates: Boolean = true,
    val updatedAt: Long = 0,
    val refreshing: Boolean = false,
    val error: String? = null,
) {
    fun resolved() = TranslationModels.resolve(selection, models)
    val summary: String get() = when (selection) {
        TranslationModels.AUTO_FLASH -> "自动跟随最新 Flash"
        TranslationModels.AUTO_PRO -> "自动跟随最新 Pro"
        else -> models.find { it.id == selection }?.name ?: selection
    }
}

class TranslationModelCatalog internal constructor(
    private val prefs: SharedPreferences,
    private val readKey: () -> String?,
    private val api: DeepSeekApi = DeepSeekHttpApi(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    constructor(repository: LibraryRepository) : this(repository.prefs, { repository.secrets.read() })
    companion object {
        internal const val CACHE = "translationModels.cache.v1"
        internal const val UPDATED_AT = "translationModels.updatedAt"
        internal const val SELECTION = "translationModels.selection"
        private const val AUTO_UPDATE = "translationModels.autoUpdate"
        private const val DAY = 24 * 60 * 60 * 1000L
        private const val RETRY_DELAY = 5 * 60 * 1000L
    }
    private val mutex = Mutex()
    private var attemptedAt: Long? = null
    private val cachedModels = runCatching { TranslationModels.parse(prefs.getString(CACHE, "")!!) }.getOrNull()
    private val selection = prefs.getString(SELECTION, null)?.takeIf(TranslationModels::validSelection)
        ?: TranslationModels.migrate(prefs.getString("model", null)).also { prefs.edit().putString(SELECTION, it).apply() }
    private val mutableState = MutableStateFlow(TranslationModelState(
        models = cachedModels ?: TranslationModels.defaults,
        selection = selection,
        automaticUpdates = prefs.getBoolean(AUTO_UPDATE, true),
        updatedAt = if (cachedModels == null) 0 else prefs.getLong(UPDATED_AT, 0),
    ))
    val state = mutableState.asStateFlow()

    fun select(value: String) {
        require(TranslationModels.validSelection(value)) { "模型 ID 格式不正确" }
        if (value == TranslationModels.AUTO_FLASH || value == TranslationModels.AUTO_PRO) TranslationModels.resolve(value, state.value.models)
        prefs.edit().putString(SELECTION, value).apply()
        mutableState.update { it.copy(selection = value) }
    }
    fun setAutomaticUpdates(enabled: Boolean) {
        prefs.edit().putBoolean(AUTO_UPDATE, enabled).apply()
        mutableState.update { it.copy(automaticUpdates = enabled) }
    }

    suspend fun refresh(force: Boolean = false): TranslationModelState = withContext(Dispatchers.IO) {
        mutex.withLock {
            val time = now()
            val current = state.value
            if (!force && (!current.automaticUpdates ||
                    (current.updatedAt > 0 && time >= current.updatedAt && time - current.updatedAt < DAY) ||
                    attemptedAt?.let { time >= it && time - it < RETRY_DELAY } == true)) return@withLock current
            val apiKey = try { readKey() } catch (_: Exception) {
                mutableState.update { it.copy(error = "无法读取 API Key，请重新保存") }
                return@withLock state.value
            }
            if (apiKey.isNullOrBlank()) {
                if (force) mutableState.update { it.copy(error = "请先保存 DeepSeek API Key，再更新模型列表") }
                return@withLock state.value
            }
            attemptedAt = time
            mutableState.update { it.copy(refreshing = true, error = null) }
            try {
                val models = TranslationModels.parse(api.models(apiKey))
                currentCoroutineContext().ensureActive()
                // Key rotation/deletion must not publish a response for the previous account.
                if (readKey() == apiKey) {
                    val updated = now()
                    prefs.edit().putString(CACHE, TranslationModels.encode(models)).putLong(UPDATED_AT, updated).apply()
                    mutableState.update { it.copy(models = models, updatedAt = updated, error = null) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val reason = when (e) {
                    is java.net.SocketTimeoutException -> "连接超时"
                    is IOException -> "网络暂不可用"
                    else -> if (e is IllegalStateException || e is IllegalArgumentException) e.message else "模型列表格式异常"
                }
                mutableState.update { it.copy(error = "更新失败：${reason?.take(100) ?: "请稍后重试"}。已保留现有预设。") }
            } finally { mutableState.update { it.copy(refreshing = false) } }
            state.value
        }
    }
}
