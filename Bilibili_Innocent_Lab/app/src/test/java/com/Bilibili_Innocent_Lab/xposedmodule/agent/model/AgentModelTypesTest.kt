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

    @Test fun `decisions endpoints use the actual protocol and never become chat paths`() {
        val native = AgentModelSource(1, "https://api.typesafe.ai/v1", "key", "jev-latest", AgentSourceProtocol.DECISIONS)
        assertEquals("https://api.typesafe.ai/v1/systemone", native.resolvedEndpoint)
        val router = native.copy(endpoint = "https://openrouter.ai/api/v1")
        assertEquals("https://openrouter.ai/api/alpha/decisions", router.resolvedEndpoint)
        assertEquals("https://proxy.example/v1/decision?version=2", native.copy(endpoint =
            "https://proxy.example/v1/decision?version=2").resolvedEndpoint)
        assertNotEquals(native.fingerprint, native.copy(protocol = AgentSourceProtocol.CHAT).fingerprint)
        for (endpoint in listOf("http://proxy.example/v1", "https://user:secret@proxy.example/v1/decision",
            "https://proxy.example/v1/decision#fragment")) {
            assertNull(AgentModelSource.from(1, endpoint, "key", "jev", AgentSourceProtocol.DECISIONS))
        }
    }

    @Test fun `old capability documents remain readable while new decision evidence expires after one day`() {
        val old = AgentModelCapabilities(true, false, 1000, "checked").toJson()
        old.remove("decisions"); old.remove("decisionState"); old.remove("decisionFormats")
        assertFalse(AgentModelCapabilities.fromJson(old)!!.decisions)
        val current = AgentModelCapabilities(false, true, 1000, "checked", decisions = true,
            decisionFormats = setOf("choice", "noul"))
        assertEquals(current, AgentModelCapabilities.fromJson(current.toJson()))
        assertTrue(current.fresh(1000 + 24 * 60 * 60_000L))
        assertFalse(current.fresh(1001 + 24 * 60 * 60_000L))
        assertNull(AgentModelCapabilities.fromJson(current.toJson().put("decisionFormats", org.json.JSONArray().put("arbitrary"))))
    }
}
