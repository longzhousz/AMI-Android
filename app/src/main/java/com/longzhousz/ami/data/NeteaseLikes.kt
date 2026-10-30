package com.longzhousz.ami.data

import com.longzhousz.ami.core.CloudLikes
import com.longzhousz.ami.core.neteaseId
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One account snapshot, no disk copy or background retry queue. Read/write requests are serialized. */
internal class NeteaseLikes(private val read: suspend (Long) -> Set<String>,
    private val write: suspend (Long, String, Boolean) -> Unit) {
    val state = MutableStateFlow(CloudLikes())
    private val lock = Any()
    private val network = Mutex()
    fun reset() = synchronized(lock) { state.value = CloudLikes(session = state.value.session + 1) }

    suspend fun refresh(force: Boolean = false) {
        val session = state.value.session
        withContext(Dispatchers.IO) { network.withLock {
            if (state.value.session != session || state.value.ready && !force) return@withLock
            var ids = read(session).toPersistentSet()
            synchronized(lock) {
                val current = state.value
                if (current.session != session) return@synchronized
                // A read started before a tap must preserve that tap's optimistic state.
                current.pending.forEach { id -> ids = if (id in current.ids) ids.add(id) else ids.remove(id) }
                state.value = current.copy(ids = ids, ready = true)
            }
        } }
    }

    suspend fun set(id: String, liked: Boolean, session: Long, confirmed: () -> Unit = {}): Boolean {
        neteaseId(id)
        val before = synchronized(lock) {
            val current = state.value
            if (current.session != session) throw CancellationException("网易云账号已变化")
            check(current.ready) { "红心状态暂时无法读取，请刷新后重试" }
            if (id in current.pending || (id in current.ids) == liked) return false
            check(current.pending.size < 16) { "红心正在同步，请稍后再试" }
            state.value = current.copy(ids = if (liked) current.ids.add(id) else current.ids.remove(id), pending = current.pending.add(id))
            id in current.ids
        }
        var success = false
        try {
            withContext(Dispatchers.IO) { network.withLock {
                if (state.value.session != session) throw CancellationException("网易云账号已变化")
                write(session, id, liked)
                success = true
                confirmed()
            } }
            return true
        } finally {
            synchronized(lock) {
                val current = state.value
                if (current.session == session) state.value = current.copy(
                    ids = if (success) current.ids else if (before) current.ids.add(id) else current.ids.remove(id),
                    pending = current.pending.remove(id))
            }
        }
    }
}
