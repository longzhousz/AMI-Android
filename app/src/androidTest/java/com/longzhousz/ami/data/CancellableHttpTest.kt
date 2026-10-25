package com.longzhousz.ami.data

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class CancellableHttpTest {
    @Test fun cancellingABlockedRequestClosesItsConnectionOffTheMainThread() {
        val entered=CountDownLatch(1);val disconnected=CountDownLatch(1)
        val closedOnMain=AtomicBoolean(false)
        val connection=object:HttpURLConnection(URL("https://api.deepseek.com/models")) {
            override fun connect()=Unit
            override fun usingProxy()=false
            override fun disconnect() {
                if (Looper.myLooper()==Looper.getMainLooper()) closedOnMain.set(true)
                disconnected.countDown()
            }
            override fun getResponseCode():Int {
                entered.countDown()
                check(disconnected.await(20,TimeUnit.SECONDS))
                throw IOException("Cancelled fixture request")
            }
        }
        val api=DeepSeekHttpApi { connection }
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        lateinit var request:Job
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                request=scope.launch { api.models("test-only") }
            }
            assertTrue(entered.await(10,TimeUnit.SECONDS))
            InstrumentationRegistry.getInstrumentation().runOnMainSync { request.cancel() }
            assertTrue(disconnected.await(10,TimeUnit.SECONDS))
            runBlocking { withTimeout(10000) { request.join() } }
            assertTrue(request.isCancelled)
            assertFalse("Connection.close must not block main",closedOnMain.get())
        } finally { disconnected.countDown();scope.cancel() }
    }
}
