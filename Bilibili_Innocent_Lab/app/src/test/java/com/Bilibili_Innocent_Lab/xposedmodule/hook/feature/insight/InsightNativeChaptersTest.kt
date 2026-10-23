package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.insight

import insightfixture.Guide
import insightfixture.Point
import insightfixture.Points
import insightfixture.ProgressReply
import org.junit.Assert.*
import org.junit.Test

class InsightNativeChaptersTest {
    private val adapter get() = InsightNativeChapters.resolve(ProgressReply::class.java)!!
    private val generated = InsightCache.Entry(InsightOutput("", listOf(InsightChapter(0, "开始"),
        InsightChapter(300, "展开"), InsightChapter(700, "结束"))), 900, true)
    @Test fun `native chapters use independent builders and preserve unrelated fields`() {
        val original = ProgressReply()
        val updated = adapter.apply(original, generated) as ProgressReply
        assertNotSame(original, updated)
        assertEquals("preserved", updated.unrelated)
        assertEquals(77, updated.videoGuide.unrelated)
        assertEquals(88, updated.videoGuide.videoPoint.unrelated)
        assertTrue(updated.videoGuide.videoPoint.pointPermanent)
        val points = updated.videoGuide.videoPoint.pointsList
        assertEquals(listOf(0L, 300L, 700L), points.map { it.from })
        assertEquals(listOf(300L, 700L, 900L), points.map { it.to })
        assertTrue(points.all { it.type == InsightNativeChapters.CHAPTER_TYPE && it.content.startsWith("AI · ") })
        assertTrue(original.videoGuide.videoPoint.pointsList.isEmpty())
        assertEquals(Point(), Point.getDefaultInstance())
    }
    @Test fun `official chapters are never replaced or appended`() {
        val original = ProgressReply(Guide(Points(listOf(Point(0, 900, "官方", 2)))))
        assertTrue(adapter.hasChapters(original))
        assertSame(original, adapter.apply(original, generated))
    }
    @Test fun `default instance and build failure preserve host reply`() {
        assertSame(ProgressReply.getDefaultInstance(), adapter.apply(ProgressReply.getDefaultInstance(), generated))
        val original = ProgressReply(fail = true)
        assertSame(original, adapter.apply(original, generated))
        assertTrue(original.videoGuide.videoPoint.pointsList.isEmpty())
    }
    @Test fun `invalid chapter cache never reaches native renderer`() {
        val original = ProgressReply()
        assertSame(original, adapter.apply(original, generated.copy(timed = false)))
        assertSame(original, adapter.apply(original, generated.copy(duration = 200)))
        assertSame(original, adapter.apply(original, generated.copy(output = InsightOutput("", listOf(
            InsightChapter(0, "a"), InsightChapter(300, "b"), InsightChapter(200, "c"))))))
    }
    @Test fun `unsupported native ABI is refused`() { assertNull(InsightNativeChapters.resolve(String::class.java)) }
}
