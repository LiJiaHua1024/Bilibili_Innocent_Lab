package com.Bilibili_Innocent_Lab.xposedmodule.agent.model

import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest

/** 编号与设置页一致。凭据仅留在模块进程；默认字符串表示不能泄露配置。 */
internal data class AgentModelSource(
    val index: Int,
    val endpoint: String,
    val apiKey: String,
    val model: String
) {
    val resolvedEndpoint: String = resolveEndpoint(endpoint)

    init {
        require(index in 1..MAX_SOURCES) { "来源编号应为 1–8" }
        require(apiKey.isNotBlank() && apiKey.length <= 4096 && apiKey.none { it.isISOControl() }) { "API Key 无效" }
        require(model.isNotBlank() && model.length <= 256 && model.none { it.isISOControl() }) { "模型名称无效" }
    }

    /** 换 Key、模型、地址均使旧能力失效；摘要从不用于认证。 */
    val fingerprint: String by lazy {
        MessageDigest.getInstance("SHA-256")
            .digest("$resolvedEndpoint\u0000$model\u0000$apiKey".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    override fun toString(): String = "AgentModelSource(index=$index)"

    companion object {
        const val MAX_SOURCES = 8

        fun from(index: Int, endpoint: String, apiKey: String, model: String): AgentModelSource? =
            runCatching { AgentModelSource(index, endpoint.trim(), apiKey.trim(), model.trim()) }.getOrNull()

        private fun resolveEndpoint(raw: String): String {
            require(raw.length in 1..2048 && raw.none { it.isWhitespace() || it.isISOControl() }) { "API 地址无效" }
            val uri = try { URI(raw) } catch (_: Exception) { throw IllegalArgumentException("API 地址无效") }
            require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() &&
                uri.rawUserInfo == null && uri.rawFragment == null && uri.port in -1..65535 && uri.port != 0) {
                "Agent API 必须使用无内嵌凭据的 HTTPS 地址"
            }
            val path = uri.rawPath.orEmpty().trimEnd('/')
            val resolved = when {
                path.endsWith("/chat/completions") -> path
                path.isEmpty() -> "/v1/chat/completions"
                else -> "$path/chat/completions"
            }
            return "https://${uri.rawAuthority}$resolved" + (uri.rawQuery?.let { "?$it" } ?: "")
        }
    }
}

internal enum class AgentCapabilityState { UNKNOWN, SUPPORTED, UNSUPPORTED }

internal data class AgentModelCapabilities(
    val tools: Boolean,
    val vision: Boolean,
    val checkedAtMs: Long,
    val detail: String,
    val toolState: AgentCapabilityState = if (tools) AgentCapabilityState.SUPPORTED else AgentCapabilityState.UNKNOWN,
    val visionState: AgentCapabilityState = if (vision) AgentCapabilityState.SUPPORTED else AgentCapabilityState.UNKNOWN
) {
    init {
        require(tools == (toolState == AgentCapabilityState.SUPPORTED))
        require(vision == (visionState == AgentCapabilityState.SUPPORTED))
    }

    fun fresh(nowMs: Long): Boolean = checkedAtMs > 0 && nowMs >= checkedAtMs && nowMs - checkedAtMs <= VALID_FOR_MS

    fun toJson(): JSONObject = JSONObject().put("tools", tools).put("vision", vision)
        .put("checkedAtMs", checkedAtMs).put("detail", detail.take(256))
        .put("toolState", toolState.name).put("visionState", visionState.name)

    companion object {
        const val VALID_FOR_MS = 7 * 24 * 60 * 60 * 1000L
        fun unknown(detail: String = "尚未检测") = AgentModelCapabilities(false, false, 0L, detail)
        fun fromJson(json: JSONObject): AgentModelCapabilities? = runCatching {
            AgentModelCapabilities(json.getBoolean("tools"), json.getBoolean("vision"),
                json.getLong("checkedAtMs"), json.getString("detail").take(256),
                AgentCapabilityState.valueOf(json.getString("toolState")),
                AgentCapabilityState.valueOf(json.getString("visionState")))
        }.getOrNull()
    }
}

internal data class AgentModelToolCall(val id: String, val name: String, val arguments: JSONObject) {
    override fun toString(): String = "AgentModelToolCall(name=$name)"
}

internal data class AgentModelUsage(val inputTokens: Long, val outputTokens: Long, val totalTokens: Long)

internal data class AgentModelTurn(
    val message: JSONObject,
    val toolCalls: List<AgentModelToolCall>,
    val text: String,
    val usage: AgentModelUsage? = null
) {
    override fun toString(): String = "AgentModelTurn(toolCalls=${toolCalls.size}, usage=$usage)"
}

/** detail 仅为模块生成的固定短语，绝不包含服务端响应正文。 */
internal class AgentModelException(
    val reason: Reason,
    val status: Int? = null,
    val retryAfterMs: Long? = null,
    /** 仅记录明确拒绝的兼容参数名，绝不保留服务端错误正文。 */
    val rejectedParameter: String? = null
) : Exception(reason.description) {
    enum class Reason(val description: String) {
        CANCELLED("任务已取消"), TIMEOUT("模型请求超时"), NETWORK("模型网络请求失败"),
        HTTP("模型接口拒绝请求"), REDIRECT("模型接口重定向已拒绝"),
        TOO_LARGE("模型请求或响应超过大小限制"), INVALID_RESPONSE("模型响应结构无效"),
        INVALID_REQUEST("模型请求结构无效"), OUTPUT_LIMIT("模型输出达到预算上限"),
        VISION_UNVERIFIED("该来源尚未通过视觉检测"),
        TOKEN_PARAMETER("模型不接受当前输出预算参数"),
        OPTIONAL_PARAMETER("模型不接受当前思考控制参数"),
        TOOLS_UNSUPPORTED("接口明确不支持工具调用"), VISION_UNSUPPORTED("接口明确不支持图像输入")
    }
}
