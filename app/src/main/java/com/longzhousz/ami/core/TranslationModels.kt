package com.longzhousz.ami.core

import org.json.JSONArray
import org.json.JSONObject

data class TranslationModel(val id: String, val name: String = id)

/** The provider's moving alias is authoritative; API list order is not a release order. */
object TranslationModels {
    const val AUTO_FLASH = "auto:flash"
    const val AUTO_PRO = "auto:pro"
    const val FLASH_ALIAS = "deepseek-flash"
    val defaults = listOf(TranslationModel(FLASH_ALIAS, "DeepSeek Flash（官方最新）"), TranslationModel("deepseek-v4-pro", "DeepSeek V4 Pro"))
    private val identifier = Regex("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")
    private val version = Regex("(?:^|[- ])v(\\d+(?:\\.\\d+)*)", RegexOption.IGNORE_CASE)
    private val experimental = Regex("(?:^|[-_. ])(?:preview|beta|alpha|exp|experimental)(?:$|[-_. \\d])", RegexOption.IGNORE_CASE)

    fun validId(value: String) = identifier.matches(value)
    fun validSelection(value: String) = value == AUTO_FLASH || value == AUTO_PRO || (validId(value) && !value.startsWith("auto:"))
    fun migrate(legacy: String?): String = when (legacy) {
        null, "", "deepseek-v4-flash", "deepseek-v4-flash-vision-exp", "deepseek-chat", "deepseek-reasoner" -> AUTO_FLASH
        else -> legacy.takeIf(::validId) ?: AUTO_FLASH
    }

    fun parse(body: String): List<TranslationModel> {
        val rows = JSONObject(body).getJSONArray("data")
        require(rows.length() <= 500) { "模型列表过大" }
        val models = buildList {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val id = row.optString("id").trim()
                if (!validId(id)) continue
                fun textSupported(key: String): Boolean {
                    if (!row.has(key)) return true // Older OpenAI-compatible responses omit modalities.
                    val values = row.optJSONArray(key) ?: return false
                    return (0 until values.length()).any { values.optString(it) == "text" }
                }
                if (!textSupported("input_modalities") || !textSupported("output_modalities")) continue
                if (id.contains(Regex("(?:embed|rerank|tts)", RegexOption.IGNORE_CASE))) continue
                val name = row.optString("name", id).filter { !it.isISOControl() }.take(160).ifBlank { id }
                add(TranslationModel(id, name))
            }
        }.distinctBy { it.id }
        require(models.isNotEmpty()) { "官方列表中没有可用于文字翻译的模型" }
        return models
    }

    fun encode(models: List<TranslationModel>): String = JSONObject().put("data", JSONArray().apply {
        models.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) }
    }).toString()

    fun resolve(selection: String, models: List<TranslationModel>): TranslationModel {
        require(validSelection(selection)) { "模型 ID 格式不正确" }
        if (selection != AUTO_FLASH && selection != AUTO_PRO) return models.find { it.id == selection } ?: TranslationModel(selection)
        val family = if (selection == AUTO_FLASH) "flash" else "pro"
        models.find { it.id == "deepseek-$family" }?.let { return it }
        val candidates = models.filter {
            (it.id.startsWith("deepseek-", ignoreCase = true)) &&
                Regex("(?:^|[-_. ])$family(?:$|[-_. ])", RegexOption.IGNORE_CASE).containsMatchIn("${it.id} ${it.name}") &&
                !experimental.containsMatchIn("${it.id} ${it.name}")
        }
        return candidates.maxWithOrNull { first, second ->
            val a = version.find("${first.id} ${first.name}")?.groupValues?.get(1)?.split('.')?.map { it.toIntOrNull() ?: 0 }.orEmpty()
            val b = version.find("${second.id} ${second.name}")?.groupValues?.get(1)?.split('.')?.map { it.toIntOrNull() ?: 0 }.orEmpty()
            var result = 0
            for (i in 0 until maxOf(a.size, b.size)) {
                result = (a.getOrElse(i) { 0 }).compareTo(b.getOrElse(i) { 0 })
                if (result != 0) break
            }
            if (result == 0) first.id.compareTo(second.id) else result
        } ?: error("官方列表中没有可自动跟随的 ${family.replaceFirstChar { it.uppercase() }} 模型，请手动选择")
    }
}
