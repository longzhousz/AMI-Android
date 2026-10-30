package com.longzhousz.ami.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NeteaseAudioTest {
    private fun response(level: String, codec: String = "flac", trial: Boolean = false): JSONObject = JSONObject().put("code", 200)
        .put("data", org.json.JSONArray().put(JSONObject().put("id", 1234).put("code", 200).put("time", 180000)
            .put("url", "https://m7.music.126.net/audio.$codec").put("level", level).put("type", codec).put("br", 1200000)
            .apply { if (trial) put("freeTrialInfo", JSONObject().put("end", 30)) }))

    @Test fun highestAuthorizedSourceStopsWithoutRedundantRequests() {
        val requested = mutableListOf<String>()
        val audio = bestNeteaseAudio("1234", 180000) { requested += it; response("jyeffect") }
        assertEquals(listOf("jyeffect"), requested)
        assertEquals("jyeffect", audio.level)
        assertEquals("flac", audio.codec)
        requested.clear()
        assertEquals("hires", bestNeteaseAudio("1234", 180000) { requested += it; response("hires") }.level)
        assertEquals(1, requested.size)
    }
    @Test fun lowerResponsesAreMeasuredAndTheBestSourceIsRetained() {
        val requested = mutableListOf<String>()
        val audio = bestNeteaseAudio("1234", 180000) { requested += it; response(if (it == "jyeffect") "exhigh" else "lossless", if (it == "jyeffect") "mp3" else "flac") }
        assertEquals(listOf("jyeffect", "hires"), requested)
        assertEquals("lossless", audio.level)
    }
    @Test fun lossyFilesCannotClaimLosslessQualityAndTrialsAreNeverPlayed() {
        val fake = neteaseAudio(response("hires", "aac"), "1234", 180000)
        assertNull(fake.level)
        val requested = mutableListOf<String>()
        val audio = bestNeteaseAudio("1234", 180000) { level ->
            requested += level
            response(if (level == "exhigh") "exhigh" else level, if (level == "exhigh") "mp3" else "flac", trial = level != "exhigh")
        }
        assertEquals(neteaseAudioLevels, requested)
        assertEquals("exhigh", audio.level)
        try { bestNeteaseAudio("1234", 180000) { response(it, trial = true) }; fail("Trial accepted") } catch (_: IllegalStateException) { }
    }
    @Test fun expiredLoginWrongSongAndForeignCdnStopResolution() {
        var count = 0
        try { bestNeteaseAudio("1234", 180000) { count++; throw NeteaseLoginExpired() }; fail("Expired login accepted") } catch (_: NeteaseLoginExpired) { }
        assertEquals(1, count)
        for (wrongSong in listOf(true, false)) {
            count = 0
            try {
                bestNeteaseAudio("1234", 180000) {
                    count++
                    response(it).apply { getJSONArray("data").getJSONObject(0).put(if (wrongSong) "id" else "url", if (wrongSong) "9999" else "https://foreign.test/audio.flac") }
                }
                fail("Invalid source accepted")
            } catch (_: IllegalArgumentException) { }
            assertEquals(1, count)
        }
    }

    @Test fun aLaterQualityFailureKeepsTheUsableSourceAlreadyReturned() {
        for (failure in listOf(NeteaseRequestRejected(403), NeteaseRequestRejected(503), java.io.IOException("private CDN URL"))) {
            var calls = 0
            val audio = bestNeteaseAudio("1234", 180000) {
                if (++calls == 1) response("exhigh", "mp3") else throw failure
            }
            assertEquals("exhigh", audio.level)
            assertTrue(calls <= 4)
        }
        val requested = mutableListOf<String>()
        assertEquals("hires", bestNeteaseAudio("1234", 180000) {
            requested += it
            if (it == "jyeffect") throw NeteaseRequestRejected(400)
            response("hires")
        }.level)
        assertEquals(listOf("jyeffect", "hires"), requested)
    }

    @Test fun aRejectedOrIncompleteClientRouteUsesTheSameWebSessionBeforeReducingQuality() {
        for (kind in listOf("login", "forbidden", "empty", "trial", "network")) {
            var clientCalls = 0; val webCalls = mutableListOf<String>()
            val audio = authorizedNeteaseAudio("1234", 180000, {
                clientCalls++
                when (kind) {
                    "login" -> throw NeteaseLoginExpired()
                    "forbidden" -> throw NeteaseRequestRejected(403)
                    "network" -> throw java.io.IOException("client endpoint down")
                    "trial" -> response("jyeffect", trial = true)
                    else -> response("jyeffect").apply { getJSONArray("data").getJSONObject(0).put("url", JSONObject.NULL) }
                }
            }, { path, data ->
                webCalls += path
                assertEquals("jyeffect", data.getString("level"))
                response("hires")
            })
            assertEquals(1, clientCalls)
            assertEquals(listOf("song/enhance/player/url/v1"), webCalls)
            assertEquals("hires", audio.level)
        }
    }

    @Test fun realWebLoginExpiryAndInvalidSourcesStopWithoutAnotherRoute() {
        var webCalls = 0
        try {
            authorizedNeteaseAudio("1234", 180000, { throw NeteaseLoginExpired() }, { _, _ -> webCalls++; throw NeteaseLoginExpired() })
            fail("Expired session accepted")
        } catch (_: NeteaseLoginExpired) { }
        assertEquals(1, webCalls)
        for (field in listOf("id", "url")) {
            webCalls = 0
            try {
                authorizedNeteaseAudio("1234", 180000, {
                    response("jyeffect").apply { getJSONArray("data").getJSONObject(0).put(field,
                        if (field == "id") "9999" else "https://foreign.test/private") }
                }, { _, _ -> webCalls++; response("hires") })
                fail("Invalid source hidden by fallback")
            } catch (_: IllegalArgumentException) { }
            assertEquals(0, webCalls)
        }
    }

    @Test fun qualityRefusalsReachTheAuthorizedLegacySourceWithinSixRequests() {
        var clientCalls = 0; val webCalls = mutableListOf<String>()
        val audio = authorizedNeteaseAudio("1234", 180000, {
            clientCalls++; throw NeteaseRequestRejected(403)
        }, { path, data ->
            webCalls += path
            if (path.endsWith("/v1")) throw NeteaseRequestRejected(403)
            assertEquals(320000, data.getInt("br")); response("exhigh", "mp3")
        })
        assertEquals("exhigh", audio.level)
        assertEquals(1, clientCalls)
        assertEquals(5, webCalls.size)
        assertEquals("song/enhance/player/url", webCalls.last())
        // A busy/limited endpoint must not be hammered once per quality or by legacy requests.
        webCalls.clear()
        try {
            authorizedNeteaseAudio("1234", 180000, { throw NeteaseRequestRejected(429) }, { path, _ ->
                webCalls += path; throw NeteaseRequestRejected(429)
            })
            fail("Rate limiting hidden")
        } catch (failure: NeteaseRequestRejected) { assertEquals(429, failure.code) }
        assertEquals(1, webCalls.size)
    }

    @Test fun fallbackNeverAcceptsATrialAndRememberedLoginFailureStillWinsOverAUsableCandidate() {
        var calls = 0
        try {
            authorizedNeteaseAudio("1234", 180000, { response("jyeffect", trial = true) }, { _, data ->
                calls++; response(data.optString("level", "exhigh"), trial = true)
            })
            fail("Trial played")
        } catch (failure: NeteaseAudioUnavailable) { assertTrue(failure.trial) }
        assertEquals(5, calls)
        calls = 0
        try {
            bestNeteaseAudio("1234", 180000) {
                if (++calls == 1) response("exhigh", "mp3") else throw NeteaseLoginExpired()
            }
            fail("Expired login hidden by an earlier source")
        } catch (_: NeteaseLoginExpired) { }
        assertEquals(2, calls)
    }
}
