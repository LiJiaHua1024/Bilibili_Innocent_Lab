package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor

import org.json.JSONArray
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.roundToLong

internal data class SponsorVideoId(val bvid: String, val cid: Long) {
    init { require(BVID.matches(bvid) && cid > 0) }
    val hashPrefix: String get() = MessageDigest.getInstance("SHA-256")
        .digest(bvid.toByteArray(Charsets.UTF_8)).take(2).joinToString("") { "%02x".format(it) }

    companion object { private val BVID = Regex("BV[0-9A-Za-z]{10}") }
}

internal data class SponsorSegment(val uuid: String, val startMs: Long, val endMs: Long, val declaredDurationMs: Long) {
    fun fits(durationMs: Long): Boolean = durationMs in 1..Int.MAX_VALUE.toLong() &&
        startMs >= 0 && startMs < endMs && endMs <= durationMs &&
        (declaredDurationMs == 0L || abs(declaredDurationMs - durationMs) <= 2_000L)
}

internal sealed interface SponsorFetchResult {
    data class Available(val segments: List<SponsorSegment>) : SponsorFetchResult
    data class Unavailable(val reason: String) : SponsorFetchResult
}

/** 只接受普通商单 skip；CID 不匹配时不使用时长兜底，不收集描述或投稿身份。 */
internal object SponsorSegmentParser {
    private val UUID = Regex("[A-Za-z0-9_-]{1,128}")
    fun parse(body: String, video: SponsorVideoId): List<SponsorSegment> {
        val buckets = JSONArray(body)
        require(buckets.length() <= 256) { "response-budget" }
        val matches = (0 until buckets.length()).mapNotNull { buckets.optJSONObject(it) }
            .filter { it.optString("videoID") == video.bvid }
        require(matches.size <= 1) { "duplicate-video" }
        val array = matches.singleOrNull()?.getJSONArray("segments") ?: return emptyList()
        require(array.length() <= 512) { "segment-budget" }
        val result = LinkedHashMap<String, SponsorSegment>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            if (item.optString("category") != "sponsor" || item.optString("actionType") != "skip" ||
                item.opt("cid")?.toString()?.toLongOrNull() != video.cid) continue
            val uuid = item.optString("UUID").takeIf(UUID::matches) ?: continue
            val range = item.optJSONArray("segment")?.takeIf { it.length() == 2 } ?: continue
            val start = millis(range.opt(0)) ?: continue
            val end = millis(range.opt(1)) ?: continue
            val duration = millis(item.opt("videoDuration") ?: 0) ?: continue
            if (start >= end || (duration > 0 && end > duration + 2_000)) continue
            val segment = SponsorSegment(uuid, start, end, duration)
            val previous = result.putIfAbsent(uuid, segment)
            require(previous == null || previous == segment) { "conflicting-segment" }
        }
        return result.values.sortedBy { it.startMs }
    }

    private fun millis(value: Any?): Long? {
        val seconds = (value as? Number)?.toDouble() ?: return null
        if (!seconds.isFinite() || seconds < 0 || seconds > Int.MAX_VALUE / 1000.0) return null
        return (seconds * 1_000).roundToLong().takeIf { it <= Int.MAX_VALUE }
    }
}
