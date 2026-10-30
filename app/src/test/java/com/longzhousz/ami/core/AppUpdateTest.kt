package com.longzhousz.ami.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppUpdateTest {
    private val current = AppVersion(0, 2, 3)
    private fun release(version: String = "0.2.4"): JSONObject {
        val tag = "android-v$version-preview"
        return JSONObject().put("tag_name", tag).put("draft", false).put("prerelease", false).put("assets", JSONArray().put(
            JSONObject().put("name", "AMI-Android-$version-online-preview.apk").put("size", 5000000)
                .put("digest", "sha256:" + "a".repeat(64))
                .put("browser_download_url", "https://github.com/$UPDATE_REPO/releases/download/$tag/AMI-Android-$version-online-preview.apk")))
    }
    @Test fun versionOrderingIsNumericAndBuildSuffixesDoNotCreateUpdates() {
        assertEquals(current, AppVersion.parse("0.2.3-music-preview"))
        assertTrue(AppVersion(0, 2, 10) > AppVersion(0, 2, 9))
        assertTrue(AppVersion(1, 0, 0) > AppVersion(0, 99, 99))
        listOf("0.2", "-1.2.3", "2147483648.0.0", "0.2.4/other").forEach { assertNull(AppVersion.parse(it)) }
    }
    @Test fun onlyANewerPublishedCompatibleAssetIsSelected() {
        assertEquals(AppVersion(0, 2, 4), AppUpdate.fromRelease(release(), current)!!.version)
        assertNull(AppUpdate.fromRelease(release("0.2.3"), current))
        assertNull(AppUpdate.fromRelease(release("0.2.2"), current))
        assertNull(AppUpdate.fromRelease(release().put("draft", true), current))
        assertNull(AppUpdate.fromRelease(release().put("prerelease", true), current))
        val duplicate = release(); duplicate.getJSONArray("assets").put(duplicate.getJSONArray("assets").getJSONObject(0))
        assertNull(AppUpdate.fromRelease(duplicate, current))
    }
    @Test fun foreignOriginsOversizedArchivesAndMissingIntegrityAreRejected() {
        for ((field, value) in listOf("browser_download_url" to "https://github.com/other/project/app.apk", "size" to MAX_UPDATE_BYTES + 1,
            "size" to 0, "digest" to "sha1:" + "a".repeat(40), "name" to "AMI-Android-0.2.4-phone-preview.apk")) {
            val json = release(); json.getJSONArray("assets").getJSONObject(0).put(field, value)
            assertNull("$field=$value", AppUpdate.fromRelease(json, current))
        }
    }
    @Test fun publishedChecksumManifestCanSupplyAnAbsentAssetDigest() {
        val json = release(); json.getJSONArray("assets").getJSONObject(0).remove("digest")
        assertNull(AppUpdate.fromRelease(json, current))
        json.getJSONArray("assets").put(JSONObject().put("name", "AMI-0.2.4-SHA256SUMS.txt")
            .put("browser_download_url", "https://github.com/$UPDATE_REPO/releases/download/android-v0.2.4-preview/AMI-0.2.4-SHA256SUMS.txt"))
        assertNotNull(AppUpdate.fromRelease(json, current)?.checksumsUrl)
    }
    @Test fun redirectsRequireOfficialHttpsOriginsWithoutCredentials() {
        assertTrue(trustedUpdateUrl("https://release-assets.githubusercontent.com/object?signature=public-download"))
        for (url in listOf("http://github.com/a", "https://github.com.evil.test/a", "https://user@github.com/a", "https://github.com:8080/a",
            "file:///data/app/update.apk", "https://example.test/app.apk", "https://github.com/a#fragment")) assertFalse(url, trustedUpdateUrl(url))
    }
}
