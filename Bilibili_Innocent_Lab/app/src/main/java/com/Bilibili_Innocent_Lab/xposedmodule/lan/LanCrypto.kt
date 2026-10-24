package com.Bilibili_Innocent_Lab.xposedmodule.lan

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/** 256-bit one-time invitation, not a brute-forceable six digit password. Never log or back up keys. */
internal object LanCrypto {
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
    fun randomId(): String = encode(ByteArray(16).also(random::nextBytes))
    fun key(): ByteArray = ByteArray(32).also(random::nextBytes)
    fun encode(value: ByteArray): String = encoder.encodeToString(value)
    fun decode(value: String): ByteArray { require(value.length <= 24_000); return decoder.decode(value) }
    fun invitation(): String = encode(key())
    fun invitationKey(code: String): ByteArray = decode(code.trim()).also { require(it.size == 32) }
    fun derive(key: ByteArray, context: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(context.toByteArray(Charsets.UTF_8))
    }
    fun seal(key: ByteArray, context: String, plain: String): String {
        require(key.size == 32 && plain.toByteArray().size <= 12_000)
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(derive(key, context), "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(context.toByteArray(Charsets.UTF_8))
        return encode(nonce + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }
    fun open(key: ByteArray, context: String, packet: String): String {
        val bytes = decode(packet)
        require(key.size == 32 && bytes.size in 28..12_028)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(derive(key, context), "AES"), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(context.toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(bytes, 12, bytes.size - 12), Charsets.UTF_8)
    }
    fun context(operation: String, from: String, to: String, boot: String): String {
        require(listOf(from, to, boot).all { it.matches(Regex("[A-Za-z0-9_-]{22}")) })
        require(operation in setOf("pair-request", "pair-response", "state-request", "state-response"))
        return "bil-lan/1/$operation/$from/$to/$boot"
    }
}

/** Boot-bound encrypted envelopes, bounded replay window, no dependence on a persisted counter. */
internal class LanReplayWindow(private val capacity: Int = 2048) {
    private val seen = LinkedHashMap<String, Long>()
    private var highWater = Long.MIN_VALUE
    @Synchronized fun accept(message: JSONObject, now: Long): Boolean {
        // A clock rollback must not make an evicted request valid again. Larger jumps fail closed
        // until the clock catches up or a new LAN session supplies a new boot challenge.
        if (highWater != Long.MIN_VALUE && now < highWater - 30_000) return false
        highWater = maxOf(highWater, now)
        val at = message.optLong("at", -1)
        val id = message.optString("request")
        if (at < now - 120_000 || at > now + 30_000 || !id.matches(Regex("[A-Za-z0-9_-]{22}"))) return false
        seen.entries.removeAll { it.value < highWater - 150_000 }
        if (id in seen || seen.size >= capacity) return false
        seen[id] = at
        return true
    }
}
