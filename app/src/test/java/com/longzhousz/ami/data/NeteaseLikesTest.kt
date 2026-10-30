package com.longzhousz.ami.data

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class NeteaseLikesTest {
    @Test(timeout = 10000) fun optimisticTapIsDeduplicatedAndOnlyFailuresRollItBack() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val writes = mutableListOf<Boolean>(); var confirmed = 0
        val likes = NeteaseLikes({ setOf("10") }, { _, id, value ->
            assertEquals("20", id); writes += value; entered.complete(Unit); release.await()
        })
        likes.refresh()
        val tap = launch(start = CoroutineStart.UNDISPATCHED) { likes.set("20", true, 0) { confirmed++ } }
        assertTrue("Tap feedback precedes the server response", "20" in likes.state.value.ids)
        assertTrue("20" in likes.state.value.pending)
        entered.await()
        assertFalse(likes.set("20", false, 0))
        release.complete(Unit); tap.join()
        assertEquals(listOf(true), writes); assertEquals(1, confirmed)
        assertTrue(likes.state.value.pending.isEmpty()); assertTrue("20" in likes.state.value.ids)
        val failing = NeteaseLikes({ setOf("20") }, { _, _, _ -> throw NeteaseRequestRejected(403) })
        failing.refresh()
        try { failing.set("20", false, 0); fail("Refused write accepted") } catch (_: NeteaseRequestRejected) { }
        assertTrue("20" in failing.state.value.ids); assertTrue(failing.state.value.pending.isEmpty())
    }

    @Test(timeout = 10000) fun anOlderReadPreservesATapAndKnownStateDoesNotReloadOnEveryEntry() = runBlocking {
        val reads = AtomicInteger(); val readEntered = CompletableDeferred<Unit>(); val readRelease = CompletableDeferred<Unit>()
        val writeEntered = CompletableDeferred<Unit>(); val writeRelease = CompletableDeferred<Unit>()
        val likes = NeteaseLikes({
            if (reads.incrementAndGet() > 1) { readEntered.complete(Unit); readRelease.await() }
            setOf("10")
        }, { _, _, _ -> writeEntered.complete(Unit); writeRelease.await() })
        likes.refresh(); likes.refresh()
        assertEquals(1, reads.get())
        val refresh = launch { likes.refresh(force = true) }; readEntered.await()
        val tap = launch(start = CoroutineStart.UNDISPATCHED) { likes.set("20", true, 0) }
        readRelease.complete(Unit); writeEntered.await()
        assertEquals(setOf("10", "20"), likes.state.value.ids)
        assertEquals(setOf("20"), likes.state.value.pending)
        writeRelease.complete(Unit); refresh.join(); tap.join()
        assertEquals(2, reads.get()); assertTrue(likes.state.value.pending.isEmpty())
    }

    @Test(timeout = 10000) fun changingAccountsDiscardsOldReadsAndDoesNotSendQueuedWritesForTheNewAccount() = runBlocking {
        val reads = AtomicInteger(); val writes = AtomicInteger()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val likes = NeteaseLikes({ session ->
            if (session == 1L) setOf("30") else {
                if (reads.incrementAndGet() > 1) { entered.complete(Unit); release.await() }
                setOf("10")
            }
        }, { _, _, _ -> writes.incrementAndGet() })
        likes.refresh()
        val oldRead = launch { likes.refresh(force = true) }; entered.await()
        val queued = launch(start = CoroutineStart.UNDISPATCHED) { likes.set("20", true, 0) }
        likes.reset(); release.complete(Unit); oldRead.join(); queued.join()
        assertTrue(queued.isCancelled); assertEquals(0, writes.get())
        assertFalse(likes.state.value.ready); assertTrue(likes.state.value.ids.isEmpty())
        likes.refresh(); assertEquals(setOf("30"), likes.state.value.ids)
        try { likes.set("20", true, 0); fail("Old account action accepted") } catch (_: CancellationException) { }
        assertEquals(0, writes.get()); assertEquals(1L, likes.state.value.session)
    }

    @Test(timeout = 10000) fun completionOfAnOldInFlightWriteCannotChangeTheNewAccountSnapshot() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val likes = NeteaseLikes({ if (it == 0L) setOf("10") else setOf("30") }, { session, _, _ ->
            assertEquals(0L, session); entered.complete(Unit); release.await()
        })
        likes.refresh()
        val old = launch(start = CoroutineStart.UNDISPATCHED) { likes.set("20", true, 0) }
        entered.await(); likes.reset()
        val fresh = launch { likes.refresh() }
        release.complete(Unit); old.join(); fresh.join()
        assertEquals(setOf("30"), likes.state.value.ids)
        assertTrue(likes.state.value.pending.isEmpty()); assertEquals(1L, likes.state.value.session)
    }
}
