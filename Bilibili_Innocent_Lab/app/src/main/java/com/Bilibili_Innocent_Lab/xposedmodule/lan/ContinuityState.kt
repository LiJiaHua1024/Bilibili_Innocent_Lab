package com.Bilibili_Innocent_Lab.xposedmodule.lan

import org.json.JSONObject
import java.util.UUID

/** Only authenticated peers may receive this object. No cookies, media URLs or account tokens. */
internal data class ContinuityState(
    val session: String,
    val bvid: String,
    val cid: Long,
    val part: Int,
    val title: String,
    val positionMs: Long,
    val durationMs: Long,
    val speed: Float,
    val playing: Boolean,
    val capturedAt: Long
) {
    init {
        require(session.length in 1..64 && bvid.matches(Regex("BV[0-9A-Za-z]{10}")))
        require(cid > 0 && part in 1..10_000 && title.length <= 256)
        require(durationMs in 1..604_800_000 && positionMs in 0..durationMs)
        require(speed.isFinite() && speed in .1f..8f && capturedAt > 0)
    }

    fun offerAt(now: Long): Boolean = now - capturedAt in 0..if (playing) 20_000L else 120_000L
    fun positionAt(now: Long): Long = (positionMs + if (playing) {
        ((now - capturedAt).coerceIn(0, 20_000) * speed).toLong()
    } else 0L).coerceIn(0, durationMs)

    /** Translate authenticated source age into the receiver's clock; public discovery provides no clock. */
    fun receivedAt(sentAt: Long, now: Long): ContinuityState? {
        val age = sentAt - capturedAt
        val lifetime = if (playing) 20_000L else 120_000L
        if (age !in 0..lifetime || now <= age) return null
        return copy(capturedAt = now - age)
    }

    fun json(): JSONObject = JSONObject().put("session", session).put("bvid", bvid).put("cid", cid)
        .put("part", part).put("title", title).put("position", positionMs).put("duration", durationMs)
        .put("speed", speed.toDouble()).put("playing", playing).put("at", capturedAt)

    companion object {
        fun read(value: JSONObject) = ContinuityState(value.getString("session"), value.getString("bvid"),
            value.getLong("cid"), value.getInt("part"), value.getString("title"), value.getLong("position"),
            value.getLong("duration"), value.getDouble("speed").toFloat(), value.getBoolean("playing"), value.getLong("at"))
        fun sessionId(): String = UUID.randomUUID().toString()
    }
}

/** Receipt time is monotonic; source timestamps are never used to extend a received offer. */
internal class ContinuityOffers {
    data class Offer(val device: String, val state: ContinuityState, val receivedAt: Long)
    private val offers = LinkedHashMap<String, Offer>()
    private val dismissed = LinkedHashSet<String>()
    @Synchronized fun receive(device: String, state: ContinuityState?, wallNow: Long, elapsed: Long) {
        if (state == null || !state.offerAt(wallNow)) { offers.remove(device); return }
        val previous = offers[device]
        if (previous?.state?.session == state.session && previous.state.capturedAt > state.capturedAt) return
        offers[device] = Offer(device, state, elapsed)
    }
    @Synchronized fun latest(wallNow: Long, elapsed: Long, ownSession: String?): Offer? = offers.values
        .filter { elapsed - it.receivedAt in 0..20_000 && it.state.offerAt(wallNow) &&
            it.state.session != ownSession && "${it.device}/${it.state.session}" !in dismissed }
        .maxByOrNull { it.state.capturedAt }
    @Synchronized fun dismiss(offer: Offer) {
        dismissed += "${offer.device}/${offer.state.session}"
        if (dismissed.size > 128) dismissed.remove(dismissed.first())
    }
    @Synchronized fun revoke(device: String) { offers.remove(device) }
    @Synchronized fun clear() { offers.clear(); dismissed.clear() }
}
