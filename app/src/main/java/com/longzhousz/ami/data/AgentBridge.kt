package com.longzhousz.ami.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

/** Opt-in SAF inbox. Only explicitly shared JSON requests are read, never the audio library. */
class AgentBridge(
    private val repository: LibraryRepository,
) {
    private val context: Context get() = repository.context
    private val secret = SecretStore(context, "agent-bridge")
    private val lock = Mutex()
    private val workScope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
    private val workers = mutableMapOf<String, Job>()
    private val inFlight = mutableMapOf<String, RequestDescriptor>()
    private val pending = mutableMapOf<String, PendingRequest>()
    private val requestSize = mutableMapOf<String, Long>()

    data class ActionResult(
        val status: String = AgentProtocol.STATUS_SUCCESS,
        val data: JSONObject? = null,
        val error: String? = null,
    )

    private data class RequestDescriptor(
        val action: String,
        val completion: CompletableDeferred<ActionResult>,
        val startedAt: Long,
        val bytes: Long,
    )

    private data class PendingRequest(
        val action: String,
        val payload: JSONObject,
        val uri: Uri,
        val destination: Uri,
        val root: Uri,
        val createdAt: Long,
        val bytes: Long,
    )

    data class RequestInfo(
        val requestId: String,
        val action: String,
        val state: String,
        val createdAt: Long,
        val bytes: Long,
    )

    fun newToken(): String =
        Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                ByteArray(32).also {
                    SecureRandom().nextBytes(it)
                },
            ).also { secret.save(it) }

    fun tokenConfigured(): Boolean = secret.read().orEmpty().isNotBlank()

    fun requestInfo(): List<RequestInfo> =
        synchronized(pending) {
            pending.map { (id, request) -> RequestInfo(id, request.action, AgentProtocol.STATUS_REQUIRES_CONFIRMATION, request.createdAt, request.bytes) }
        }.let { info ->
            synchronized(inFlight) { info + inFlight.map { (id, request) -> RequestInfo(id, request.action, "running", request.startedAt, request.bytes) } }
        }

    /** Confirmation is user-triggered in the UI; only the request ID crosses that boundary. */
    suspend fun confirm(
        requestId: String,
        accept: Boolean,
        execute: suspend (String, JSONObject) -> ActionResult,
    ) {
        val request =
            synchronized(pending) {
                pending[requestId]
            } ?: return
        val completion = CompletableDeferred<Unit>()
        val worker =
            workScope.launch {
                try {
                    val result =
                        when {
                            !hasPermissions(request.action, request.payload) -> ActionResult(AgentProtocol.STATUS_BLOCKED, error = AgentProtocol.ERROR_BLOCKED)
                            !accept -> ActionResult(AgentProtocol.STATUS_CANCELLED)
                            else -> executeSafely(request.action, request.payload, requestId, execute)
                        }
                    finalize(request.root, requestId, request.action, request.uri, result)
                } catch (_: Exception) {
                    Unit
                } finally {
                    withContext(Dispatchers.IO) { deleteQuietly(request.destination) }
                    synchronized(pending) { pending.remove(requestId) }
                    requestSize.remove(requestId)
                    completion.complete(Unit)
                }
            }
        try {
            completion.await()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            worker.cancel()
        }
    }

    suspend fun process(execute: suspend (String, JSONObject) -> ActionResult) {
        if (!lock.tryLock()) return
        try {
            val root = repository.prefs.getString("bridgeFolder", null)?.let(Uri::parse) ?: return
            if (!repository.prefs.getBoolean("bridgeEnabled", false)) return
            val secretValue =
                try {
                    secret.read().orEmpty()
                } catch (_: Exception) {
                    return
                }
            val files =
                withContext(Dispatchers.IO) {
                    runCatching { files(root) }.getOrDefault(emptyList())
                }
            clearExpiredPending(root, files)
            for ((name, uri, size) in files.take(20)) {
                if (!name.endsWith(".request.json") || size > 32768) continue
                handleRequest(root, secretValue, uri, size, execute)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A revoked provider or damaged token store must never affect music playback.
        } finally {
            lock.unlock()
        }
    }

    private suspend fun handleRequest(
        root: Uri,
        secretValue: String,
        uri: Uri,
        size: Long,
        execute: suspend (String, JSONObject) -> ActionResult,
    ) {
        val request = readRequest(uri) ?: return
        val id = request.optString("request_id")
        if (!AgentProtocol.requestId.matches(id)) return
        val action = request.optString("action")
        val payload = request.optJSONObject("payload") ?: JSONObject()
        val sent = request.optString("token")
        val processed = processed()
        requestSize[id] = size
        try {
            dispatch(root, id, action, payload, sent, uri, size, processed, secretValue, execute)
        } finally {
            requestSize.remove(id)
        }
    }

    @Suppress("LongParameterList")
    private suspend fun dispatch(
        root: Uri,
        id: String,
        action: String,
        payload: JSONObject,
        sent: String,
        uri: Uri,
        size: Long,
        processed: Set<String>,
        secretValue: String,
        execute: suspend (String, JSONObject) -> ActionResult,
    ) {
        val result =
            when {
                secretValue.isBlank() || !MessageDigest.isEqual(secretValue.toByteArray(), sent.toByteArray()) -> {
                    ActionResult(AgentProtocol.STATUS_UNAUTHORIZED, error = AgentProtocol.ERROR_INVALID_TOKEN)
                }

                isActiveRequest(id) -> {
                    return
                }

                id in processed -> {
                    ActionResult(AgentProtocol.STATUS_DUPLICATE)
                }

                !AgentProtocol.isKnown(action) -> {
                    val status = if (AgentProtocol.requiresUi(action)) AgentProtocol.STATUS_REQUIRES_UI else AgentProtocol.STATUS_UNKNOWN_ACTION
                    ActionResult(status)
                }

                AgentProtocol.requiresUi(action) -> {
                    ActionResult(AgentProtocol.STATUS_REQUIRES_UI)
                }

                AgentProtocol.forbiddenPayloadField(payload) != null -> {
                    ActionResult(AgentProtocol.STATUS_INVALID, error = AgentProtocol.ERROR_UNSUPPORTED_FIELD)
                }

                action == "agent.cancel" -> {
                    cancelRequest(payload.optString("target_request_id"))
                }

                !hasPermissions(action, payload) -> {
                    ActionResult(AgentProtocol.STATUS_BLOCKED)
                }

                action in AgentProtocol.confirmationActions -> {
                    val confirmation = requestConfirmation(id, action, payload, uri, size, root)
                    if (confirmation.status == AgentProtocol.STATUS_SUCCESS) return
                    confirmation
                }

                else -> {
                    val worker =
                        workScope.launch {
                            try {
                                finalize(root, id, action, uri, executeSafely(action, payload, id, execute))
                            } catch (_: Exception) {
                                Unit
                            }
                        }
                    synchronized(workers) { workers[id] = worker }
                    worker.invokeOnCompletion { synchronized(workers) { workers.remove(id) } }
                    return
                }
            }
        withContext(Dispatchers.IO) { finalize(root, id, action, uri, result) }
    }

    private suspend fun readRequest(uri: Uri) =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        check(out.size() + n <= 32768)
                        out.write(buffer, 0, n)
                    }
                    JSONObject(out.toString("UTF-8"))
                }
            }.getOrNull()
        }

    private suspend fun hasPermissions(
        action: String,
        payload: JSONObject,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val keys = AgentProtocol.permissionsFor(action).orEmpty().toMutableSet()
            val target = payload.optString("target")
            when {
                action == "like.read" || action == "like.set" -> keys += if (target == "cloud") "agentOnline" else "agentLibrary"
                action == "lyrics.import" || action == "lyrics.clear" -> keys += "agentLibrary"
            }
            keys.all { repository.prefs.getBoolean(it, AgentProtocol.permissionDefaults.getValue(it)) }
        }

    private suspend fun executeSafely(
        action: String,
        payload: JSONObject,
        requestId: String,
        execute: suspend (String, JSONObject) -> ActionResult,
    ): ActionResult {
        if (!isActiveRequest(requestId)) reserve(requestId)
        val completion = CompletableDeferred<ActionResult>()
        val bytes = requestSize[requestId] ?: payload.toString().length.toLong()
        synchronized(inFlight) { inFlight[requestId] = RequestDescriptor(action, completion, System.currentTimeMillis(), bytes) }
        try {
            return coroutineScope {
                val execution = async { runCatching { execute(action, payload) } }
                val result =
                    withTimeout(45_000L) {
                        select<ActionResult> {
                            execution.onAwait { it.getOrElse { ActionResult(AgentProtocol.STATUS_FAILED) } }
                            completion.onAwait { it }
                        }
                    }
                if (!completion.isCompleted) execution.cancel()
                result
            }
        } catch (timeout: TimeoutCancellationException) {
            return ActionResult(AgentProtocol.STATUS_TIMEOUT)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return ActionResult(AgentProtocol.STATUS_FAILED)
        } finally {
            synchronized(inFlight) { inFlight.remove(requestId) }
            requestSize.remove(requestId)
        }
    }

    private fun cancelRequest(target: String): ActionResult {
        if (!AgentProtocol.requestId.matches(target)) return ActionResult(AgentProtocol.STATUS_INVALID, error = AgentProtocol.ERROR_INVALID_REQUEST_ID)
        val completion =
            synchronized(inFlight) {
                inFlight[target]?.completion
            } ?: return ActionResult(AgentProtocol.STATUS_CONFLICT)
        completion.complete(ActionResult(AgentProtocol.STATUS_CANCELLED))
        return ActionResult()
    }

    private suspend fun requestConfirmation(
        id: String,
        action: String,
        payload: JSONObject,
        uri: Uri,
        size: Long,
        root: Uri,
    ): ActionResult =
        withContext(Dispatchers.IO) {
            val destination =
                createDocument(root, "$id.pending.json")
                    ?: return@withContext ActionResult(AgentProtocol.STATUS_FAILED)
            val written =
                runCatching {
                    context.contentResolver.openOutputStream(destination, "wt")?.use { output ->
                        output.write(
                            JSONObject()
                                .put("request_id", id)
                                .put("action", action)
                                .put("status", AgentProtocol.STATUS_REQUIRES_CONFIRMATION)
                                .put("created_at", Instant.now().toString())
                                .put("bytes", size)
                                .toString(2)
                                .toByteArray(),
                        )
                    } != null
                }.getOrDefault(false)
            if (!written) {
                deleteQuietly(destination)
                return@withContext ActionResult(AgentProtocol.STATUS_FAILED)
            }
            if (!isActiveRequest(id)) reserve(id)
            synchronized(pending) { pending[id] = PendingRequest(action, payload, uri, destination, root, System.currentTimeMillis(), size) }
            requestSize[id] = size
            ActionResult()
        }

    private suspend fun clearExpiredPending(
        root: Uri,
        files: List<Triple<String, Uri, Long>>,
    ) {
        val now = System.currentTimeMillis()
        val names = files.map { it.first }.toSet()
        val stale =
            withContext(Dispatchers.IO) {
                runCatching {
                    files(root)
                        .filter { it.first.endsWith(".pending.json") }
                        .mapNotNull { (name, uri, _) ->
                            val documentId = readPending(uri) ?: return@mapNotNull null
                            val id = documentId.optString("request_id")
                            val created = runCatching { Instant.parse(documentId.optString("created_at")).toEpochMilli() }.getOrNull() ?: return@mapNotNull null
                            Triple(id, uri, created)
                        }.filter { (id, _, created) ->
                            (now - created > 15 * 60_000L || id + ".request.json" !in names) && !isActiveRequest(id)
                        }
                }.getOrDefault(emptyList())
            }
        stale.forEach { (id, uri, _) ->
            synchronized(pending) { pending.remove(id) }
            requestSize.remove(id)
            withContext(Dispatchers.IO) { deleteQuietly(uri) }
            finalize(root, id, "", uri, ActionResult(AgentProtocol.STATUS_TIMEOUT))
        }
        val memory =
            synchronized(pending) {
                pending.entries
                    .filter { (_, request) -> now - request.createdAt > 15 * 60_000L }
                    .onEach { (id, _) -> pending.remove(id) }
                    .map { (id, request) -> Triple(id, request.uri, request) }
            }
        memory.forEach { (id, uri, request) ->
            requestSize.remove(id)
            withContext(Dispatchers.IO) { deleteQuietly(request.destination) }
            finalize(root, id, request.action, uri, ActionResult(AgentProtocol.STATUS_TIMEOUT))
        }
    }

    private fun readPending(uri: Uri) =
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input -> JSONObject(input.bufferedReader(Charsets.UTF_8).readText()) }
        }.getOrNull()

    private fun isActiveRequest(id: String) =
        synchronized(pending) { id in pending } ||
            synchronized(inFlight) { id in inFlight } ||
            synchronized(workers) { id in workers }

    private suspend fun reserve(requestId: String) =
        withContext(Dispatchers.IO) {
            val previous = processed()
            check(
                repository.prefs
                    .edit()
                    .putStringSet("bridgeProcessed", (previous.toList().takeLast(499) + requestId).toSet())
                    .commit(),
            )
        }

    private suspend fun processed() = withContext(Dispatchers.IO) { repository.prefs.getStringSet("bridgeProcessed", emptySet()).orEmpty() }

    private suspend fun finalize(
        root: Uri,
        id: String,
        action: String,
        uri: Uri,
        result: ActionResult,
    ): ActionResult =
        withContext(Dispatchers.IO) {
            val body =
                JSONObject()
                    .put("request_id", id)
                    .put("action", action)
                    .put("status", result.status)
                    .put("ok", result.status == AgentProtocol.STATUS_SUCCESS)
                    .put("created_at", Instant.now().toString())
            result.error?.let { body.put("error", it) }
            result.data?.let { body.put("data", it) }
            val target = createDocument(root, "$id.result.json")
            if (target != null) {
                runCatching {
                    context.contentResolver.openOutputStream(target, "wt")?.use { it.write(body.toString(2).toByteArray()) }
                }
                if (result.status != AgentProtocol.STATUS_UNAUTHORIZED && id !in repository.prefs.getStringSet("bridgeProcessed", emptySet()).orEmpty()) reserve(id)
                deleteQuietly(uri)
            }
            // Never persist the request payload or token.
            repository.db.log(action.take(80), "agent", result.status)
            result
        }

    private fun createDocument(
        root: Uri,
        displayName: String,
    ): Uri? =
        runCatching {
            DocumentsContract.createDocument(
                context.contentResolver,
                DocumentsContract.buildDocumentUriUsingTree(root, DocumentsContract.getTreeDocumentId(root)),
                "application/json",
                displayName,
            )
        }.getOrNull()

    private fun deleteQuietly(uri: Uri) {
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
    }

    private fun files(root: Uri): List<Triple<String, Uri, Long>> {
        val child = DocumentsContract.buildChildDocumentsUriUsingTree(root, DocumentsContract.getTreeDocumentId(root))
        return context.contentResolver
            .query(
                child,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(Triple(cursor.getString(0), DocumentsContract.buildDocumentUriUsingTree(root, cursor.getString(1)), cursor.getLong(2)))
                    }
                }
            }.orEmpty()
    }
}
