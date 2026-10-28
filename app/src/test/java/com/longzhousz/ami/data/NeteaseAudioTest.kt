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
}
