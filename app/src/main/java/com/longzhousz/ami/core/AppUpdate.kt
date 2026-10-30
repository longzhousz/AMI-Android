package com.longzhousz.ami.core

import org.json.JSONObject
import java.net.URI

internal data class AppVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<AppVersion> {
    override fun compareTo(other: AppVersion) = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
    override fun toString() = "$major.$minor.$patch"
    companion object {
        fun parse(text: String): AppVersion? {
            val parts = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)(?:-[A-Za-z0-9.-]+)?$").matchEntire(text)?.groupValues?.drop(1) ?: return null
            val values = parts.map { it.toIntOrNull() ?: return null }
            return AppVersion(values[0], values[1], values[2])
        }
    }
}

internal const val UPDATE_REPO = "longzhousz/AMI-Android"
internal const val UPDATE_API = "https://api.github.com/repos/$UPDATE_REPO/releases/latest"
internal const val MAX_UPDATE_BYTES = 128L * 1024 * 1024
internal val UpdateDigest = Regex("[a-f0-9]{64}")

internal data class AppUpdate(val version: AppVersion, val url: String, val bytes: Long, val digest: String?, val checksumsUrl: String?) {
    val filename get() = "AMI-Android-$version-online-preview.apk"
    companion object {
        fun fromRelease(json: JSONObject, current: AppVersion): AppUpdate? {
            if (json.optBoolean("draft", true) || json.optBoolean("prerelease", true)) return null
            val tag = json.optString("tag_name")
            val text = Regex("android-v(\\d+\\.\\d+\\.\\d+)-preview").matchEntire(tag)?.groupValues?.get(1) ?: return null
            val version = AppVersion.parse(text)?.takeIf { it > current } ?: return null
            val assets = json.optJSONArray("assets") ?: return null
            val rows = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
            val name = "AMI-Android-$version-online-preview.apk"
            val apk = rows.singleOrNull { it.optString("name") == name } ?: return null
            val prefix = "https://github.com/$UPDATE_REPO/releases/download/$tag/"
            if (apk.optString("browser_download_url") != prefix + name || apk.optLong("size") !in 1..MAX_UPDATE_BYTES) return null
            val digest = apk.optString("digest").removePrefix("sha256:").takeIf { UpdateDigest.matches(it) }
            val checksums = rows.singleOrNull { it.optString("name") == "AMI-$version-SHA256SUMS.txt" }
                ?.optString("browser_download_url")?.takeIf { it == prefix + "AMI-$version-SHA256SUMS.txt" }
            if (digest == null && checksums == null) return null
            return AppUpdate(version, prefix + name, apk.optLong("size"), digest, checksums)
        }
    }
}

/** GitHub redirects release assets to its own HTTPS object hosts. Never follow arbitrary origins. */
internal fun trustedUpdateUrl(text: String): Boolean = runCatching {
    val uri = URI(text)
    uri.scheme == "https" && uri.rawUserInfo == null && uri.port in listOf(-1, 443) && uri.rawFragment == null &&
        uri.host in setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com")
}.getOrDefault(false)
