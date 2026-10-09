package com.Bilibili_Innocent_Lab.xposedmodule.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentCapabilityProbeTest {
    private val source = AgentModelSource(1, "https://example.test", "test-key", "model-name-is-not-evidence")
    private val challenge = VisionChallenge("data:image/png;base64,YWJj", "A7c9E2")

    private fun response(content: String, calls: JSONArray? = null): String = JSONObject().put("choices", JSONArray()
        .put(JSONObject().put("finish_reason", if (calls == null) "stop" else "tool_calls")
            .put("message", JSONObject().put("role", "assistant").put("content", content)
                .also { if (calls != null) it.put("tool_calls", calls) }))).toString()

    private fun echo(body: JSONObject, receiptCorrect: Boolean = true): String {
        val messages = body.getJSONArray("messages")
        if (messages.length() == 1) {
            val prompt = messages.getJSONObject(0).getString("content")
            val nonce = Regex("nonce '([^']+)'").find(prompt)!!.groupValues[1]
            val calls = JSONArray().put(JSONObject().put("id", "echo_1").put("type", "function")
                .put("function", JSONObject().put("name", "capability_echo")
                    .put("arguments", JSONObject().put("nonce", nonce).toString())))
            return response("", calls)
        }
        assertEquals("assistant", messages.getJSONObject(1).getString("role"))
        assertEquals("echo_1", messages.getJSONObject(2).getString("tool_call_id"))
        val receipt = JSONObject(messages.getJSONObject(2).getString("content")).getString("receipt")
        assertFalse(messages.getJSONObject(0).getString("content").contains(receipt))
        return response(if (receiptCorrect) receipt else "fabricated")
    }

    @Test fun `tool support requires real echo result round trip and vision requires image answer`() {
        val requests = mutableListOf<JSONObject>()
        val client = AgentModelClient(AgentHttpTransport { _, bytes, _, _ ->
            val body = JSONObject(String(bytes, Charsets.UTF_8)); requests += body
            if (body.has("tools")) echo(body) else {
                val content = body.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
                assertFalse(content.getJSONObject(0).getString("text").contains(challenge.expectedAnswer))
                assertEquals(challenge.dataUrl, content.getJSONObject(1).getJSONObject("image_url").getString("url"))
                response(challenge.expectedAnswer)
            }
        }, clock = { 20_000L })
        val result = AgentCapabilityProbe(client, clock = { 20_000L }).probe(source, challenge)
        assertTrue(result.tools)
        assertTrue(result.vision)
        assertEquals(AgentCapabilityState.SUPPORTED, result.toolState)
        assertEquals(3, requests.size)
        assertFalse(result.detail.contains("test-key"))
        assertFalse(challenge.toString().contains(challenge.dataUrl))
        // 探测结果已登记；同一凭据的普通视觉调用不再触发未验证拒绝。
        client.generate(source, requests.last().getJSONArray("messages"), JSONArray(), true, 1000) { false }
    }

    @Test fun `tool-looking text and incorrect visual answer remain unknown`() {
        val client = AgentModelClient(AgentHttpTransport { _, _, _, _ -> response("capability_echo(nonce)") })
        val result = AgentCapabilityProbe(client).probe(source, challenge)
        assertFalse(result.tools)
        assertFalse(result.vision)
        assertEquals(AgentCapabilityState.UNKNOWN, result.toolState)
        assertEquals(AgentCapabilityState.UNKNOWN, result.visionState)
    }

    @Test fun `one tool invocation alone does not prove full tool cycle`() {
        val client = AgentModelClient(AgentHttpTransport { _, bytes, _, _ -> echo(JSONObject(String(bytes, Charsets.UTF_8)), false) })
        val result = AgentCapabilityProbe(client).probe(source)
        assertEquals(AgentCapabilityState.UNKNOWN, result.toolState)
        assertEquals(AgentCapabilityState.UNKNOWN, result.visionState)
    }

    @Test fun `authentication network and timeouts cannot be mislabeled unsupported`() {
        for ((reason, status) in listOf(AgentModelException.Reason.HTTP to 401,
            AgentModelException.Reason.NETWORK to null, AgentModelException.Reason.TIMEOUT to null)) {
            var requests = 0
            val client = AgentModelClient(AgentHttpTransport { _, _, _, _ -> requests++; throw AgentModelException(reason, status) })
            val result = AgentCapabilityProbe(client).probe(source, challenge)
            assertEquals(AgentCapabilityState.UNKNOWN, result.toolState)
            assertEquals(AgentCapabilityState.UNKNOWN, result.visionState)
            assertEquals(1, requests)
        }
    }

    @Test fun `generic tool rejection does not skip a potentially valid visual challenge`() {
        // 403 也可能只是工具功能的独立权限限制，不能据此放弃视觉来源。
        for (status in listOf(400, 403)) {
            var requests = 0
            val client = AgentModelClient(AgentHttpTransport { _, bytes, _, _ ->
                requests++
                if (JSONObject(String(bytes, Charsets.UTF_8)).has("tools")) throw AgentModelException(AgentModelException.Reason.HTTP, status)
                response(challenge.expectedAnswer)
            })
            val result = AgentCapabilityProbe(client).probe(source, challenge)
            assertEquals(AgentCapabilityState.UNKNOWN, result.toolState)
            assertTrue(result.vision)
            assertEquals(2, requests)
        }
    }

    @Test fun `receipt authentication failure stops image request without accepting partial tool proof`() {
        var requests = 0
        val client = AgentModelClient(AgentHttpTransport { _, bytes, _, _ ->
            requests++
            if (requests == 1) echo(JSONObject(String(bytes, Charsets.UTF_8)))
            else throw AgentModelException(AgentModelException.Reason.HTTP, 401)
        })
        val result = AgentCapabilityProbe(client).probe(source, challenge)
        assertEquals(AgentCapabilityState.UNKNOWN, result.toolState)
        assertEquals(AgentCapabilityState.UNKNOWN, result.visionState)
        assertEquals(2, requests)
    }

    @Test fun `cancellation during a common failure still propagates and publishes no proof`() {
        var cancelled = false
        var requests = 0
        val client = AgentModelClient(AgentHttpTransport { _, _, _, _ ->
            requests++
            cancelled = true
            throw AgentModelException(AgentModelException.Reason.NETWORK)
        })
        try { AgentCapabilityProbe(client).probe(source, challenge, cancelled = { cancelled }); fail("cancel expected") }
        catch (error: AgentModelException) { assertEquals(AgentModelException.Reason.CANCELLED, error.reason) }
        assertEquals(1, requests)
    }

    @Test fun `explicit unsupported is distinct from inconclusive`() {
        val client = AgentModelClient(AgentHttpTransport { _, bytes, _, _ ->
            if (JSONObject(String(bytes, Charsets.UTF_8)).has("tools")) throw AgentModelException(AgentModelException.Reason.TOOLS_UNSUPPORTED)
            response(challenge.expectedAnswer)
        })
        val result = AgentCapabilityProbe(client).probe(source, challenge)
        assertEquals(AgentCapabilityState.UNSUPPORTED, result.toolState)
        assertTrue(result.vision)
    }

    @Test fun `cancelled capability probe propagates cancellation`() {
        val client = AgentModelClient(AgentHttpTransport { _, _, _, _ -> fail("must not call"); response("") })
        try { AgentCapabilityProbe(client).probe(source, challenge, cancelled = { true }); fail("cancel expected") }
        catch (e: AgentModelException) { assertEquals(AgentModelException.Reason.CANCELLED, e.reason) }
    }
}
