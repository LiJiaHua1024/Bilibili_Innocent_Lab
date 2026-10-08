package com.Bilibili_Innocent_Lab.xposedmodule.agent

import com.Bilibili_Innocent_Lab.xposedmodule.agent.model.AgentModelToolCall
import com.Bilibili_Innocent_Lab.xposedmodule.agent.model.AgentModelTurn
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentConversationTest {
    private fun turn(index: Int, reasoning: String = ""): AgentModelTurn {
        val arguments = JSONObject().put("query", "悟空 $index")
        val calls = JSONArray().put(JSONObject().put("id", "call_$index").put("type", "function")
            .put("function", JSONObject().put("name", "search_videos").put("arguments", arguments.toString())))
        val message = JSONObject().put("role", "assistant").put("content", JSONObject.NULL).put("tool_calls", calls)
        if (reasoning.isNotEmpty()) message.put("reasoning_content", reasoning)
        return AgentModelTurn(message, listOf(AgentModelToolCall("call_$index", "search_videos", arguments)), "")
    }

    private fun result(index: Int) = JSONObject().put("ok", true).put("data", JSONObject()
        .put("videos", JSONArray().put(JSONObject().put("video_id", "av$index").put("title", "demo $index")
            .put("official_source", "unknown"))).put("observed_at_elapsed", index))

    @Test fun `hundreds of rounds retain goal and complete tool pairs with bounded history`() {
        val conversation = AgentConversation("system rules", "find official demonstrations")
        repeat(400) { conversation.append(turn(it + 1), result(it + 1), "source1") }
        val messages = conversation.forSource("source1")
        assertEquals("system rules", messages.getJSONObject(0).getString("content"))
        assertEquals("find official demonstrations", messages.getJSONObject(1).getString("content"))
        assertTrue(messages.length() <= 3 + AgentConversation.MAX_ROUNDS * 2)
        assertTrue(messages.toString().length <= AgentConversation.MAX_CONTEXT_CHARS)
        val history = messages.getJSONObject(2).getString("content")
        assertTrue(history.contains("历史数据"))
        assertTrue(history.contains("unknown"))
        assertTrue(history.contains("observed_at_elapsed"))
        for (index in 3 until messages.length() step 2) {
            val assistant = messages.getJSONObject(index)
            val tool = messages.getJSONObject(index + 1)
            assertEquals("assistant", assistant.getString("role"))
            assertEquals("tool", tool.getString("role"))
            assertEquals(assistant.getJSONArray("tool_calls").getJSONObject(0).getString("id"), tool.getString("tool_call_id"))
        }
    }

    @Test fun `source specific reasoning is not forwarded to another model`() {
        val conversation = AgentConversation("system", "goal")
        conversation.append(turn(1, "provider private reasoning"), result(1), "source1")
        assertTrue(conversation.forSource("source1").getJSONObject(2).has("reasoning_content"))
        assertFalse(conversation.forSource("source2").getJSONObject(2).has("reasoning_content"))
        assertFalse(conversation.messages().toString().contains("provider private reasoning"))
    }

    @Test fun `images never enter current or archived planner history`() {
        val conversation = AgentConversation("system", "goal")
        repeat(30) { index -> conversation.append(turn(index), JSONObject().put("ok", true)
            .put("data", JSONObject().put("image_data_url", "data:image/jpeg;base64,raw")
                .put("nested", JSONArray().put("data:image/png;base64,raw"))), "source1") }
        assertFalse(conversation.messages().toString().contains("data:image/"))
        assertFalse(conversation.messages().toString().contains("image_data_url"))
    }

    @Test fun `reasoning and large results remain bounded even for the original model`() {
        val conversation = AgentConversation("system", "goal")
        repeat(40) { index -> conversation.append(turn(index, "r".repeat(16_384)),
            JSONObject().put("ok", true).put("data", JSONObject().put("description", "d".repeat(60_000))), "source1") }
        assertTrue(conversation.forSource("source1").toString().length <= AgentConversation.MAX_CONTEXT_CHARS)
    }

    @Test fun `recent call id replay prevention is bounded and cleared at task end`() {
        val conversation = AgentConversation("system", "goal")
        repeat(1_000) { assertTrue(conversation.acceptCallId("call_$it")) }
        assertFalse(conversation.acceptCallId("call_999"))
        assertFalse(conversation.acceptCallId("bad id"))
        assertTrue(conversation.acceptCallId("call_0"))
        conversation.clear()
        assertTrue(conversation.acceptCallId("call_999"))
        assertEquals(2, conversation.messages().length())
    }

    @Test fun `oversized single tool result keeps valid bounded JSON and recent identity`() {
        val conversation = AgentConversation("system", "goal")
        val data = JSONObject().put("video_id", "av42").put("official_source", "unknown")
        repeat(40) { data.put("extra_$it", "x".repeat(4_000)) }
        conversation.append(turn(1), JSONObject().put("ok", true).put("data", data), "source1")
        val messages = conversation.messages()
        assertEquals(4, messages.length())
        val body = messages.getJSONObject(3).getString("content")
        assertTrue(body.length <= AgentConversation.MAX_RESULT_CHARS)
        val decoded = JSONObject(body)
        assertTrue(decoded.getBoolean("truncated"))
        assertEquals("av42", decoded.getJSONObject("data").getString("video_id"))
    }
}
