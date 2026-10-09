package com.Bilibili_Innocent_Lab.xposedmodule.agent.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 调用方用 Canvas 绘制随机字符；正确答案不得放进发送给模型的文字提示。 */
internal data class VisionChallenge(val dataUrl: String, val expectedAnswer: String) {
    init {
        require(AgentModelClient.validImageDataUrl(dataUrl)) { "视觉挑战图片无效" }
        require(Regex("[A-Za-z0-9]{6,32}").matches(expectedAnswer)) { "视觉挑战必须包含至少六个随机字符" }
    }
    override fun toString(): String = "VisionChallenge(redacted)"
}

/** 能力来自实际回路；认证失败、超时、答错都只说明未确认，不能宣称模型不支持。 */
internal class AgentCapabilityProbe(
    private val client: AgentModelClient,
    private val clock: () -> Long = System::currentTimeMillis,
    private val decisionProbe: AgentDecisionProbe = AgentDecisionProbe(client.decisionClientForProbe(), clock)
) {
    fun probe(
        source: AgentModelSource,
        visionChallenge: VisionChallenge? = null,
        timeoutMs: Int = 30_000,
        cancelled: () -> Boolean = { false }
    ): AgentModelCapabilities {
        if (source.protocol == AgentSourceProtocol.DECISIONS) {
            return decisionProbe.probe(source, visionChallenge, timeoutMs, cancelled)
        }
        val deadline = System.nanoTime() + timeoutMs.coerceIn(1, AgentHttpsTransport.MAX_TIMEOUT_MS) * 1_000_000L
        fun remaining(): Int {
            if (cancelled()) throw AgentModelException(AgentModelException.Reason.CANCELLED)
            val value = ((deadline - System.nanoTime()) / 1_000_000L).toInt()
            if (value <= 0) throw AgentModelException(AgentModelException.Reason.TIMEOUT)
            return value
        }
        val tools = checkCapability(AgentModelException.Reason.TOOLS_UNSUPPORTED) {
            val nonce = UUID.randomUUID().toString()
            val schema = JSONObject().put("type", "object")
                .put("properties", JSONObject().put("nonce", JSONObject().put("type", "string")))
                .put("required", JSONArray().put("nonce")).put("additionalProperties", false)
            val definitions = JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject()
                .put("name", "capability_echo").put("description", "A harmless local echo capability test")
                .put("parameters", schema)))
            val messages = JSONArray().put(JSONObject().put("role", "user").put("content",
                "Call capability_echo exactly once with nonce '$nonce'. Then read its result and reply only with the receipt value."))
            val first = client.probeGenerate(source, messages, definitions, false, remaining(), cancelled)
            val call = first.toolCalls.singleOrNull()
            if (call == null || call.name != "capability_echo" || call.arguments.length() != 1 ||
                call.arguments.opt("nonce") != nonce) false
            else {
                // 第一个模型响应后才产生 receipt；无法直接复述用户提示来假装工具往返成功。
                val receipt = UUID.randomUUID().toString()
                messages.put(first.message).put(JSONObject().put("role", "tool").put("tool_call_id", call.id)
                    .put("content", JSONObject().put("receipt", receipt).toString()))
                val last = client.probeGenerate(source, messages, definitions, false, remaining(), cancelled)
                last.toolCalls.isEmpty() && last.text.trim() == receipt
            }
        }
        val vision = if (visionChallenge == null) ProbeResult(AgentCapabilityState.UNKNOWN, "未提供图片挑战")
        else if (tools.failure?.let(::sharedFailure) == true) {
            // 两个题型共用凭据、端点与总期限；共同故障不能靠再传一张图得到可靠能力证明。
            ProbeResult(AgentCapabilityState.UNKNOWN, "共同的凭据或网络故障，本次未继续检测图片")
        } else checkCapability(AgentModelException.Reason.VISION_UNSUPPORTED) {
            val content = JSONArray().put(JSONObject().put("type", "text").put("text",
                "Read the characters shown in this image. Reply with those characters only; preserve capitalization."))
                .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", visionChallenge.dataUrl)))
            val messages = JSONArray().put(JSONObject().put("role", "user").put("content", content))
            val turn = client.probeGenerate(source, messages, JSONArray(), true, remaining(), cancelled)
            turn.toolCalls.isEmpty() && turn.text.trim() == visionChallenge.expectedAnswer
        }
        val plain = if (tools.state == AgentCapabilityState.SUPPORTED) ProbeResult(AgentCapabilityState.UNKNOWN, "优先使用已验证工具模式")
        else if (tools.failure?.let(::sharedFailure) == true || vision.failure?.let(::sharedFailure) == true)
            ProbeResult(AgentCapabilityState.UNKNOWN, "共同故障，本次未继续检测文本规划")
        else checkCapability(AgentModelException.Reason.PLAIN_UNVERIFIED) {
            AgentTextPlanner(client, clock).probe(source, remaining(), cancelled)
        }
        if (cancelled()) throw AgentModelException(AgentModelException.Reason.CANCELLED)
        return AgentModelCapabilities(
            tools.state == AgentCapabilityState.SUPPORTED,
            vision.state == AgentCapabilityState.SUPPORTED,
            clock(), "工具：${tools.detail}；文本：${plain.detail}；视觉：${vision.detail}", tools.state, vision.state,
            plainPlanning = plain.state == AgentCapabilityState.SUPPORTED, plainState = plain.state
        ).also { client.setCapabilities(source, it) }
    }

    private data class ProbeResult(val state: AgentCapabilityState, val detail: String,
                                   val failure: AgentModelException? = null)

    private fun sharedFailure(error: AgentModelException): Boolean =
        error.reason in setOf(AgentModelException.Reason.NETWORK, AgentModelException.Reason.TIMEOUT) ||
            error.reason == AgentModelException.Reason.HTTP && error.status == 401

    private fun checkCapability(unsupported: AgentModelException.Reason, action: () -> Boolean): ProbeResult = try {
        if (action()) ProbeResult(AgentCapabilityState.SUPPORTED, "实际检测通过")
        else ProbeResult(AgentCapabilityState.UNKNOWN, "未通过本次挑战，能力尚未确认")
    } catch (e: AgentModelException) {
        if (e.reason == AgentModelException.Reason.CANCELLED) throw e
        if (e.reason == unsupported) ProbeResult(AgentCapabilityState.UNSUPPORTED, e.reason.description)
        else ProbeResult(AgentCapabilityState.UNKNOWN, e.reason.description, e)
    }
}
