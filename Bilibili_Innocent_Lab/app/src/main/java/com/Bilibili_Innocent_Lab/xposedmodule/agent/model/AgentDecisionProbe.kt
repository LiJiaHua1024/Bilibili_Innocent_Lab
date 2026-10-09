package com.Bilibili_Innocent_Lab.xposedmodule.agent.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 实际检测每种限定题型；决策能力不等于自由工具调用。图像挑战答案只从图里获得，
 * Choice 每位提供完整字母数字集合；Noul/Score 用六位字符的 36 个二分题和一个反向对照。
 */
internal class AgentDecisionProbe(
    private val client: AgentDecisionClient = AgentDecisionClient(),
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun probe(
        source: AgentModelSource,
        visionChallenge: VisionChallenge? = null,
        timeoutMs: Int = 30_000,
        cancelled: () -> Boolean = { false }
    ): AgentModelCapabilities {
        if (source.protocol != AgentSourceProtocol.DECISIONS) throw AgentModelException(AgentModelException.Reason.INVALID_REQUEST)
        val deadline = System.nanoTime() + timeoutMs.coerceIn(1, AgentHttpsTransport.MAX_TIMEOUT_MS) * 1_000_000L
        val formats = linkedSetOf<String>()
        var detail = "本次限定问题未通过，能力尚未确认"
        for (format in FORMATS) {
            try {
                val (state, questions, expected) = textChallenge(format)
                val result = client.probeEvaluate(source, state, questions, timeoutMs = remaining(deadline, cancelled), cancelled = cancelled)
                if (matches(result.answers, expected)) formats += format
            } catch (error: AgentModelException) {
                if (error.reason == AgentModelException.Reason.CANCELLED) throw error
                detail = error.reason.description
                // 凭据、服务或网络失败对所有题型都一样；不再为另外两种题型重复付费重试。
                if (!formatFailure(error)) break
            }
        }
        var visionState = AgentCapabilityState.UNKNOWN
        val visionFormats = linkedSetOf<String>()
        var visionDetail = if (visionChallenge == null) "未提供图片挑战" else "图像挑战未通过，能力尚未确认"
        if (formats.isNotEmpty() && visionChallenge != null) {
            for (format in FORMATS.filter(formats::contains)) {
                try {
                    if (visionMatches(source, visionChallenge, format, deadline, cancelled)) {
                        visionState = AgentCapabilityState.SUPPORTED
                        visionDetail = "实际图片限定判断通过"
                        visionFormats += format
                        break
                    }
                } catch (error: AgentModelException) {
                    if (error.reason == AgentModelException.Reason.CANCELLED) throw error
                    visionDetail = error.reason.description
                    if (error.reason == AgentModelException.Reason.VISION_UNSUPPORTED) {
                        visionState = AgentCapabilityState.UNSUPPORTED
                        break
                    }
                    if (!formatFailure(error)) break
                }
            }
        }
        if (cancelled()) throw AgentModelException(AgentModelException.Reason.CANCELLED)
        val decisions = formats.isNotEmpty()
        return AgentModelCapabilities(
            tools = false,
            vision = visionState == AgentCapabilityState.SUPPORTED,
            checkedAtMs = clock(),
            detail = "决策：${if (decisions) "实际检测通过 (${formats.joinToString("/")})" else detail}；视觉：$visionDetail",
            toolState = AgentCapabilityState.UNSUPPORTED,
            visionState = visionState,
            decisions = decisions,
            decisionState = if (decisions) AgentCapabilityState.SUPPORTED else AgentCapabilityState.UNKNOWN,
            decisionFormats = formats,
            decisionVisionFormats = visionFormats
        ).also { client.setCapabilities(source, it) }
    }

    private data class Challenge(
        val state: JSONObject,
        val questions: JSONObject,
        val expected: Map<String, String>
    )

    private fun textChallenge(format: String): Challenge {
        val nonce = UUID.randomUUID().toString().replace("-", "")
        val decoy = UUID.randomUUID().toString().replace("-", "")
        val state = JSONObject().put("goal", "Compare tokens exactly, including their capitalization.").put("token", nonce)
        val questions = JSONObject()
        val expected = linkedMapOf<String, String>()
        if (format == "choice") {
            // 每题独立打乱，恒定选择某个 option 的接口不能轻易得到文字决策证明。
            repeat(6) { questionIndex ->
                val correct = (0..3).random()
                val criteria = JSONObject()
                repeat(4) { index -> criteria.put("option_$index", if (index == correct) nonce else "$decoy$index") }
                val id = "token_match_$questionIndex"
                questions.put(id, JSONObject().put("type", format)
                    .put("instructions", "Which criterion is exactly equal to state.token? No paraphrasing.").put("criteria", criteria))
                expected[id] = "option_$correct"
            }
        } else {
            repeat(4) { index ->
                val yes = index % 2 == 0
                val target = if (yes) nonce else "$decoy$index"
                val id = "token_$index"
                questions.put(id, binaryQuestion(format, "Is state.token exactly equal to '$target'?"))
                expected[id] = yes.toString()
            }
        }
        return Challenge(state, questions, expected)
    }

    private fun visionMatches(
        source: AgentModelSource, challenge: VisionChallenge, format: String,
        deadline: Long, cancelled: () -> Boolean
    ): Boolean {
        // 六位已经提供至少 62^6 个可能答案；只送最少必要题目，避免检测调用随图片字数膨胀。
        val target = challenge.expectedAnswer.take(6)
        val questions = JSONObject()
        val expected = linkedMapOf<String, String>()
        if (format == "choice") {
            target.forEachIndexed { position, character ->
                val id = "character_$position"
                val criteria = JSONObject()
                ALPHABET.forEachIndexed { index, option -> criteria.put("c_$index", option.toString()) }
                questions.put(id, JSONObject().put("type", "choice")
                    .put("instructions", "Read character ${position + 1} from left to right in the image. Preserve case. Choose the displayed character only.")
                    .put("criteria", criteria))
                expected[id] = "c_${ALPHABET.indexOf(character)}"
            }
        } else {
            target.forEachIndexed { position, character ->
                repeat(6) { bit ->
                    val subset = ALPHABET.filterIndexed { index, _ -> (index and (1 shl bit)) != 0 }
                    val id = "character_${position}_bit_$bit"
                    questions.put(id, binaryQuestion(format,
                        "Read character ${position + 1} from left to right in the image, preserving case. Is that character one of these characters: $subset?"))
                    expected[id] = ((ALPHABET.indexOf(character) and (1 shl bit)) != 0).toString()
                }
            }
            // 和第一题相反，保证每份图片的完整挑战都有 true / false，恒 yes/no 都不能通过。
            val complement = ALPHABET.filterIndexed { index, _ -> (index and 1) == 0 }
            questions.put("inverse_control", binaryQuestion(format,
                "Read the first character in the image, preserving case. Is it one of these characters: $complement?"))
            expected["inverse_control"] = (!expected.getValue("character_0_bit_0").toBoolean()).toString()
        }
        val ids = questions.keys().asSequence().toList()
        val state = JSONObject().put("goal", "Read the image itself; supplied criteria list all possibilities, not the correct answers.")
        for (batch in ids.chunked(AgentDecisionClient.MAX_QUESTIONS)) {
            val group = JSONObject()
            batch.forEach { group.put(it, questions.getJSONObject(it)) }
            val result = client.probeEvaluate(source, state, group, challenge.dataUrl,
                remaining(deadline, cancelled), cancelled)
            if (!matches(result.answers, expected.filterKeys(batch::contains))) return false
        }
        return true
    }

    private fun binaryQuestion(format: String, instruction: String): JSONObject = JSONObject().put("type", format)
        .put("instructions", instruction).put("criteria", if (format == "noul")
            JSONObject().put("true", "The statement is true.").put("false", "The statement is false.")
        else JSONArray().put("The statement is false.").put("The statement is true."))

    private fun matches(answers: JSONObject, expected: Map<String, String>): Boolean = expected.all { (id, target) ->
        val answer = answers.getJSONObject(id)
        when (answer.getString("type")) {
            "choice" -> answer.getString("choice") == target && answer.getJSONObject("probabilities").getDouble(target) >= 0.8
            "noul" -> matchProbability(answer.getDouble("noul"), target.toBoolean())
            "score" -> matchProbability(answer.getJSONObject("probabilities").getDouble("1"), target.toBoolean())
            else -> false
        }
    }

    private fun matchProbability(probability: Double, expected: Boolean): Boolean =
        if (expected) probability >= 0.8 else probability <= 0.2

    private fun formatFailure(error: AgentModelException): Boolean =
        error.reason in setOf(AgentModelException.Reason.DECISION_PARAMETER, AgentModelException.Reason.INVALID_RESPONSE)

    private fun remaining(deadline: Long, cancelled: () -> Boolean): Int {
        if (cancelled() || Thread.currentThread().isInterrupted) throw AgentModelException(AgentModelException.Reason.CANCELLED)
        val millis = (deadline - System.nanoTime()) / 1_000_000L
        if (millis <= 0) throw AgentModelException(AgentModelException.Reason.TIMEOUT)
        return millis.coerceAtMost(AgentHttpsTransport.MAX_TIMEOUT_MS.toLong()).toInt()
    }

    companion object {
        private val FORMATS = listOf("choice", "noul", "score")
        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    }
}
