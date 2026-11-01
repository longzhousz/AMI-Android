package com.longzhousz.ami.data

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class NeteaseTransportTest {
    @Test fun requestsStayOnFirstPartyHttpsWithNoRedirectAndCloseAfterSuccess() = runBlocking {
        val connection = Response("{\"code\":200}")
        var address = ""
        val client = NeteaseTransport { address = it; connection }
        assertEquals(200, client.post("user/playlist", JSONObject(), "MUSIC_U=test; __csrf=token").getInt("code"))
        assertEquals("https://music.163.com/weapi/user/playlist?csrf_token=token", address)
        assertEquals("MUSIC_U=test; __csrf=token", connection.getRequestProperty("Cookie"))
        assertNull(connection.getRequestProperty("Authorization"))
        assertFalse(connection.instanceFollowRedirects)
        assertTrue(connection.closed)
        assertTrue(connection.body.toString("UTF-8").startsWith("params="))
        assertFalse(connection.body.toString("UTF-8").contains("csrf_token"))
    }
    @Test fun redirectsExpiredLoginAndOversizedResponsesAreRejected() = runBlocking {
        val responses = listOf(Response("{}", 302), Response("{\"code\":301}"), Response("x".repeat(2_000_001)))
        for (connection in responses) {
            try { NeteaseTransport { connection }.post("user/playlist", JSONObject(), "MUSIC_U=test"); fail("Bad response accepted") }
            catch (_: IllegalStateException) { } catch (_: IllegalArgumentException) { }
            assertTrue(connection.closed)
        }
        assertFalse(responses.first().opened)
    }
    @Test fun clientAudioStaysOnTheFixedHttpsEndpointAndUsesTheExistingSession() {
        val connection = Response("{\"code\":200}")
        var address = ""
        NeteaseTransport { address = it; connection }.blockingAudio(JSONObject().put("level", "jyeffect").put("encodeType", "flac"), "MUSIC_U=test")
        assertEquals("https://interface.music.163.com/eapi/song/enhance/player/url/v1", address)
        assertTrue(connection.getRequestProperty("Cookie").split("; ").contains("MUSIC_U=test"))
        assertTrue(connection.getRequestProperty("Cookie").contains("os=android"))
        assertTrue(connection.getRequestProperty("Cookie").contains("appver=9.5.61"))
        assertTrue(connection.getRequestProperty("User-Agent").startsWith("NeteaseMusic/9.5.61"))
        assertFalse(connection.instanceFollowRedirects)
        assertTrue(connection.closed)
        assertTrue(connection.body.toString("UTF-8").matches(Regex("params=[0-9A-F]+")))
    }
    @Test fun bothClientContextsKeepCookieAndEncryptedHeaderConsistentWithoutUsingAmisVersion() {
        for (desktop in listOf(false, true)) {
            val connection = Response("{\"code\":200}")
            var address = ""
            NeteaseTransport { address = it; connection }.blockingAudio(JSONObject().put("level", "jyeffect"),
                "MUSIC_U=a%2Fb+c%3D; __csrf=csrf; NMTID=session", desktop)
            assertEquals(if (desktop) "interfacepc.music.163.com" else "interface.music.163.com", URL(address).host)
            val hex = connection.body.toString("UTF-8").removePrefix("params=")
            val raw = javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding").run {
                init(javax.crypto.Cipher.DECRYPT_MODE, javax.crypto.spec.SecretKeySpec("e82ckenh8dichen8".toByteArray(), "AES"))
                doFinal(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()).toString(Charsets.UTF_8)
            }
            val payload = JSONObject(raw.split("-36cd479b6b5-")[1])
            val header = payload.getJSONObject("header")
            val cookies = connection.getRequestProperty("Cookie").split("; ").associate { it.substringBefore('=') to
                java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            header.keys().forEach { assertEquals(header.getString(it), cookies[it]) }
            assertEquals("a/b+c=", header.getString("MUSIC_U"))
            assertEquals(if (desktop) "pc" else "android", header.getString("os"))
            assertEquals(if (desktop) "3.1.17.204416" else "9.5.61", header.getString("appver"))
            assertEquals("csrf", payload.getString("csrf_token"))
            assertFalse(payload.getBoolean("e_r"))
            assertFalse(connection.getRequestProperty("Cookie").contains("MUSIC_A="))
        }
    }
    @Test fun preheatOnlyAcceptsSessionFieldsFromTheFixedFirstPartyHomepage() {
        val connection = object : Response("unused HTML") {
            override fun getHeaderFields(): Map<String, List<String>> = mapOf("Set-Cookie" to listOf(
                "__csrf=csrf-value; Path=/; Secure", "NMTID=new-session; Path=/", "MUSIC_U=foreign-account; Path=/", "MUSIC_A=anonymous; Path=/"))
        }
        var address = ""
        val result = NeteaseTransport { address = it; connection }.preheat("MUSIC_U=original")
        assertEquals("https://music.163.com/", address)
        assertEquals("MUSIC_U=original; __csrf=csrf-value; NMTID=new-session", result)
        assertFalse(connection.instanceFollowRedirects)
        assertTrue(connection.closed)
        assertFalse(connection.opened)
        val redirected = Response("unused", 302)
        assertEquals("MUSIC_U=original", NeteaseTransport { redirected }.preheat("MUSIC_U=original"))
        assertTrue(redirected.closed); assertFalse(redirected.opened)
    }
    @Test fun forbiddenEndpointIsNotMisreportedAsExpiredMembershipOrLogin() {
        for (connection in listOf(Response("{}", 403), Response("{\"code\":403}"))) {
            try {
                NeteaseTransport { connection }.blockingPost("song/enhance/player/url/v1", JSONObject(), "MUSIC_U=test")
                fail("Refusal accepted")
            } catch (failure: NeteaseRequestRejected) { assertEquals(403, failure.code) }
            assertTrue(connection.closed)
        }
        for (connection in listOf(Response("{}", 401), Response("{\"code\":301}"))) {
            try {
                NeteaseTransport { connection }.blockingPost("w/nuser/account/get", JSONObject(), "MUSIC_U=test")
                fail("Expired session accepted")
            } catch (_: NeteaseLoginExpired) { }
            assertTrue(connection.closed)
        }
    }
    @Test fun cancellationDisconnectsAwayFromTheCancellingThread() = runBlocking {
        val entered = CountDownLatch(1); val closed = CountDownLatch(1)
        val caller = Thread.currentThread(); val wrongThread = AtomicBoolean(false)
        val connection = object : Response("{}") {
            override fun getResponseCode(): Int { entered.countDown(); check(closed.await(10, TimeUnit.SECONDS)); throw IOException("cancelled") }
            override fun disconnect() { if (Thread.currentThread() == caller) wrongThread.set(true); closed.countDown(); super.disconnect() }
        }
        val job = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch { NeteaseTransport { connection }.post("user/playlist", JSONObject(), "MUSIC_U=test") }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS)); job.cancel()
            assertTrue(closed.await(5, TimeUnit.SECONDS)); withTimeout(5000) { job.join() }
            assertFalse(wrongThread.get())
        } finally { closed.countDown(); job.cancel() }
    }
    @Test fun nativeMetadataKeepsVipTracksAndRejectsTrialOrForeignAudio() {
        val row = JSONObject("""{"id":1234,"name":"测试歌曲","dt":180000,"ar":[{"name":"测试歌手"}],"al":{"name":"测试专辑","picUrl":"http://p1.music.126.net/cover.jpg"}}""")
        assertTrue(NeteaseAccount.track(row, JSONObject("{\"fee\":1,\"st\":0}")).getBoolean("available"))
        assertFalse(NeteaseAccount.track(row, JSONObject("{\"st\":-200}")).getBoolean("available"))
        assertEquals("测试歌手", NeteaseAccount.track(row).getString("artist"))
        assertEquals("https://p1.music.126.net/cover.jpg", NeteaseAccount.track(row).getString("cover"))
        fun response(url: String, extra: String = "") = JSONObject("""{"code":200,"data":[{"id":1234,"code":200,"url":"$url","time":180000$extra}]}""")
        assertEquals("https://m7.music.126.net/song.mp3", NeteaseAccount.streamUrl(response("http://m7.music.126.net/song.mp3"), "1234", 180000))
        val invalid = listOf(response("https://attacker.test/song.mp3"), response("https://m7.music.126.net/song.mp3", ",\"freeTrialInfo\":{\"start\":0,\"end\":30}"),
            response("https://m7.music.126.net/song.mp3").getJSONArray("data").getJSONObject(0).put("time", 30000).let { JSONObject().put("data", org.json.JSONArray().put(it)) })
        invalid.forEach { try { NeteaseAccount.streamUrl(it, "1234", 180000); fail("Invalid stream accepted") } catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { } }
        try { NeteaseAccount.streamUrl(response("https://m7.music.126.net/song.mp3"), "9999", 180000); fail("Wrong song accepted") } catch (_: IllegalArgumentException) { }
    }
    private open class Response(private val text: String, private val status: Int = 200) : HttpURLConnection(URL("https://music.163.com/")) {
        val body = ByteArrayOutputStream(); var closed = false; var opened = false
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() { closed = true }
        override fun getOutputStream() = body
        override fun getInputStream() = ByteArrayInputStream(text.toByteArray()).also { opened = true }
        override fun getResponseCode() = status
    }
}
