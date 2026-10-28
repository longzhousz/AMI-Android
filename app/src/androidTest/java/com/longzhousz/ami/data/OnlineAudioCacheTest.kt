package com.longzhousz.ami.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class OnlineAudioCacheTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var cache: OnlineAudioCache
    private lateinit var tone: ByteArray
    private var payload = byteArrayOf()
    private var name = ""
    private var now = 0L
    private val owner = "test-account-hash"
    @Before fun prepare() {
        name = "qa-online-audio-${System.nanoTime()}"
        now = System.currentTimeMillis()
        tone = instrumentation.context.assets.open("qa-96k-24bit.flac").use { it.readBytes() }
        payload = tone
        cache = OnlineAudioCache(context, name, budget = tone.size * 2L, reserve = 0, open = { address ->
            object : HttpURLConnection(URL(address)) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy() = false
                override fun getResponseCode() = 200
                override fun getContentLengthLong() = tone.size.toLong()
                override fun getInputStream() = ByteArrayInputStream(payload)
            }
        })
        cache.switchAccount(owner)
    }
    @After fun cleanup() {
        cache.switchAccount(null); cache.close()
        context.deleteDatabase(File(context.noBackupFilesDir, "$name.db").path)
        File(context.cacheDir, name).deleteRecursively()
    }
    private fun hot(id: String, at: Long = now) {
        repeat(3) { cache.record(owner, id, 6000, at + it) }
    }
    private fun audio(md5: String? = null) = NeteaseAudio("https://m7.music.126.net/qa.flac", "hires", "flac", 2400000, tone.size.toLong(), md5)
    private fun save(id: String, at: Long = now) {
        hot(id, at)
        cache.download(owner, OnlineAudioCache.Candidate(id, 6000), audio(), at) { cache.scope() == owner }
    }

    @Test fun onlyThreeRecentEffectiveListensQualifyAndHistorySurvivesReopening() {
        assertFalse(cache.record(owner, "1", 6000, now - OnlineAudioCache.WEEK - 1))
        assertFalse(cache.record(owner, "1", 6000, now - 1000))
        assertFalse(cache.record(owner, "1", 6000, now))
        assertTrue(cache.candidates(owner, now).isEmpty())
        assertTrue(cache.record(owner, "1", 6000, now + 1000))
        assertEquals(listOf("1"), cache.candidates(owner, now + 1000).map { it.id })
        OnlineAudioCache(context, name, reserve = 0).use { reopened ->
            assertEquals(owner, reopened.scope())
            assertEquals("1", reopened.candidates(owner, now + 1000).single().id)
        }
    }
    @Test fun completeVerifiedAudioBecomesAFileWithTheOriginalQuality() {
        hot("1")
        val md5 = MessageDigest.getInstance("MD5").digest(tone).joinToString("") { "%02x".format(it) }
        cache.download(owner, OnlineAudioCache.Candidate("1", 6000), audio(md5), now) { true }
        val hit = cache.get(owner, "1", now)!!
        assertArrayEquals(tone, hit.file.readBytes())
        assertEquals("Hi-Res", hit.quality.label)
        assertTrue(hit.quality.cached)
        assertTrue(cache.candidates(owner, now).isEmpty())
    }
    @Test fun truncatedAndWrongChecksumDownloadsNeverBecomePlayable() {
        hot("1"); payload = tone.copyOf(tone.size / 2)
        assertTrue(runCatching { cache.download(owner, OnlineAudioCache.Candidate("1", 6000), audio(), now) { true } }.isFailure)
        assertNull(cache.get(owner, "1", now))
        payload = tone
        assertTrue(runCatching { cache.download(owner, OnlineAudioCache.Candidate("1", 6000), audio("0".repeat(32)), now) { true } }.isFailure)
        assertNull(cache.get(owner, "1", now))
        assertTrue(File(context.cacheDir, name).listFiles().orEmpty().isEmpty())
    }
    @Test fun durationMismatchAndCancellationNeverPublishPartialAudio() {
        hot("1")
        assertTrue(runCatching { cache.download(owner, OnlineAudioCache.Candidate("1", 180000), audio(), now) { true } }.isFailure)
        var checks = 0
        assertTrue(runCatching { cache.download(owner, OnlineAudioCache.Candidate("1", 6000), audio(), now) { ++checks < 3 } }.isFailure)
        assertNull(cache.get(owner, "1", now))
        assertTrue(File(context.cacheDir, name).listFiles().orEmpty().isEmpty())
    }
    @Test fun lruEvictionAndExpiryProtectThePlayingFileAndNeverTouchImportedMusic() {
        val imported = File(context.filesDir, "AudioCache/qa-source-$name.flac").apply { parentFile!!.mkdirs(); writeText("original") }
        try {
            save("1", now - 3000); save("2", now - 2000)
            cache.playingId = "1"
            save("3", now)
            assertNotNull(cache.get(owner, "1", now))
            assertNull(cache.get(owner, "2", now))
            assertNotNull(cache.get(owner, "3", now))
            cache.cleanup(now + OnlineAudioCache.WEEK + 10000)
            assertEquals(1, File(context.cacheDir, name).listFiles().orEmpty().count { it.extension == "audio" })
            cache.playingId = null
            cache.cleanup(now + OnlineAudioCache.WEEK + 10001)
            assertTrue(File(context.cacheDir, name).listFiles().orEmpty().isEmpty())
            assertEquals("original", imported.readText())
        } finally { imported.delete() }
    }
    @Test fun missingFilesAndAccountChangesFallBackWithoutSharingAccountAudio() {
        save("1")
        assertNull(cache.get("another-account", "1", now))
        cache.get(owner, "1", now)!!.file.delete()
        assertNull(cache.get(owner, "1", now))
        save("2")
        cache.switchAccount("another-account")
        assertNull(cache.get(owner, "2", now))
        assertTrue(cache.candidates("another-account", now).isEmpty())
        assertTrue(File(context.cacheDir, name).listFiles().orEmpty().isEmpty())
    }
}
