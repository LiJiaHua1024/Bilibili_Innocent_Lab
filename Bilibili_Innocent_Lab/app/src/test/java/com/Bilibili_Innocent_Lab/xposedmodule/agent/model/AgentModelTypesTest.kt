package com.Bilibili_Innocent_Lab.xposedmodule.agent.model

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentModelTypesTest {
    @Test fun `source normalization preserves query and rejects credentials cleartext or fragments`() {
        assertEquals("https://example.test/v1/chat/completions", AgentModelSource(8, "https://example.test", "k", "m").resolvedEndpoint)
        assertEquals("https://example.test/custom/chat/completions?api-version=1", AgentModelSource(1,
            "https://example.test/custom?api-version=1", "k", "m").resolvedEndpoint)
        assertEquals("https://example.test/v1/chat/completions?api-version=1", AgentModelSource(1,
            "https://example.test/v1/chat/completions?api-version=1", "k", "m").resolvedEndpoint)
        for (endpoint in listOf("http://example.test", "https://user:secret@example.test", "https://example.test/#fragment", "file:///secret", "https://example.test:0")) {
            assertNull(endpoint, AgentModelSource.from(1, endpoint, "key", "model"))
        }
        assertNull(AgentModelSource.from(9, "https://example.test", "key", "model"))
        assertNull(AgentModelSource.from(1, "https://example.test", "key\r\nInjected: value", "model"))
    }

    @Test fun `credential changes invalidate proof without exposing key in diagnostic strings`() {
        val source = AgentModelSource(1, "https://example.test/v1", "private-key", "private-model")
        assertNotEquals(source.fingerprint, source.copy(apiKey = "other-key").fingerprint)
        assertNotEquals(source.fingerprint, source.copy(model = "other-model").fingerprint)
        assertNotEquals(source.fingerprint, source.copy(endpoint = "https://other.test").fingerprint)
        assertEquals(source.fingerprint, source.copy(index = 8).fingerprint)
        assertEquals(64, source.fingerprint.length)
        assertFalse(source.toString().contains("private"))
        assertFalse(source.fingerprint.contains("private-key"))
    }

    @Test fun `capability persistence preserves unknown unsupported and freshness`() {
        val original = AgentModelCapabilities(false, true, 1000, "checked", AgentCapabilityState.UNSUPPORTED, AgentCapabilityState.SUPPORTED)
        assertEquals(original, AgentModelCapabilities.fromJson(original.toJson()))
        assertFalse(original.fresh(999))
        assertTrue(original.fresh(1000))
        assertFalse(original.fresh(1001 + AgentModelCapabilities.VALID_FOR_MS))
        assertNull(AgentModelCapabilities.fromJson(original.toJson().put("tools", true)))
        assertNull(AgentModelCapabilities.fromJson(JSONObject()))
    }

    @Test fun `HTTP failure classification is structural and does not disclose response contents`() {
        val body = "{\"error\":{\"message\":\"private-key image unsupported\"}}"
        val failure = AgentHttpsTransport.httpFailure(401, body, null)
        assertEquals(AgentModelException.Reason.HTTP, failure.reason)
        assertFalse(failure.toString().contains("private-key"))
        val unsupported = "{\"error\":{\"code\":\"unsupported_feature\",\"param\":\"image_url\"}}"
        assertEquals(AgentModelException.Reason.VISION_UNSUPPORTED, AgentHttpsTransport.httpFailure(400, unsupported, null).reason)
        assertEquals(AgentModelException.Reason.HTTP, AgentHttpsTransport.httpFailure(500, unsupported, null).reason)
        val tokens = "{\"error\":{\"code\":\"unsupported_parameter\",\"param\":\"max_tokens\"}}"
        assertEquals(AgentModelException.Reason.TOKEN_PARAMETER, AgentHttpsTransport.httpFailure(400, tokens, null).reason)
    }
}
