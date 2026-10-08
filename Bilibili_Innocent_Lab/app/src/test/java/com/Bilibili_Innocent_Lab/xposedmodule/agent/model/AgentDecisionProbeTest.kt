package com.Bilibili_Innocent_Lab.xposedmodule.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentDecisionProbeTest {
    private val source = AgentModelSource(1, "https://example.test/v1/systemone", "test-key", "name-is-not-proof", AgentSourceProtocol.DECISIONS)
    private val challenge = VisionChallenge("data:image/png;base64,YWJj", "A7c9E2")

    private fun stateOf(body: JSONObject): JSONObject = when (val value = body.get("state")) {
        is JSONObject -> value
        is JSONArray -> JSONObject()
        is String -> {
            val offset = value.indexOf("State: ")
            check(offset >= 0)
            JSONObject(value.substring(offset + "State: ".length))
        }
        else -> error("bad state")
    }

    /** 本地假服务根据请求数据完成文字题；图片题的答案只由测试持有，不从请求文字推断。 */
    private fun answer(body: JSONObject, alwaysYesForImage: Boolean = false): String {
        val questions = body.getJSONObject("questions")
        val image = body.opt("state") is JSONArray
        val state = stateOf(body)
        val answers = JSONObject()
        questions.keys().forEach { id ->
            val question = questions.getJSONObject(id)
            val format = question.getString("type")
            val instruction = question.getString("instructions")
            val result = JSONObject().put("type", format)
            if (format == "choice") {
                val criteria = question.getJSONObject("criteria")
                val token = if (image) {
                    val position = Regex("character (\\d+)").find(instruction)!!.groupValues[1].toInt() - 1
                    challenge.expectedAnswer[position].toString()
                } else state.getString("token")
                val choice = criteria.keys().asSequence().single { criteria.getString(it) == token }
                val probabilities = JSONObject()
                criteria.keys().forEach { key -> probabilities.put(key, if (key == choice) 0.99 else 0.01 / (criteria.length() - 1)) }
                result.put("choice", choice).put("probabilities", probabilities).put("confidence", 0.98)
            } else {
                val yes = if (image) {
                    if (alwaysYesForImage) true else {
                        val position = Regex("character (\\d+)").find(instruction)?.groupValues?.get(1)?.toInt()?.minus(1) ?: 0
                        val subset = instruction.substring(instruction.indexOf(':') + 1).trim().removeSuffix("?")
                        challenge.expectedAnswer[position] in subset
                    }
                } else {
                    val quoted = Regex("'([^']+)'").find(instruction)!!.groupValues[1]
                    state.getString("token") == quoted
                }
                val probability = if (yes) 0.99 else 0.01
                if (format == "noul") result.put("noul", probability)
                else result.put("score", probability).put("confidence", 0.98)
                    .put("probabilities", JSONObject().put("0", 1 - probability).put("1", probability))
            }
            answers.put(id, result)
        }
        return JSONObject().put("answers", answers).toString()
    }

    @Test fun `decision and visual probes use typed answers and never claim tool support`() {
        val requests = mutableListOf<JSONObject>()
        val client = AgentDecisionClient(AgentHttpTransport { _, bytes, _, _ ->
            val body = JSONObject(String(bytes, Charsets.UTF_8)); requests += body
            if (body.opt("state") is JSONArray) {
                assertEquals(6, body.getJSONObject("questions").length())
                assertFalse(body.getJSONArray("state").getString(0).contains(challenge.expectedAnswer))
                body.getJSONObject("questions").keys().forEach { id ->
                    assertEquals(62, body.getJSONObject("questions").getJSONObject(id).getJSONObject("criteria").length())
                }
            }
            answer(body)
        }, clock = { 10_000L })
        val caps = AgentDecisionProbe(client, clock = { 10_000L }).probe(source, challenge)
        assertTrue(caps.decisions); assertTrue(caps.vision); assertFalse(caps.tools)
        assertEquals(AgentCapabilityState.UNSUPPORTED, caps.toolState)
        assertEquals(setOf("choice", "noul", "score"), caps.decisionFormats)
        assertEquals(setOf("choice"), caps.decisionVisionFormats)
        assertEquals(4, requests.size)
        assertFalse(caps.detail.contains("test-key"))
    }

    @Test fun `noul only visual probe is strong and split into bounded batches with both answer signs`() {
        val images = mutableListOf<JSONObject>()
        val client = AgentDecisionClient(AgentHttpTransport { _, bytes, _, _ ->
            val body = JSONObject(String(bytes, Charsets.UTF_8))
            val questions = body.getJSONObject("questions")
            if (questions.getJSONObject(questions.keys().next()).getString("type") != "noul") {
                throw AgentModelException(AgentModelException.Reason.DECISION_PARAMETER, 422, rejectedParameter = "type")
            }
            if (body.opt("state") is JSONArray) {
                images += body
                assertTrue(questions.length() <= AgentDecisionClient.MAX_QUESTIONS)
                assertFalse(body.getJSONArray("state").getString(0).contains(challenge.expectedAnswer))
            }
            answer(body)
        }, clock = { 10_000L })
        val caps = AgentDecisionProbe(client, clock = { 10_000L }).probe(source, challenge)
        assertTrue(caps.decisions); assertTrue(caps.vision)
        assertEquals(setOf("noul"), caps.decisionFormats)
        assertEquals(setOf("noul"), caps.decisionVisionFormats)
        assertEquals(2, images.size)
        assertEquals(37, images.sumOf { it.getJSONObject("questions").length() })
        val signs = images.flatMap { body ->
            val answers = JSONObject(answer(body)).getJSONObject("answers")
            answers.keys().asSequence().map { answers.getJSONObject(it).getDouble("noul") > 0.5 }.toList()
        }.toSet()
        assertEquals(setOf(true, false), signs)
    }

    @Test fun `text only service can prove decisions but image failure remains unknown`() {
        var imageRequests = 0
        val client = AgentDecisionClient(AgentHttpTransport { _, bytes, _, _ ->
            val body = JSONObject(String(bytes, Charsets.UTF_8))
            if (body.opt("state") is JSONArray) {
                imageRequests++
                throw AgentModelException(AgentModelException.Reason.HTTP, 400)
            }
            answer(body)
        })
        val caps = AgentDecisionProbe(client).probe(source, challenge)
        assertTrue(caps.decisions); assertFalse(caps.vision)
        assertEquals(AgentCapabilityState.UNKNOWN, caps.visionState)
        assertTrue(caps.decisionVisionFormats.isEmpty())
        assertEquals(1, imageRequests)
    }

    @Test fun `incorrect visual guesses do not become unsupported or gain proof`() {
        val client = AgentDecisionClient(AgentHttpTransport { _, bytes, _, _ ->
            val body = JSONObject(String(bytes, Charsets.UTF_8))
            val root = JSONObject(answer(body, alwaysYesForImage = true))
            if (body.opt("state") is JSONArray) root.getJSONObject("answers").keys().forEach { id ->
                val result = root.getJSONObject("answers").getJSONObject(id)
                if (result.getString("type") == "choice") {
                    val criteria = body.getJSONObject("questions").getJSONObject(id).getJSONObject("criteria")
                    val probabilities = JSONObject()
                    criteria.keys().forEach { key -> probabilities.put(key, if (key == "c_0") 0.99 else 0.01 / 61) }
                    result.put("choice", "c_0").put("probabilities", probabilities)
                }
            }
            root.toString()
        })
        val caps = AgentDecisionProbe(client).probe(source, challenge)
        assertTrue(caps.decisions)
        assertFalse(caps.vision)
        assertEquals(AgentCapabilityState.UNKNOWN, caps.visionState)
        assertTrue(caps.decisionVisionFormats.isEmpty())
    }

    @Test fun `explicit image rejection distinguishes unsupported from general errors`() {
        val client = AgentDecisionClient(AgentHttpTransport { _, bytes, _, _ ->
            val body = JSONObject(String(bytes, Charsets.UTF_8))
            if (body.opt("state") is JSONArray) throw AgentModelException(AgentModelException.Reason.VISION_UNSUPPORTED, 422)
            answer(body)
        })
        val caps = AgentDecisionProbe(client).probe(source, challenge)
        assertTrue(caps.decisions); assertFalse(caps.vision)
        assertEquals(AgentCapabilityState.UNSUPPORTED, caps.visionState)
    }

    @Test fun `authentication network and timeout do not assert unsupported decisions or retry formats`() {
        for (reason in listOf(AgentModelException.Reason.HTTP, AgentModelException.Reason.NETWORK, AgentModelException.Reason.TIMEOUT)) {
            var attempts = 0
            val client = AgentDecisionClient(AgentHttpTransport { _, _, _, _ ->
                attempts++; throw AgentModelException(reason, if (reason == AgentModelException.Reason.HTTP) 401 else null)
            })
            val caps = AgentDecisionProbe(client).probe(source, challenge)
            assertFalse(caps.decisions); assertFalse(caps.vision)
            assertEquals(AgentCapabilityState.UNKNOWN, caps.decisionState)
            assertEquals(AgentCapabilityState.UNKNOWN, caps.visionState)
            assertEquals(1, attempts)
        }
    }

    @Test fun `known text formats do not leak into visual proof if only noul image tests succeed`() {
        val client = AgentDecisionClient(AgentHttpTransport { _, bytes, _, _ ->
            val body = JSONObject(String(bytes, Charsets.UTF_8))
            val questions = body.getJSONObject("questions")
            val format = questions.getJSONObject(questions.keys().next()).getString("type")
            if (body.opt("state") is JSONArray && format == "choice") {
                throw AgentModelException(AgentModelException.Reason.DECISION_PARAMETER, 422, rejectedParameter = "type")
            }
            answer(body)
        })
        val caps = AgentDecisionProbe(client).probe(source, challenge)
        assertEquals(setOf("choice", "noul", "score"), caps.decisionFormats)
        assertEquals(setOf("noul"), caps.decisionVisionFormats)
        assertTrue(caps.vision)
    }

    @Test fun `string state requirement is discovered with explicit schema rejection`() {
        var stringRequests = 0
        val client = AgentDecisionClient(AgentHttpTransport { _, bytes, _, _ ->
            val body = JSONObject(String(bytes, Charsets.UTF_8))
            if (body.opt("state") is JSONObject) throw AgentModelException(AgentModelException.Reason.DECISION_PARAMETER, 422, rejectedParameter = "state")
            stringRequests++
            answer(body)
        })
        val caps = AgentDecisionProbe(client).probe(source)
        assertTrue(caps.decisions)
        assertEquals(setOf("choice", "noul", "score"), caps.decisionFormats)
        assertEquals(3, stringRequests)
        assertEquals(AgentCapabilityState.UNKNOWN, caps.visionState)
    }

    @Test fun `cancelled challenge never returns a capability record`() {
        var cancelled = false
        val client = AgentDecisionClient(AgentHttpTransport { _, bytes, _, _ ->
            cancelled = true
            answer(JSONObject(String(bytes, Charsets.UTF_8)))
        })
        try {
            AgentDecisionProbe(client).probe(source, challenge, cancelled = { cancelled })
            fail("expected cancellation")
        } catch (error: AgentModelException) { assertEquals(AgentModelException.Reason.CANCELLED, error.reason) }
    }
}
