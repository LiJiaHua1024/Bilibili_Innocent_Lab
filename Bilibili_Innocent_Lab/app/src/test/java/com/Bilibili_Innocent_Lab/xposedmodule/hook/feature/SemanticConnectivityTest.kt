package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SemanticConnectivityTest {

    @Before fun reset() = SemanticJudge.resetLearnedVariants()

    @After fun clear() = SemanticJudge.resetLearnedVariants()

    private val jev = requireNotNull(SemanticSource.from(1, "k", "https://relay.example.com", SemanticBackend.JEV, ""))

    private fun answer(body: ByteArray): String {
        val json = JSONObject(String(body))
        val questions = json.getJSONObject("questions")
        val answers = JSONObject()
        questions.keys().forEach { id ->
            val type = questions.getJSONObject(id).getString("type")
            answers.put(id, if (type == "noul") JSONObject().put("type", "noul").put("noul", 0.1)
            else JSONObject().put("type", "choice").put("probabilities", JSONObject().put("block", 0.1).put("keep", 0.9)))
        }
        return JSONObject().put("answers", answers).toString()
    }

    @Test
    fun `a working source reports success with the request format used`() {
        var sent = 0
        val result = SemanticConnectivity.probe(jev, transport = { body, key, _ ->
            sent++
            assertEquals("k", key)
            assertTrue(String(body).contains(SemanticConnectivity.SAMPLE))
            200 to answer(body)
        })
        assertTrue(result.ok)
        assertEquals("ok", result.outcome)
        assertEquals("choice", result.variant)
        assertEquals(1, sent) // 只发一条请求
    }

    @Test
    fun `failures carry the outcome for a readable message`() {
        assertEquals("http-401", SemanticConnectivity.probe(jev, transport = { _, _, _ -> 401 to "{}" }).outcome)
        assertEquals("network", SemanticConnectivity.probe(jev, transport = { _, _, _ -> null }).outcome)
        assertEquals("parse", SemanticConnectivity.probe(jev, transport = { _, _, _ -> 200 to "<html>" }).outcome.let {
            // 2xx 但读不出：先按写法回退，全部失败后是 parse。
            it
        })
        assertFalse(SemanticConnectivity.probe(jev, transport = { _, _, _ -> 429 to "{}" }).ok)
    }

    @Test
    fun `a decisions model that needs another request format still tests green`() {
        val respan = requireNotNull(SemanticSource.from(1, "k", "https://openrouter.ai/api/v1", SemanticBackend.JEV, "span-01-lite:free"))
        assertEquals(JevBackend.OPENROUTER_DECISIONS, respan.endpoint)
        val result = SemanticConnectivity.probe(respan, transport = { body, _, _ ->
            val json = JSONObject(String(body))
            val type = json.getJSONObject("questions").getJSONObject("item_0").getString("type")
            if (json.opt("state") !is String || type != "noul") 400 to "{}" else 200 to answer(body)
        })
        assertTrue(result.ok)
        assertEquals("noul+text", result.variant)
    }
}
