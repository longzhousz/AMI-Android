package com.longzhousz.ami.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longzhousz.ami.core.CloudTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReloginQueueCleanupTest {
    @Test fun signingIntoAnotherAccountDropsThePreviousQueueMetadata() =
        runBlocking(Dispatchers.IO) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val queueFile = File(context.cacheDir, "qa-relogin-queue-${System.nanoTime()}.json")
            var account = "account-a"
            val cloud =
                CloudMusic(
                    context,
                    true,
                    NeteaseTransport(),
                    queuePath = queueFile,
                    accountKeyOverride = { account },
                )
            try {
                val track = CloudTrack("101", "101", "A song", "Artist", "Album", 180_000, null, true)
                cloud.rememberQueue(listOf(track), "12345678-1234-1234-1234-123456789abc")
                assertTrue(queueFile.isFile)
                assertTrue(cloud.catalog.value.isNotEmpty())
                assertEquals("12345678-1234-1234-1234-123456789abc", cloud.radio.value)

                account = "account-b"
                cloud.clearQueueForRelogin()

                assertFalse("The previous account's queue file must not survive relogin", queueFile.exists())
                assertTrue(cloud.catalog.value.isEmpty())
                assertNull(cloud.radio.value)
            } finally {
                queueFile.delete()
            }
        }
}
