package com.longzhousz.ami.core

import java.time.Instant

enum class ErrorCode {
    PLAYER_COMMAND,
    ONLINE_QUEUE,
    ONLINE_DISCOVERY,
    ONLINE_LIBRARY,
    ONLINE_FAVORITES,
    LYRICS_READ,
    LYRICS_IMPORT,
    LYRICS_PREFETCH,
    TRANSLATION,
    TRANSLATION_KEY,
    TRANSLATION_CONNECTION,
    LIBRARY_WRITE,
    LIBRARY_SCAN,
    CACHE_MAINTENANCE,
    BACKUP_EXPORT,
    BACKUP_RESTORE,
    UPDATE_INSTALL,
    LISTENING_WRITE,
    STRICT_MODE,
}

data class ErrorEntry(
    val code: ErrorCode,
    val type: String,
    val time: Long,
)

/** Retains no Throwable, message, stack, URI, request, or user data. */
class ErrorJournal(
    private val capacity: Int = 50,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val entries = ArrayDeque<ErrorEntry>()

    init {
        require(capacity in 1..100)
    }

    @Synchronized
    fun record(
        code: ErrorCode,
        failure: Throwable,
    ) {
        val type = failure.javaClass.simpleName.takeIf { it.matches(TYPE) } ?: "UnknownException"
        if (entries.size == capacity) entries.removeFirst()
        entries.addLast(ErrorEntry(code, type, now()))
    }

    @Synchronized
    fun snapshot(): List<ErrorEntry> = entries.toList()

    @Synchronized
    fun clear() = entries.clear()

    fun recent(limit: Int = 5): String =
        snapshot().takeLast(limit.coerceIn(0, capacity)).joinToString("\n") {
            "${Instant.ofEpochMilli(it.time)} ${it.code.name} ${it.type}"
        }

    private companion object {
        val TYPE = Regex("[A-Za-z0-9_.$]{1,80}")
    }
}
