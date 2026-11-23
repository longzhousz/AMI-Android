package com.longzhousz.ami.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longzhousz.ami.data.LibraryRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AgentBridgeCancellationTest {
    @Test fun cancelInterruptsTheExecutionBodyAndReportsCancelled() =
        runBlocking(Dispatchers.IO) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val name = "qa-agent-cancel-${System.nanoTime()}"
            val isolated =
                object : android.content.ContextWrapper(context) {
                    override fun getDatabasePath(dbName: String) = File(super.getDatabasePath(dbName).parentFile, "$name.sqlite3")
                }
            val repository = LibraryRepository(isolated)
            val bridge = AgentBridge(repository)
            val entered = CompletableDeferred<Unit>()
            val finished = CompletableDeferred<Unit>()
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            try {
                val result =
                    withTimeout(5000) {
                        val execution =
                            scope.async {
                                bridge.executeSafely("stats.report", JSONObject(), "req-1") { _, _ ->
                                    entered.complete(Unit)
                                    delay(60_000)
                                    finished.complete(Unit)
                                    AgentBridge.ActionResult()
                                }
                            }
                        entered.await()
                        assertEquals(AgentBridge.ActionResult().status, bridge.cancelRequest("req-1").status)
                        val value = execution.await()
                        assertEquals(AgentProtocol.STATUS_CANCELLED, value.status)
                        delay(100)
                        assertTrue("The cancelled execution must stop", !finished.isCompleted)
                        value
                    }
                assertEquals(AgentProtocol.STATUS_CANCELLED, result.status)
            } finally {
                scope.cancel()
                repository.scope.cancel()
                repository.db.close()
                isolated.deleteDatabase("$name.sqlite3")
            }
        }
}
