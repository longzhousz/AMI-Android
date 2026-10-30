package com.longzhousz.ami.data

import android.content.Intent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longzhousz.ami.BuildConfig
import com.longzhousz.ami.core.*
import com.longzhousz.ami.ui.AmiTheme
import com.longzhousz.ami.ui.UpdatePrompt
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class AppUpdatesTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val actual = requireNotNull(AppVersion.parse(BuildConfig.VERSION_NAME))
    private val older = AppVersion(0, 0, 0)
    private lateinit var folder: File
    private lateinit var apk: File
    private lateinit var digest: String
    private var apkRequests = 0
    private var truncated = false
    private var wrongDigest = false
    private var unavailable = false
    private var offeredVersion = actual
    @Before fun prepare() {
        folder = File(context.filesDir, "updates/qa-${System.nanoTime()}").apply { mkdirs() }
        apk = File(context.applicationInfo.sourceDir)
        val sha = MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { input -> val buffer = ByteArray(65536); while (true) { val n = input.read(buffer); if (n < 0) break; sha.update(buffer, 0, n) } }
        digest = sha.digest().joinToString("") { "%02x".format(it) }
    }
    @After fun cleanup() { folder.deleteRecursively() }
    private fun updater() = AppUpdates(context, older.toString(), BuildConfig.VERSION_CODE.toLong() - 1, ::open, folder)
    private fun open(address: String) = object : HttpURLConnection(URL(address)) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = if (unavailable) 503 else 200
        override fun getInputStream(): java.io.InputStream {
            if (address == UPDATE_API) {
                val name = "AMI-Android-$offeredVersion-online-preview.apk"
                val json = JSONObject().put("tag_name", "android-v$offeredVersion-preview").put("draft", false).put("prerelease", false)
                    .put("assets", JSONArray().put(JSONObject().put("name", name).put("size", apk.length())
                        .put("digest", "sha256:" + if (wrongDigest) "0".repeat(64) else digest)
                        .put("browser_download_url", "https://github.com/$UPDATE_REPO/releases/download/android-v$offeredVersion-preview/$name")))
                return ByteArrayInputStream(json.toString().toByteArray())
            }
            apkRequests++
            return if (!truncated) apk.inputStream() else object : FilterInputStream(apk.inputStream()) {
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int = throw IOException("Interrupted fixture download")
            }
        }
    }

    @Test fun aVerifiedInstallerIsReusedAcrossStartsAndSharedOnlyThroughARestrictedContentUri() = runBlocking {
        val updates = updater()
        updates.check(); updates.check()
        assertEquals(1, apkRequests)
        assertEquals(actual, updates.ready.value!!.version)
        assertEquals(BuildConfig.VERSION_CODE.toLong(), updates.ready.value!!.code)
        assertEquals(1, folder.listFiles()!!.count { it.extension == "apk" })
        unavailable = true
        val restarted = updater()
        runCatching { restarted.check() }
        assertNotNull("An already downloaded update must remain available offline", restarted.ready.value)
        val intent = restarted.installerIntent()!!
        assertEquals(Intent.ACTION_VIEW, intent.action); assertEquals("content", intent.data!!.scheme)
        assertEquals("${context.packageName}.updates", intent.data!!.authority)
        assertEquals("application/vnd.android.package-archive", intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertNotNull(context.contentResolver.openInputStream(intent.data!!)?.use { it.readNBytes(4) })
        assertTrue(runCatching { FileProvider.getUriForFile(context, "${context.packageName}.updates", File(context.filesDir, "not-an-update")) }.isFailure)
        unavailable = false
        val upgraded = AppUpdates(context, connect = ::open, directory = folder)
        upgraded.check()
        assertNull(upgraded.ready.value)
        assertFalse("Installed update APKs must be reclaimed", folder.listFiles()!!.any { it.extension == "apk" })
    }

    @Test fun interruptedCorruptAndMislabelledDownloadsNeverBecomeInstallable() = runBlocking {
        val updates = updater()
        truncated = true
        assertTrue(runCatching { updates.check() }.isFailure)
        assertNull(updates.ready.value); assertFalse(folder.listFiles()!!.any { it.extension in setOf("apk", "part") })
        truncated = false; wrongDigest = true
        assertTrue(runCatching { updates.check() }.isFailure)
        assertNull(updates.ready.value)
        wrongDigest = false; offeredVersion = actual.copy(patch = actual.patch + 1)
        assertTrue("A valid archive with another version must be rejected", runCatching { updates.check() }.isFailure)
        assertNull(updates.ready.value)
        offeredVersion = actual
        updates.check()
        val verified = updates.ready.value!!
        offeredVersion = actual.copy(patch = actual.patch + 1); wrongDigest = true
        assertTrue(runCatching { updates.check() }.isFailure)
        assertEquals(verified, updates.ready.value)
        assertTrue(verified.file.isFile)
    }

    @Test fun readyUpdatesWaitForSettingsAndOnlyExplicitInstallClicksInvokeTheInstaller() {
        var updates by mutableStateOf(updater())
        runBlocking { updates.check() }
        var active by mutableStateOf(false)
        var installs = 0
        compose.setContent { AmiTheme("light") { UpdatePrompt(updates, active) { installs++ } } }
        compose.onNodeWithText("发现新版本").assertDoesNotExist()
        compose.runOnIdle { active = true }
        compose.onNodeWithText("发现新版本").assertIsDisplayed()
        compose.onNodeWithText("稍后").performClick()
        compose.runOnIdle { active = false }; compose.runOnIdle { active = true }
        compose.onNodeWithText("发现新版本").assertDoesNotExist()
        assertEquals(0, installs)
        val nextSession = updater(); runBlocking { nextSession.check() }
        compose.runOnIdle { updates = nextSession }
        compose.onNodeWithText("发现新版本").assertIsDisplayed()
        compose.onNodeWithText("安装").performClick()
        compose.onNodeWithText("发现新版本").assertDoesNotExist()
        assertEquals(1, installs)
    }
}
