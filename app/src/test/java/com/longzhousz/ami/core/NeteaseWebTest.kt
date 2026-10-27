package com.longzhousz.ami.core

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class NeteaseWebTest {
    @Test fun webEnvelopeMatchesIndependentKnownVectorAndUsesFreshKeys() {
        val form = NeteaseForm.encode("{\"test\":1}", "abcdefghijklmnop")
        val hash = MessageDigest.getInstance("SHA-256").digest(form.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals("5f22aff104e462ad0630bbee1e073d4410386f03e2dba2126e888d4367e6f291", hash)
        assertNotEquals(NeteaseForm.encode("{}"), NeteaseForm.encode("{}"))
    }
    @Test fun loginCookieAndNavigationBoundariesRejectExternalData() {
        assertEquals("MUSIC_U=fake-session; __csrf=csrf", neteaseCookies("ignored=private; MUSIC_U=fake-session; __csrf=csrf"))
        listOf("", "MUSIC_A=anonymous", "MUSIC_U=bad\r\nX-Test: injected", "MUSIC_U=" + "x".repeat(17000)).forEach {
            try { neteaseCookies(it); fail("Invalid login accepted") } catch (_: IllegalArgumentException) { }
        }
        assertTrue(neteaseLoginUrl("https://music.163.com/m/login"))
        assertTrue(neteaseLoginUrl("https://dl.reg.163.com/"))
        listOf("http://music.163.com/", "https://music.163.com.attacker.test/", "https://user@music.163.com/", "file:///sdcard/login.html", "https://music.163.com:8443/").forEach { assertFalse(it, neteaseLoginUrl(it)) }
        listOf("0", "-1", "1/../../user", "123?x=y").forEach { try { neteaseId(it); fail("Invalid id") } catch (_: IllegalArgumentException) { } }
    }
}
