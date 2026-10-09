package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SponsorSegmentsTest {
    private val id = SponsorVideoId("BV14741127BN", 168885122)
    private fun segment(uuid: String = "abc") = JSONObject().put("UUID", uuid).put("cid", id.cid.toString())
        .put("category", "sponsor").put("actionType", "skip").put("segment", JSONArray(listOf(10.25, 20.5)))
        .put("videoDuration", 100)
    private fun payload(vararg items: JSONObject) = JSONArray().put(JSONObject().put("videoID", id.bvid)
        .put("segments", JSONArray(items.toList()))).toString()

    @Test fun prefixUsesTheBvidOnlyAndTheKnownPublicProtocolVector() {
        assertEquals("5759", id.hashPrefix)
        assertEquals("https://www.bsbsb.top/api/skipSegments/5759", SponsorBlockClient.endpoint(id))
        assertEquals(id.hashPrefix, id.copy(cid = 1).hashPrefix)
        assertThrows(IllegalArgumentException::class.java) { SponsorVideoId("https://evil.invalid", 1) }
    }
    @Test fun exactVideoPartAndActionAreRequired() {
        val good = segment()
        val body = payload(good, segment("other-part").put("cid", "1"), segment("point").put("actionType", "poi"),
            segment("intro").put("category", "intro"))
        assertEquals(listOf(SponsorSegment("abc", 10_250, 20_500, 100_000)), SponsorSegmentParser.parse(body, id))
        assertEquals(SponsorParseSummary(4, 1, 1, 1, 0), SponsorSegmentParser.parseDetailed(body, id).summary)
        assertTrue(SponsorSegmentParser.parse(body, id.copy(bvid = "BV1Q8P7z8Exw")).isEmpty())
    }
    @Test fun invalidRangesAndDuplicateContradictionsCannotBecomeSkips() {
        for (range in listOf(listOf(-1, 5), listOf(5, 4), listOf(1), listOf(1, 999_999_999))) {
            assertTrue(SponsorSegmentParser.parse(payload(segment().put("segment", JSONArray(range))), id).isEmpty())
        }
        assertThrows(IllegalArgumentException::class.java) {
            SponsorSegmentParser.parse(payload(segment(), segment().put("segment", JSONArray(listOf(11, 22)))), id)
        }
    }
    @Test fun runtimeDurationChecksRejectEditedMediaAndUnknownPlayerDuration() {
        val item = SponsorSegment("id", 10_000, 20_000, 100_000)
        assertTrue(item.fits(100_000)); assertTrue(item.fits(102_000)); assertFalse(item.fits(103_000))
        assertFalse(item.fits(0)); assertFalse(item.copy(endMs = 110_000).fits(100_000))
    }

    @Test fun malformedRangesAndMissingCidAreReportedWithoutRelaxingPartChecks() {
        val missing = segment("missing").apply { remove("cid") }
        val parsed = SponsorSegmentParser.parseDetailed(payload(missing,
            segment("invalid").put("segment", JSONArray(listOf(20, 10)))), id)
        assertTrue(parsed.segments.isEmpty())
        assertEquals(SponsorParseSummary(2, 0, 0, 1, 1), parsed.summary)
    }

    @Test fun reportedVideoWithLargeCidAcceptsItsActualSponsorAndProducesTheExpectedJump() {
        // 2026-10-09 反馈视频的公开接口向量，离线验证解析与跳转策略，不依赖网络。
        val video = SponsorVideoId("BV1FnhC6qEck", 42_072_543_108L)
        val body = """[{"videoID":"BV1FnhC6qEck","segments":[{
            "UUID":"f64c944a842c28eb2df0b60ae108285d74aa4b4abe6791d178abf85f4f1ca70c7",
            "cid":"42072543108","category":"sponsor","actionType":"skip",
            "segment":[450.415,509.762],"videoDuration":654}]}]"""
        val parsed = SponsorSegmentParser.parseDetailed(body, video)
        assertEquals("de1d", video.hashPrefix)
        assertEquals(SponsorParseSummary(1, 0, 0, 0, 0), parsed.summary)
        assertEquals(1, parsed.segments.size); assertTrue(parsed.segments.single().fits(654_000))
        val policy = SponsorPlaybackPolicy(true).apply { load(parsed.segments); progress(450_415, 654_000, 0) }
        assertEquals(509_762L, policy.request(true, 0)?.targetMs)
        assertTrue(policy.confirm(509_762, 1)); assertTrue(policy.canUndo(2))
    }
}
