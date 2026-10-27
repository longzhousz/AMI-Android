package com.longzhousz.ami.data

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ArtworkDownloaderTest {
    private val address = "https://p2.music.126.net/cover/109951168923742133.jpg"

    @Test fun upgradesRealNcmImageUrlsWithoutChangingTheirPathOrQuery() {
        assertEquals(address, ArtworkDownloader.url(address.replace("https:", "http:")))
        assertEquals("$address?param=600y600", ArtworkDownloader.url("$address?param=600y600#ignored"))
        assertEquals(address, ArtworkDownloader.url(" HTTPS://P2.MUSIC.126.NET/cover/109951168923742133.jpg "))
    }

    @Test fun untrustedSchemesHostsCredentialsAndPortsAreRejected() {
        listOf(null, "", "file:///sdcard/cover.jpg", "content://media/1", "https://127.0.0.1/cover.jpg",
            "https://p2.music.126.net.attacker.test/cover.jpg", "https://attacker.test/p2.music.126.net/cover.jpg",
            "https://user:password@p2.music.126.net/cover.jpg", "https://p2.music.126.net:8080/cover.jpg",
            "https://p2.music.126.net/\nother").forEach { assertNull(it, ArtworkDownloader.url(it)) }
    }

    @Test fun downloadsOverHttpsWithoutCredentialsAndClosesTheConnection() = runBlocking {
        val image = byteArrayOf(1, 2, 3)
        val connection = ImageConnection(image)
        var requested: String? = null
        val downloader = ArtworkDownloader { requested = it; connection }
        assertArrayEquals(image, downloader.download(address.replace("https:", "http:")))
        assertEquals(address, requested)
        assertNull(connection.getRequestProperty("Authorization"))
        assertFalse(connection.instanceFollowRedirects)
        assertTrue(connection.disconnected)
    }

    @Test fun redirectsNonImagesAndOversizedResponsesNeverBecomeCoverBytes() = runBlocking {
        val responses = listOf(
            ImageConnection(byteArrayOf(1), status = 302),
            ImageConnection(byteArrayOf(1), type = "text/html"),
            ImageConnection(byteArrayOf(1), declaredSize = ArtworkDownloader.MAX_BYTES.toLong() + 1),
            ImageConnection(ByteArray(ArtworkDownloader.MAX_BYTES + 1), declaredSize = -1),
            ImageConnection(byteArrayOf()),
        )
        responses.forEach { connection ->
            try { ArtworkDownloader { connection }.download(address); fail("Invalid image response accepted") }
            catch (_: IOException) { /* The scanner keeps the audio and retries artwork on a later scan. */ }
            assertTrue(connection.disconnected)
            assertFalse(connection.instanceFollowRedirects)
        }
        assertFalse(responses.first().opened)
    }

    @Test fun cancellationClosesABlockedDownloadAwayFromTheCancellingThread() = runBlocking {
        val entered = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val cancelledOn = Thread.currentThread()
        val closedOnCancellingThread = AtomicBoolean(false)
        val connection = object : HttpURLConnection(URL(address)) {
            override fun connect() = Unit
            override fun usingProxy() = false
            override fun disconnect() {
                if (Thread.currentThread() == cancelledOn) closedOnCancellingThread.set(true)
                closed.countDown()
            }
            override fun getResponseCode(): Int {
                entered.countDown()
                check(closed.await(15, TimeUnit.SECONDS))
                throw IOException("Cancelled")
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val request = scope.launch { ArtworkDownloader { connection }.download(address) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            request.cancel()
            assertTrue(closed.await(5, TimeUnit.SECONDS))
            withTimeout(5000) { request.join() }
            assertTrue(request.isCancelled)
            assertFalse(closedOnCancellingThread.get())
        } finally { closed.countDown(); scope.cancel() }
    }

    private class ImageConnection(bytes: ByteArray, private val status: Int = 200,
        private val type: String = "image/jpeg", private val declaredSize: Long = bytes.size.toLong()) : HttpURLConnection(URL("https://p2.music.126.net/test.jpg")) {
        private val input = ByteArrayInputStream(bytes)
        var disconnected = false
        var opened = false
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() { disconnected = true }
        override fun getResponseCode() = status
        override fun getContentType() = type
        override fun getContentLengthLong() = declaredSize
        override fun getInputStream(): InputStream { opened = true; return input }
    }
}
