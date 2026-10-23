package com.longzhousz.ami.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.CancellationException

/** Opt-in SAF inbox. Only explicitly shared JSON requests are read, never the audio library. */
class AgentBridge(private val repository: LibraryRepository) {
    private val context: Context get() = repository.context
    private val secret = SecretStore(context, "agent-bridge")
    private var busy = false
    data class ActionResult(val status: String = "success", val data: JSONObject? = null)
    fun newToken(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) }).also { secret.save(it) }

    suspend fun process(execute: suspend (String, JSONObject) -> ActionResult) {
        if (busy || !repository.prefs.getBoolean("bridgeEnabled", false)) return
        val root = repository.prefs.getString("bridgeFolder", null)?.let(Uri::parse) ?: return
        busy = true
        try {
            val files = withContext(Dispatchers.IO) { files(root) }
            for ((name, uri, size) in files.take(20)) {
                if (!name.endsWith(".request.json") || size > 32768) continue
                withContext(Dispatchers.IO) {
                    val request = runCatching { context.contentResolver.openInputStream(uri)?.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(4096)
                        while (true) { val n = input.read(buffer); if (n < 0) break; check(out.size() + n <= 32768); out.write(buffer, 0, n) }
                        JSONObject(out.toString("UTF-8"))
                    } }.getOrNull() ?: return@withContext null
                    request
                }?.let { request ->
                    val id = request.optString("request_id")
                    if (!id.matches(Regex("[A-Za-z0-9_-]{1,80}"))) return@let
                    val action = request.optString("action")
                    val result = withContext(Dispatchers.IO) {
                        val token = secret.read().orEmpty()
                        val sent = request.optString("token")
                        val previous = repository.prefs.getStringSet("bridgeProcessed", emptySet()).orEmpty()
                        when {
                            token.isBlank() || !MessageDigest.isEqual(token.toByteArray(), sent.toByteArray()) -> ActionResult("unauthorized")
                            id in previous -> ActionResult("duplicate")
                            action == "cache.cleanupApply" -> ActionResult("requires_confirmation")
                            (action.startsWith("player.") || action == "album.play") && !repository.prefs.getBoolean("agentPlayback", true) -> ActionResult("blocked")
                            action == "album.reorderTracks" && !repository.prefs.getBoolean("agentReorder", false) -> ActionResult("blocked")
                            action.startsWith("lyrics.") && !repository.prefs.getBoolean("agentTranslation", false) -> ActionResult("blocked")
                            action.startsWith("cache.") && !repository.prefs.getBoolean("agentCache", false) -> ActionResult("blocked")
                            else -> null
                        }
                    } ?: try {
                        // Reserve the ID before dispatch: a provider write failure must not replay effects.
                        withContext(Dispatchers.IO) {
                            val previous = repository.prefs.getStringSet("bridgeProcessed", emptySet()).orEmpty()
                            check(repository.prefs.edit().putStringSet("bridgeProcessed", (previous.toList().takeLast(499) + id).toSet()).commit())
                        }
                        execute(action, request.optJSONObject("payload") ?: JSONObject())
                    }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { ActionResult("failed") }
                    withContext(Dispatchers.IO) {
                        val body = JSONObject().put("request_id", id).put("action", action).put("status", result.status)
                            .put("ok", result.status == "success").put("created_at", java.time.Instant.now().toString())
                        result.data?.let { body.put("data", it) }
                        val target = DocumentsContract.createDocument(context.contentResolver,
                            DocumentsContract.buildDocumentUriUsingTree(root, DocumentsContract.getTreeDocumentId(root)), "application/json", "$id.result.json")
                        if (target != null) {
                            context.contentResolver.openOutputStream(target, "wt")!!.use { it.write(body.toString(2).toByteArray()) }
                            if (result.status != "unauthorized") {
                                val previous = repository.prefs.getStringSet("bridgeProcessed", emptySet()).orEmpty()
                                repository.prefs.edit().putStringSet("bridgeProcessed", (previous.toList().takeLast(499) + id).toSet()).apply()
                            }
                            DocumentsContract.deleteDocument(context.contentResolver, uri)
                        }
                        // Never persist the request payload or token.
                        repository.db.log(action.take(80), "agent", result.status)
                    }
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* A revoked provider does not affect music playback. */ }
        finally { busy = false }
    }
    private fun files(root: Uri): List<Triple<String, Uri, Long>> {
        val child = DocumentsContract.buildChildDocumentsUriUsingTree(root, DocumentsContract.getTreeDocumentId(root))
        return context.contentResolver.query(child, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { cursor ->
            buildList { while (cursor.moveToNext()) add(Triple(cursor.getString(0), DocumentsContract.buildDocumentUriUsingTree(root, cursor.getString(1)), cursor.getLong(2))) }
        }.orEmpty()
    }
}
