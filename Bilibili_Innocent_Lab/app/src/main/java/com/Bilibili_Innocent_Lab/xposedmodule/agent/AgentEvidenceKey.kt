package com.Bilibili_Innocent_Lab.xposedmodule.agent

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** 辅助判断的输入身份；保留视觉证据及unknown/false，仅排除不会改变输入事实的计时与缓存标记。 */
internal object AgentEvidenceKey {
    private val metadata = setOf("observed_at_elapsed", "capture_elapsed", "cache_hit")
    fun of(sourceFingerprint: String, policy: String, input: JSONObject): String =
        hash(sourceFingerprint + "\u0000" + policy + "\u0000" + canonical(input))

    fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().filter { it !in metadata }.sorted().joinToString(",", "{", "}") {
            JSONObject.quote(it) + ":" + canonical(value.opt(it))
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.opt(it)) }
        is String -> JSONObject.quote(value)
        is Number -> JSONObject.numberToString(value)
        is Boolean -> value.toString()
        else -> "null"
    }
    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
