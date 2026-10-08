package com.Bilibili_Innocent_Lab.xposedmodule.agent.model

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AgentHttpTransportTest {
    private val source = AgentModelSource(1, "https://example.test/v1", "private-key", "model")
    private val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "test"))
    private val tools = JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject()
        .put("name", "echo").put("parameters", JSONObject().put("type", "object"))))
    private val success = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}"

    private open class Connection(private val status: Int = 200, private val payload: String = "{}") :
        HttpURLConnection(URL("https://example.test/v1/chat/completions")) {
        val disconnected = AtomicBoolean()
        val output = ByteArrayOutputStream()
        override fun connect() = Unit
        override fun usingProxy(): Boolean = false
        override fun disconnect() { disconnected.set(true) }
        override fun getResponseCode(): Int = status
        override fun getOutputStream() = output
        override fun getInputStream(): InputStream = ByteArrayInputStream(payload.toByteArray(Charsets.UTF_8))
        override fun getErrorStream(): InputStream = ByteArrayInputStream(payload.toByteArray(Charsets.UTF_8))
        override fun getContentLengthLong(): Long = -1
    }

    @Test fun `request uses bearer HTTPS POST no redirects and bounded timeouts`() {
        val connection = Connection(payload = "{\"value\":1}")
        val result = AgentHttpsTransport.postWithConnection(source, "body".toByteArray(), 2000, { false }) { url ->
            assertEquals("https", url.protocol); connection
        }
        assertEquals("{\"value\":1}", result)
        assertEquals("POST", connection.requestMethod)
        assertFalse(connection.instanceFollowRedirects)
        assertFalse(connection.useCaches)
        assertTrue(connection.connectTimeout in 1..2000)
        assertTrue(connection.readTimeout in 1..2000)
        assertEquals("Bearer private-key", connection.getRequestProperty("Authorization"))
        assertEquals("body", connection.output.toString(Charsets.UTF_8.name()))
        assertTrue(connection.disconnected.get())
    }

    @Test fun `redirect is refused before reading or forwarding any response`() {
        val connection = object : Connection(status = 302) {
            override fun getInputStream(): InputStream = error("must not follow redirect")
            override fun getHeaderField(name: String?): String = "https://different-host.test/steal"
        }
        try { AgentHttpsTransport.postWithConnection(source, byteArrayOf(), 1000, { false }) { connection }; fail("redirect expected") }
        catch (e: AgentModelException) { assertEquals(AgentModelException.Reason.REDIRECT, e.reason) }
        assertTrue(connection.disconnected.get())
    }

    @Test fun `chunked response without content length still has a byte limit`() {
        val connection = Connection(payload = "x".repeat(AgentHttpsTransport.MAX_RESPONSE_BYTES + 1))
        try { AgentHttpsTransport.postWithConnection(source, byteArrayOf(), 1000, { false }) { connection }; fail("size expected") }
        catch (e: AgentModelException) { assertEquals(AgentModelException.Reason.TOO_LARGE, e.reason) }
        assertTrue(connection.disconnected.get())
    }

    @Test fun `cancel while blocked disconnects and makes late response unusable`() {
        val entered = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val connection = object : Connection() {
            override fun getResponseCode(): Int {
                entered.countDown()
                disconnected.await(2, TimeUnit.SECONDS)
                return 200
            }
            override fun disconnect() { super.disconnect(); disconnected.countDown() }
        }
        val start = System.nanoTime()
        try {
            AgentHttpsTransport.postWithConnection(source, byteArrayOf(), 2000, { entered.count == 0L }) { connection }
            fail("cancel expected")
        } catch (e: AgentModelException) { assertEquals(AgentModelException.Reason.CANCELLED, e.reason) }
        assertTrue(connection.disconnected.get())
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1500)
    }

    @Test fun `absolute deadline also bounds response header blocking`() {
        val disconnected = CountDownLatch(1)
        val connection = object : Connection() {
            override fun getResponseCode(): Int { disconnected.await(2, TimeUnit.SECONDS); return 200 }
            override fun disconnect() { super.disconnect(); disconnected.countDown() }
        }
        try { AgentHttpsTransport.postWithConnection(source, byteArrayOf(), 100, { false }) { connection }; fail("timeout expected") }
        catch (e: AgentModelException) { assertEquals(AgentModelException.Reason.TIMEOUT, e.reason) }
        assertTrue(connection.disconnected.get())
    }

    @Test fun `known providers use verified non streaming reasoning controls while unknown hosts stay plain`() {
        val requests = mutableListOf<JSONObject>()
        val client = AgentModelClient(AgentHttpTransport { src, body, timeout, cancelled ->
            AgentHttpsTransport.postWithConnection(src, body, timeout, cancelled) {
                object : Connection(payload = success) {
                    override fun getResponseCode(): Int { requests += JSONObject(output.toString(Charsets.UTF_8.name())); return 200 }
                }
            }
        })
        val endpoints = listOf("https://dashscope.aliyuncs.com/compatible-mode/v1", "https://api.siliconflow.cn/v1",
            "https://api.deepseek.com", "https://open.bigmodel.cn/api/paas/v4", "https://api.z.ai/api/paas/v4",
            "https://ark.cn-beijing.volces.com/api/v3", "https://openrouter.ai/api/v1", "https://unknown.example/v1")
        endpoints.forEach { endpoint -> client.generate(source.copy(endpoint = endpoint), messages, tools, false, 5000) { false } }
        assertFalse(requests[0].getBoolean("enable_thinking"))
        assertFalse(requests[1].getBoolean("enable_thinking"))
        for (index in 2..5) assertEquals("disabled", requests[index].getJSONObject("thinking").getString("type"))
        assertEquals("none", requests[6].getJSONObject("reasoning").getString("effort"))
        assertFalse(requests.last().has("reasoning_effort"))
        assertFalse(requests.last().has("enable_thinking"))
        assertFalse(requests.last().has("thinking"))
        assertFalse(requests.last().has("reasoning"))
        requests.forEach { assertFalse(it.getBoolean("stream")); assertEquals(2048, it.getInt("max_tokens")); assertTrue(it.has("tools")) }
    }

    @Test fun `only explicit optional parameter rejection removes that control with one bounded retry`() {
        val requests = mutableListOf<JSONObject>()
        val timeouts = mutableListOf<Int>()
        val qwen = source.copy(endpoint = "https://dashscope.aliyuncs.com/compatible-mode/v1")
        val client = AgentModelClient(AgentHttpTransport { src, body, timeout, cancelled ->
            timeouts += timeout
            val status = if (requests.isEmpty()) 400 else 200
            val payload = if (status == 400) "{\"error\":{\"code\":\"unsupported_parameter\",\"param\":\"enable_thinking\",\"message\":\"secret\"}}" else success
            AgentHttpsTransport.postWithConnection(src, body, timeout, cancelled) {
                object : Connection(status, payload) {
                    override fun getResponseCode(): Int { requests += JSONObject(output.toString(Charsets.UTF_8.name())); return status }
                }
            }
        })
        assertEquals("ok", client.generate(qwen, messages, tools, false, 5000) { false }.text)
        assertEquals(2, requests.size)
        assertFalse(requests[0].getBoolean("enable_thinking"))
        assertFalse(requests[1].has("enable_thinking"))
        assertTrue(timeouts[1] <= timeouts[0])
        requests.forEach { assertEquals(tools.toString(), it.getJSONArray("tools").toString()); assertEquals(2048, it.getInt("max_tokens")) }
        client.generate(qwen, messages, tools, false, 5000) { false }
        assertEquals(3, requests.size)
        assertFalse(requests.last().has("enable_thinking"))
    }

    @Test fun `unstructured errors tools rejection or rejection of an absent control never trigger stripping`() {
        val errors = listOf(
            "{\"error\":{\"message\":\"enable_thinking unsupported\"}}",
            "{\"error\":{\"code\":\"unsupported_parameter\",\"param\":\"tools\"}}",
            "{\"error\":{\"code\":\"unsupported_parameter\",\"param\":\"reasoning.effort\"}}"
        )
        for (error in errors) {
            var attempts = 0
            val client = AgentModelClient(AgentHttpTransport { src, body, timeout, cancelled ->
                attempts++
                AgentHttpsTransport.postWithConnection(src, body, timeout, cancelled) { Connection(400, error) }
            })
            try { client.generate(source.copy(endpoint = "https://dashscope.aliyuncs.com/v1"), messages, tools, false, 5000) { false }; fail("reject expected") }
            catch (_: AgentModelException) { assertEquals(1, attempts) }
        }
    }

    @Test fun `reasoning and output budget compatibility share at most one retry`() {
        var attempts = 0
        val client = AgentModelClient(AgentHttpTransport { src, body, timeout, cancelled ->
            val parameter = if (attempts++ == 0) "enable_thinking" else "max_tokens"
            val error = "{\"error\":{\"code\":\"unsupported_parameter\",\"param\":\"$parameter\"}}"
            assertTrue(JSONObject(String(body, Charsets.UTF_8)).has("tools"))
            AgentHttpsTransport.postWithConnection(src, body, timeout, cancelled) { Connection(400, error) }
        })
        try { client.generate(source.copy(endpoint = "https://dashscope.aliyuncs.com/v1"), messages, tools, false, 5000) { false }; fail("reject expected") }
        catch (error: AgentModelException) { assertEquals(AgentModelException.Reason.TOKEN_PARAMETER, error.reason) }
        assertEquals(2, attempts)
    }
}
