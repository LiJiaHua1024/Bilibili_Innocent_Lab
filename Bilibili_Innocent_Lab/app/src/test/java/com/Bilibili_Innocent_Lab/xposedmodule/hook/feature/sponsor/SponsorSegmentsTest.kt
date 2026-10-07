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
}
