package com.longzhousz.ami.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longzhousz.ami.core.CloudTrack
import com.longzhousz.ami.core.PlaybackSnapshot
import com.longzhousz.ami.core.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Saves a mixed queue under one account key and restores it as another account. */
@RunWith(AndroidJUnit4::class)
class OnlineQueueAccountTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun track(
        id: String,
        title: String,
    ) = CloudTrack(id, id, title, "Artist", "Album", 180_000, null, true)

    @Test fun anotherAccountsQueueKeepsLocalTracksAndDropsItsOnlineTracks() =
        runBlocking(Dispatchers.IO) {
            val queueFile = File(context.cacheDir, "qa-online-queue-${System.nanoTime()}.json")
            try {
                CloudMusic(
                    context,
                    true,
                    NeteaseTransport(),
                    queuePath = queueFile,
                    accountKeyOverride = { "account-a" },
                ).rememberQueue(listOf(track("101", "A one"), track("102", "A two")))
                assertTrue(queueFile.isFile)

                val localOne = Song("qa-local-one", "file:///unused-1", folder = "qa", title = "Local one")
                val localTwo = Song("qa-local-two", "file:///unused-2", folder = "qa", title = "Local two")
                val snapshot =
                    PlaybackSnapshot(
                        listOf(localOne.id, "netease:101", localTwo.id, "netease:102"),
                        index = 2,
                        position = 1500,
                    )

                val accountB =
                    CloudMusic(
                        context,
                        true,
                        NeteaseTransport(),
                        queuePath = queueFile,
                        accountKeyOverride = { "account-b" },
                    )
                assertTrue("Account A's online tracks must not return", accountB.restore(snapshot.queue).isEmpty())
                assertFalse("Account A's queue metadata must be discarded", queueFile.exists())

                val (songs, location) = snapshot.restore(listOf(localOne, localTwo))
                assertEquals(listOf(localOne.id, localTwo.id), songs.map { it.id })
                assertEquals("The selected local track keeps its saved position", 1 to 1500L, location)

                val selectedOnline = snapshot.copy(index = 1, position = 2000)
                assertEquals("A local track after the missing online item continues from its start", 1 to 0L, selectedOnline.restore(listOf(localOne, localTwo)).second)
            } finally {
                queueFile.delete()
            }
        }

    @Test fun aCachedQueueIsDroppedWhenTheAccountChangesInProcess() =
        runBlocking(Dispatchers.IO) {
            val queueFile = File(context.cacheDir, "qa-online-queue-${System.nanoTime()}.json")
            var account = "account-a"
            try {
                val cloud =
                    CloudMusic(
                        context,
                        true,
                        NeteaseTransport(),
                        queuePath = queueFile,
                        accountKeyOverride = { account },
                    )
                cloud.rememberQueue(listOf(track("101", "A one")))
                assertEquals(listOf("netease:101"), cloud.restore(listOf("netease:101")).map { it.id })
                account = "account-b"
                assertTrue(cloud.restore(listOf("netease:101")).isEmpty())
                assertTrue(cloud.catalog.value.isEmpty())
            } finally {
                queueFile.delete()
            }
        }

    @Test fun aLegacyFmQueueForTheSameAccountStillRestores() =
        runBlocking(Dispatchers.IO) {
            val queueFile = File(context.cacheDir, "qa-online-queue-${System.nanoTime()}.json")
            val token = "12345678-1234-1234-1234-123456789abc"
            try {
                CloudMusic(
                    context,
                    true,
                    NeteaseTransport(),
                    queuePath = queueFile,
                    accountKeyOverride = { "account-a" },
                ).rememberQueue(listOf(track("101", "A one")), token)
                val legacy = JSONObject(queueFile.readText()).apply { remove("account") }
                queueFile.writeText(legacy.toString())

                val restarted =
                    CloudMusic(
                        context,
                        true,
                        NeteaseTransport(),
                        queuePath = queueFile,
                        accountKeyOverride = { "account-a" },
                    )
                assertEquals(listOf("netease:101"), restarted.restore(listOf("netease:101")).map { it.id })
                assertEquals("A legacy FM queue proves its owner through radioAccount", token, restarted.radio.value)
            } finally {
                queueFile.delete()
            }
        }

    @Test fun signingOutClearsTheQueueFileCatalogAndRadio() =
        runBlocking(Dispatchers.IO) {
            val queueFile = File(context.cacheDir, "qa-online-queue-${System.nanoTime()}.json")
            val token = "12345678-1234-1234-1234-123456789abc"
            val cloud =
                CloudMusic(
                    context,
                    false,
                    NeteaseTransport(),
                    queuePath = queueFile,
                    accountKeyOverride = { "account-a" },
                )
            try {
                cloud.rememberQueue(listOf(track("101", "A one")), token)
                assertTrue(queueFile.isFile)
                assertTrue(cloud.catalog.value.isNotEmpty())
                assertEquals(token, cloud.radio.value)

                cloud.disconnect()

                assertFalse("Signing out must delete the queue metadata", queueFile.exists())
                assertTrue(cloud.catalog.value.isEmpty())
                assertNull(cloud.radio.value)
            } finally {
                queueFile.delete()
            }
        }

    @Test fun theSameAccountStillRestoresItsOnlineQueue() =
        runBlocking(Dispatchers.IO) {
            val queueFile = File(context.cacheDir, "qa-online-queue-${System.nanoTime()}.json")
            try {
                CloudMusic(
                    context,
                    true,
                    NeteaseTransport(),
                    queuePath = queueFile,
                    accountKeyOverride = { "account-a" },
                ).rememberQueue(listOf(track("101", "A one"), track("102", "A two")))

                val restarted =
                    CloudMusic(
                        context,
                        true,
                        NeteaseTransport(),
                        queuePath = queueFile,
                        accountKeyOverride = { "account-a" },
                    )
                assertEquals(
                    listOf("netease:101", "netease:102"),
                    restarted.restore(listOf("netease:101", "netease:102")).map { it.id },
                )
                assertTrue("A matching queue stays available for the next restore", queueFile.exists())
            } finally {
                queueFile.delete()
            }
        }
}
