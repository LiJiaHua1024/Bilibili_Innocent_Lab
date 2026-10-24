package com.Bilibili_Innocent_Lab.xposedmodule.lan

import java.net.URI
import org.json.JSONObject

internal data class LanPeer(val id: String, val address: URI)

/** Only opaque random IDs and boot challenges are public. All human names and watch state are encrypted. */
internal class LanPeerProtocol(
    val id: String,
    private val find: (String) -> TrustedLanDevice?,
    private val remember: (TrustedLanDevice) -> Unit,
    private val ownState: () -> ContinuityState?,
    private val received: (String, ContinuityState?) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    val boot = LanCrypto.randomId()
    private data class Invitation(val key: ByteArray, val until: Long)
    private var invitation: Invitation? = null
    private val replay = LanReplayWindow()
    private var failedAttempts = 0
    private var attemptWindow = 0L

    @Synchronized fun invite(): String {
        val key = LanCrypto.key()
        invitation?.key?.fill(0)
        invitation = Invitation(key, elapsed() + 120_000)
        failedAttempts = 0; attemptWindow = elapsed()
        return LanCrypto.encode(key)
    }
    @Synchronized fun cancelInvitation() { invitation?.key?.fill(0); invitation = null }

    fun handle(request: LanRequest): LanResponse {
        if (request.path == "/v1/hello" && request.method == "GET") return json(JSONObject().put("v", 1).put("id", id).put("boot", boot))
        if (request.method != "POST") return LanResponse(404)
        val from = request.headers["x-bil-device"].orEmpty()
        if (!from.matches(Regex("[A-Za-z0-9_-]{22}")) || from == id) return LanResponse(403)
        return when (request.path) {
            "/v1/pair" -> pair(from, request.body)
            "/v1/state" -> state(from, request.body)
            else -> LanResponse(404)
        }
    }
    @Synchronized private fun pair(from: String, packet: String): LanResponse {
        val offer = invitation ?: return LanResponse(403)
        if (elapsed() >= offer.until) { cancelInvitation(); return LanResponse(403) }
        if (elapsed() - attemptWindow >= 60_000) { failedAttempts = 0; attemptWindow = elapsed() }
        if (failedAttempts >= 8) return LanResponse(403)
        failedAttempts++
        val message = runCatching { JSONObject(LanCrypto.open(offer.key, LanCrypto.context("pair-request", from, id, boot), packet)) }
            .getOrNull() ?: return LanResponse(403)
        if (!replay.accept(message, clock())) return LanResponse(403)
        val name = message.optString("name").take(80).ifBlank { "已配对设备" }
        val secret = LanCrypto.derive(offer.key, "bil-lan/trust/1/$from/$id/$boot")
        val reply = JSONObject().put("request", message.getString("request")).put("at", clock()).put("ok", true)
        val encrypted = LanCrypto.seal(offer.key, LanCrypto.context("pair-response", id, from, boot), reply.toString())
        remember(TrustedLanDevice(from, name, secret, clock()))
        cancelInvitation()
        return LanResponse(body = encrypted.toByteArray(Charsets.UTF_8))
    }
    private fun state(from: String, packet: String): LanResponse {
        val peer = find(from) ?: return LanResponse(403)
        val message = runCatching { JSONObject(LanCrypto.open(peer.key, LanCrypto.context("state-request", from, id, boot), packet)) }
            .getOrNull() ?: return LanResponse(403)
        if (!replay.accept(message, clock()) || find(from) !== peer) return LanResponse(403)
        val watching = readState(message)
        received(from, watching)
        val reply = stateEnvelope(message.getString("request"))
        if (find(from) !== peer) return LanResponse(403)
        return LanResponse(body = LanCrypto.seal(peer.key, LanCrypto.context("state-response", id, from, boot), reply.toString()).toByteArray(Charsets.UTF_8))
    }
    fun pairWith(peer: LanPeer, code: String, name: String, http: LanHttpClient): TrustedLanDevice {
        val invitationKey = LanCrypto.invitationKey(code)
        try {
            val hello = hello(peer, http)
            val challenge = hello.getString("boot")
            val request = LanCrypto.randomId()
            val message = JSONObject().put("request", request).put("at", clock()).put("name", name.take(80))
            val response = http.request(peer.address.resolve("/v1/pair"), "POST",
                LanCrypto.seal(invitationKey, LanCrypto.context("pair-request", id, peer.id, challenge), message.toString()),
                mapOf("X-Bil-Device" to id))
            require(response.status == 200) { "pairing-refused" }
            val reply = JSONObject(LanCrypto.open(invitationKey, LanCrypto.context("pair-response", peer.id, id, challenge), response.body))
            require(reply.getString("request") == request && reply.getBoolean("ok"))
            return TrustedLanDevice(peer.id, "接力设备 ${peer.id.take(6)}",
                LanCrypto.derive(invitationKey, "bil-lan/trust/1/$id/${peer.id}/$challenge"), clock()).also(remember)
        } finally { invitationKey.fill(0) }
    }
    fun exchange(peer: LanPeer, trusted: TrustedLanDevice, http: LanHttpClient) {
        require(trusted.id == peer.id && find(peer.id) === trusted)
        val challenge = hello(peer, http).getString("boot")
        val request = LanCrypto.randomId()
        val message = stateEnvelope(request)
        val response = http.request(peer.address.resolve("/v1/state"), "POST",
            LanCrypto.seal(trusted.key, LanCrypto.context("state-request", id, peer.id, challenge), message.toString()),
            mapOf("X-Bil-Device" to id))
        require(response.status == 200) { "peer-unavailable" }
        val reply = JSONObject(LanCrypto.open(trusted.key, LanCrypto.context("state-response", peer.id, id, challenge), response.body))
        require(reply.getString("request") == request && reply.getLong("at") in (clock() - 120_000)..(clock() + 30_000))
        if (find(peer.id) === trusted) received(peer.id, readState(reply))
    }
    private fun readState(envelope: JSONObject): ContinuityState? = envelope.optJSONObject("state")?.let {
        runCatching { ContinuityState.read(it).receivedAt(envelope.getLong("at"), clock()) }.getOrNull()
    }
    private fun stateEnvelope(request: String): JSONObject {
        val state = ownState()
        val at = clock()
        return JSONObject().put("request", request).put("at", at).also { value ->
            state?.takeIf { it.offerAt(at) }?.let { value.put("state", it.json()) }
        }
    }
    private fun hello(peer: LanPeer, http: LanHttpClient): JSONObject {
        LanAddress.url(peer.address.toString())
        val response = http.request(peer.address.resolve("/v1/hello"))
        require(response.status == 200)
        return JSONObject(response.body).also { require(it.getInt("v") == 1 && it.getString("id") == peer.id) }
    }
    private fun json(value: JSONObject) = LanResponse(body = value.toString().toByteArray(Charsets.UTF_8))
}
