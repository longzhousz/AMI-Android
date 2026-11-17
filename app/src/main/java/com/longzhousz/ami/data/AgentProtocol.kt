package com.longzhousz.ami.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Pure Agent Bridge contract. Keep this free of Android and coroutine dependencies. */
internal object AgentProtocol {
    const val STATUS_SUCCESS = "success"
    const val STATUS_DUPLICATE = "duplicate"
    const val STATUS_INVALID = "invalid_request"
    const val STATUS_UNAUTHORIZED = "unauthorized"
    const val STATUS_BLOCKED = "blocked"
    const val STATUS_UNKNOWN_ACTION = "unknown_action"
    const val STATUS_REQUIRES_UI = "requires_ui"
    const val STATUS_REQUIRES_CONFIRMATION = "requires_confirmation"
    const val STATUS_CANCELLED = "cancelled"
    const val STATUS_TIMEOUT = "timeout"
    const val STATUS_CONFLICT = "conflict"
    const val STATUS_FAILED = "failed"

    const val ERROR_UNSUPPORTED_FIELD = "unsupported_field"
    const val ERROR_INVALID_PAYLOAD = "invalid_payload"
    const val ERROR_INVALID_REQUEST_ID = "invalid_request_id"
    const val ERROR_INVALID_TOKEN = "invalid_token"
    const val ERROR_BLOCKED = "permission_denied"

    val requestId = Regex("[A-Za-z0-9_-]{1,80}")

    val permissionDefaults =
        mapOf(
            "agentPlayback" to true,
            "agentQueue" to false,
            "agentReorder" to false,
            "agentTranslation" to false,
            "agentCache" to false,
            "agentOnline" to false,
            "agentLibrary" to false,
            "agentStats" to false,
        )

    val permissions =
        mapOf(
            "player.pause" to setOf("agentPlayback"),
            "player.play" to setOf("agentPlayback"),
            "player.toggle" to setOf("agentPlayback"),
            "player.next" to setOf("agentPlayback"),
            "player.previous" to setOf("agentPlayback"),
            "player.seek" to setOf("agentPlayback"),
            "player.jump" to setOf("agentPlayback"),
            "player.mode" to setOf("agentPlayback"),
            "player.stop" to setOf("agentPlayback"),
            "player.get" to setOf("agentPlayback"),
            "queue.get" to setOf("agentPlayback"),
            "queue.set" to setOf("agentPlayback", "agentQueue"),
            "queue.add" to setOf("agentPlayback", "agentQueue"),
            "queue.remove" to setOf("agentPlayback", "agentQueue"),
            "queue.clear" to setOf("agentPlayback", "agentQueue"),
            "queue.reorder" to setOf("agentPlayback", "agentQueue", "agentReorder"),
            "album.open" to setOf("agentLibrary"),
            "album.play" to setOf("agentPlayback"),
            "album.reorderTracks" to setOf("agentReorder"),
            "like.read" to emptySet<String>(),
            "like.set" to emptySet<String>(),
            "online.likes.read" to setOf("agentOnline"),
            "online.library.read" to setOf("agentOnline"),
            "online.tracks.read" to setOf("agentOnline"),
            "online.search" to setOf("agentOnline"),
            "online.discover" to setOf("agentOnline"),
            "online.library.refresh" to setOf("agentOnline"),
            "online.likes.refresh" to setOf("agentOnline"),
            "online.play" to setOf("agentOnline", "agentPlayback"),
            "online.prepare.cancel" to setOf("agentOnline"),
            "lyrics.translateCurrent" to setOf("agentTranslation"),
            "lyrics.clear" to setOf("agentTranslation"),
            "lyrics.import" to setOf("agentLibrary"),
            "lyrics.models.read" to setOf("agentTranslation"),
            "lyrics.models.refresh" to setOf("agentTranslation"),
            "lyrics.model.set" to setOf("agentTranslation"),
            "lyrics.style.set" to setOf("agentTranslation"),
            "library.scan" to setOf("agentLibrary"),
            "library.scan.cancel" to setOf("agentLibrary"),
            "library.remove" to setOf("agentLibrary"),
            "library.roots.read" to setOf("agentLibrary"),
            "library.roots.remove" to setOf("agentLibrary"),
            "cache.getStats" to setOf("agentCache"),
            "cache.cleanupDryRun" to setOf("agentCache"),
            "cache.cleanupApply" to setOf("agentCache"),
            "online.cache.cleanupApply" to setOf("agentCache"),
            "stats.report" to setOf("agentStats"),
            "stats.clear" to setOf("agentStats"),
            "bridge.status" to emptySet<String>(),
            "agent.cancel" to emptySet<String>(),
            "agent.requests" to emptySet<String>(),
        )

    /**
     * Known but deliberately unavailable to a JSON caller. Such an action never reaches a holder.
     * Fields with a '*' suffix are refused everywhere as credential or private-data carriers.
     */
    val uiOnlyActions =
        setOf(
            "auth.login",
            "auth.logout",
            "bridge.token.new",
            "bridge.folder.set",
            "bridge.setEnabled",
            "bridge.setPermission",
            "library.roots.add",
            "lyrics.attach",
            "backup.export",
            "backup.restore",
            "app.update.install",
        )

    val confirmationActions = setOf("cache.cleanupApply", "online.cache.cleanupApply", "stats.clear")

    private val forbiddenFields =
        setOf(
            "uri",
            "uris",
            "documentUri",
            "path",
            "filePath",
            "cookie",
            "cookies",
            "apiKey",
            "password",
            "captcha",
            "credential",
            "token",
            "lyrics",
            "translated",
        )

    fun isKnown(action: String) = action in permissions || action in uiOnlyActions

    fun requiresUi(action: String) = action in uiOnlyActions

    fun permissionsFor(action: String) = permissions[action]

    fun isTerminal(status: String) =
        status in
            setOf(
                STATUS_SUCCESS,
                STATUS_DUPLICATE,
                STATUS_INVALID,
                STATUS_UNAUTHORIZED,
                STATUS_BLOCKED,
                STATUS_UNKNOWN_ACTION,
                STATUS_REQUIRES_UI,
                STATUS_CANCELLED,
                STATUS_TIMEOUT,
                STATUS_CONFLICT,
                STATUS_FAILED,
            )

    private val normalizedForbiddenFields = forbiddenFields.map { it.lowercase(Locale.ROOT) }.toSet()

    fun forbiddenPayloadField(payload: JSONObject): String? {
        payload.keys().forEach { key ->
            val normalized = key.lowercase(Locale.ROOT)
            if (normalized in normalizedForbiddenFields || normalized.endsWith("uri") || normalized.endsWith("path")) return key
            when (val value = payload.opt(key)) {
                is JSONObject -> {
                    forbiddenPayloadField(value)?.let { return it }
                }

                is JSONArray -> {
                    for (i in 0 until value.length()) {
                        when (val item = value.opt(i)) {
                            is JSONObject -> forbiddenPayloadField(item)?.let { return it }
                            is JSONArray -> if (forbiddenPayloadField(JSONObject().put("nested", item)) != null) return "path"
                        }
                    }
                }

                is String -> {
                    if (value.contains("://") || (value.startsWith("/") && !key.equals("style", ignoreCase = true))) return key
                }
            }
        }
        return null
    }
}
