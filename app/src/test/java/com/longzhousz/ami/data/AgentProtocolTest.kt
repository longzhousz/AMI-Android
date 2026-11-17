package com.longzhousz.ami.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentProtocolTest {
    @Test fun requestIdAllowsOnlyPortableAscii() {
        assertTrue(AgentProtocol.requestId.matches("req_2026-A1"))
        assertFalse(AgentProtocol.requestId.matches("../escape"))
        assertFalse(AgentProtocol.requestId.matches(""))
        assertFalse(AgentProtocol.requestId.matches("a".repeat(81)))
    }

    @Test fun everyExecutableActionDeclaresPermissions() {
        AgentProtocol.permissions.keys.forEach { action ->
            assertNotNull("Missing permissions for $action", AgentProtocol.permissionsFor(action))
            assertTrue("Missing permission group for $action", AgentProtocol.permissionsFor(action)!!.all { it in AgentProtocol.permissionDefaults })
        }
        assertTrue(AgentProtocol.permissionsFor("queue.reorder")!!.containsAll(setOf("agentPlayback", "agentQueue", "agentReorder")))
        assertTrue(AgentProtocol.permissionsFor("like.set")!!.isEmpty())
    }

    @Test fun onlineLibraryAndTrackReadsRequireOnlinePermission() {
        assertTrue(AgentProtocol.isKnown("online.library.read"))
        assertTrue(AgentProtocol.isKnown("online.tracks.read"))
        assertEquals(setOf("agentOnline"), AgentProtocol.permissionsFor("online.library.read"))
        assertEquals(setOf("agentOnline"), AgentProtocol.permissionsFor("online.tracks.read"))
        assertFalse(AgentProtocol.requiresUi("online.library.read"))
        assertFalse(AgentProtocol.requiresUi("online.tracks.read"))
        assertNull(
            AgentProtocol.forbiddenPayloadField(
                JSONObject().put("id", "123456").put("kind", "album").put("offset", 0),
            ),
        )
    }

    @Test fun uiOnlyAndConfirmationActionsAreDistinct() {
        AgentProtocol.uiOnlyActions.forEach { action ->
            assertFalse("UI-only action must not be executable", action in AgentProtocol.permissions)
            assertTrue(AgentProtocol.isKnown(action))
            assertTrue(AgentProtocol.requiresUi(action))
            assertFalse("UI-only action must not require confirmation", action in AgentProtocol.confirmationActions)
        }
        AgentProtocol.confirmationActions.forEach { action ->
            assertTrue("Confirmation action must be executable", action in AgentProtocol.permissions)
            assertFalse(AgentProtocol.requiresUi(action))
        }
        assertTrue(AgentProtocol.isTerminal(AgentProtocol.STATUS_CANCELLED))
        assertFalse(AgentProtocol.isTerminal(AgentProtocol.STATUS_REQUIRES_CONFIRMATION))
    }

    @Test fun forbiddenPayloadRejectsCredentialsPathsAndNestedFields() {
        assertEquals("uri", AgentProtocol.forbiddenPayloadField(JSONObject().put("uri", "content://anything")))
        assertEquals("apiKey", AgentProtocol.forbiddenPayloadField(JSONObject().put("apiKey", "sk-test")))
        assertEquals("Token", AgentProtocol.forbiddenPayloadField(JSONObject().put("Token", "secret")))
        assertEquals("COOKIE", AgentProtocol.forbiddenPayloadField(JSONObject().put("COOKIE", "secret")))
        assertEquals("lyrics", AgentProtocol.forbiddenPayloadField(JSONObject().put("lyrics", "secret")))
        assertEquals("path", AgentProtocol.forbiddenPayloadField(JSONObject().put("path", "/sdcard/music")))
        assertEquals("coverUri", AgentProtocol.forbiddenPayloadField(JSONObject().put("coverUri", "https://example.test")))
        val nested = JSONObject().put("tracks", JSONArray().put(JSONArray().put(JSONObject().put("path", "Music/song.mp3"))))
        assertEquals("path", AgentProtocol.forbiddenPayloadField(nested))
        assertNull(AgentProtocol.forbiddenPayloadField(JSONObject().put("query", "周杰伦")))
        assertNull(AgentProtocol.forbiddenPayloadField(JSONObject().put("style", "每行一条要求")))
    }
}
