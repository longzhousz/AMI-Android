package com.longzhousz.ami.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.AtomicFile
import androidx.core.content.FileProvider
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.longzhousz.ami.AmiApplication
import com.longzhousz.ami.BuildConfig
import com.longzhousz.ami.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

internal data class ReadyUpdate(val version: AppVersion, val code: Long, val file: File, val digest: String)

/** A foreground-entry check and one OS-managed job; no timer, progress UI, or GitHub credentials. */
internal class AppUpdates(
    private val context: Context,
    currentName: String = BuildConfig.VERSION_NAME,
    private val currentCode: Long = BuildConfig.VERSION_CODE.toLong(),
    private val connect: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection },
    private val directory: File = File(context.filesDir, "updates"),
) {
    private val current = requireNotNull(AppVersion.parse(currentName))
    private val pointer = AtomicFile(File(directory, "pending"))
    private val pending = MutableStateFlow<ReadyUpdate?>(null)
    val ready = pending.asStateFlow()
    private var lastStart = Long.MIN_VALUE
    private var offeredCode: Long? = null
    private var etag: String? = null
    private var lastRelease: AppUpdate? = null

    fun onStart() {
        if (BuildConfig.BUILD_TYPE != "musicPreview") return
        val now = SystemClock.elapsedRealtime()
        // Returning from Android's permission/installer screen is not another launch check.
        if (lastStart != Long.MIN_VALUE && now - lastStart < 15000) return
        lastStart = now
        WorkManager.getInstance(context).enqueueUniqueWork("app-update", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AppUpdateWorker>().build())
    }

    fun claimPrompt(code: Long): Boolean {
        if (offeredCode == code) return false
        offeredCode = code
        return true
    }

    private fun hash(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun verified(file: File, digest: String, expected: AppVersion? = null): ReadyUpdate {
        check(file.length() in 1..MAX_UPDATE_BYTES && hash(file) == digest)
        val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        val info = context.packageManager.getPackageArchiveInfo(file.path, flags) ?: error("Invalid update archive")
        val version = requireNotNull(AppVersion.parse(info.versionName.orEmpty()))
        check(info.packageName == context.packageName && info.longVersionCode > currentCode && version > current)
        check(expected == null || expected == version)
        check((info.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE) <= Build.VERSION.SDK_INT)
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val signers = info.signingInfo?.apkContentsSigners.orEmpty().toSet()
        check(signers.isNotEmpty() && signers == installed.signingInfo?.apkContentsSigners.orEmpty().toSet())
        return ReadyUpdate(version, info.longVersionCode, file, digest)
    }

    private fun clean(keep: Set<File>) {
        directory.listFiles()?.filter { it.isFile && it !in keep && (it.extension == "apk" || it.extension == "part") }?.forEach { it.delete() }
    }

    private fun saved(): ReadyUpdate? {
        val digest = runCatching { synchronized(pointer) { pointer.openRead().bufferedReader().use { it.readText().trim() } } }.getOrNull()
        val value = digest?.takeIf(UpdateDigest::matches)?.let { runCatching { verified(File(directory, "$it.apk"), it) }.getOrNull() }
        if (value == null) { synchronized(pointer) { pointer.delete() }; clean(emptySet()) }
        return value
    }

    suspend fun installerIntent(): Intent? = withContext(Dispatchers.IO) {
        saved()?.let { update ->
            Intent(Intent.ACTION_VIEW).setDataAndType(FileProvider.getUriForFile(context, "${context.packageName}.updates", update.file),
                "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun response(address: String, match: String? = null): HttpURLConnection {
        var url = address
        repeat(6) {
            check(trustedUpdateUrl(url))
            val connection = connect(url).apply {
                connectTimeout = 10000; readTimeout = 15000; instanceFollowRedirects = false
                setRequestProperty("User-Agent", "AMI/${BuildConfig.VERSION_NAME}")
                setRequestProperty("Accept", "application/vnd.github+json")
                if (match != null) setRequestProperty("If-None-Match", match)
            }
            try {
                if (connection.responseCode !in listOf(301, 302, 303, 307, 308)) return connection
                url = URI(url).resolve(requireNotNull(connection.getHeaderField("Location"))).toString()
            } catch (error: Exception) { connection.disconnect(); throw error }
            connection.disconnect()
        }
        error("Too many update redirects")
    }

    suspend fun check() = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val previous = saved()
        clean(setOfNotNull(previous?.file))
        pending.value = previous
        val metadata = response(UPDATE_API, etag)
        val release = try {
            when (metadata.responseCode) {
                304 -> lastRelease
                200 -> {
                    val text = metadata.inputStream.use { it.readNBytes(512 * 1024 + 1) }
                    check(text.size <= 512 * 1024)
                    AppUpdate.fromRelease(JSONObject(text.toString(Charsets.UTF_8)), current).also {
                        lastRelease = it; etag = metadata.getHeaderField("ETag")
                    }
                }
                else -> error("Update metadata unavailable")
            }
        } finally { metadata.disconnect() }
        if (release == null) {
            pending.value = null
            synchronized(pointer) { pointer.delete() }
            clean(emptySet())
            return@withContext
        }
        val digest = release.digest ?: response(requireNotNull(release.checksumsUrl)).let { connection ->
            try {
                check(connection.responseCode == 200)
                val bytes = connection.inputStream.use { it.readNBytes(16385) }; check(bytes.size <= 16384)
                bytes.toString(Charsets.UTF_8).lineSequence().map { it.trim().split(Regex("\\s+"), limit = 2) }
                    .single { it.size == 2 && it[1].removePrefix("*") == release.filename }[0].also { check(UpdateDigest.matches(it)) }
            } finally { connection.disconnect() }
        }
        if (previous?.digest == digest) return@withContext
        check(directory.usableSpace >= release.bytes + 8 * 1024 * 1024)
        val temporary = File(directory, "download.part.apk")
        val connection = response(release.url)
        try {
            check(connection.responseCode == 200)
            connection.inputStream.use { input -> temporary.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024); var copied = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer); if (count < 0) break
                    copied += count; check(copied <= release.bytes); output.write(buffer, 0, count)
                }
                check(copied == release.bytes)
            } }
            val candidate = verified(temporary, digest, release.version)
            val apk = File(directory, "$digest.apk")
            currentCoroutineContext().ensureActive()
            check(temporary.renameTo(apk))
            synchronized(pointer) {
                val output = pointer.startWrite()
                try { output.write(digest.toByteArray()); pointer.finishWrite(output) }
                catch (error: Exception) { pointer.failWrite(output); throw error }
            }
            pending.value = candidate.copy(file = apk)
            // Retain the previous immutable file until the next check in case Android is opening it.
            clean(setOfNotNull(apk, previous?.file))
        } finally { connection.disconnect(); temporary.delete() }
    }
}

class AppUpdateWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        try { (applicationContext as AmiApplication).updates.check() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { android.util.Log.i("AmiUpdate", "check_unavailable") }
        return Result.success()
    }
}
