package com.Bilibili_Innocent_Lab.xposedmodule.ui.overlay

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class ReplyTopologyBranchGeometryTest {
    @Test fun aBranchTurnsFromTheCurrentRowTopToItsNodeCenter() {
        val curve = ReplyTopologyBranchGeometry()
        assertTrue(curve.update(101f, 113f, 730f, 834f))
        assertEquals(730f, curve.startY, 0f)
        assertEquals(782f, curve.endY, 0f)
        assertEquals(101f, curve.startX, 0f)
        assertEquals(113f, curve.endX, 0f)
    }

    @Test fun scrollingTranslatesTheWholeCurveWithoutChangingItsShape() {
        val curve = ReplyTopologyBranchGeometry()
        curve.update(101f, 113f, 730f, 834f)
        val before = sample(curve)
        curve.update(101f, 113f, 659f, 763f)
        val after = sample(curve)
        before.zip(after).forEach { (first, second) ->
            assertEquals(first.first, second.first, 0.0001f)
            assertEquals(first.second - 71f, second.second, 0.0002f)
        }
    }

    @Test fun curvesStayInsideTheirOwnRowsAndTheirTwoLaneCoordinates() {
        val curve = ReplyTopologyBranchGeometry()
        for ((top, bottom) in listOf(0f to 66f, 400f to 524f, -80f to 40f, 1_000f to 1_002f)) {
            for ((parentX, nodeX) in listOf(14f to 22f, 70f to 70f, 50f to 42f)) {
                assertTrue(curve.update(parentX, nodeX, top, bottom))
                sample(curve).forEach { (x, y) ->
                    assertTrue(x >= min(parentX, nodeX) - 0.001f && x <= max(parentX, nodeX) + 0.001f)
                    assertTrue(y >= top - 0.001f && y <= (top + bottom) * 0.5f + 0.001f)
                }
            }
        }
    }

    @Test fun aCollapsedDeepLaneStaysVerticalAndDoesNotCreateADiagonal() {
        val curve = ReplyTopologyBranchGeometry()
        assertTrue(curve.update(70f, 70f, 400f, 500f))
        sample(curve).forEach { (x, _) -> assertEquals(70f, x, 0.0001f) }
    }

    @Test fun successiveRowsCanReuseOneBufferWithoutKeepingTheOldOrigin() {
        val curve = ReplyTopologyBranchGeometry()
        curve.update(14f, 22f, 100f, 200f)
        curve.update(42f, 50f, 700f, 820f)
        assertEquals(42f, curve.startX, 0f)
        assertEquals(700f, curve.startY, 0f)
        assertEquals(760f, curve.control1Y, 0f)
        assertEquals(700f, curve.control2Y, 0f)
        assertEquals(50f, curve.endX, 0f)
        assertEquals(760f, curve.endY, 0f)
    }

    @Test fun transientInvalidRowBoundsDoNotProduceACurve() {
        val curve = ReplyTopologyBranchGeometry()
        assertFalse(curve.update(14f, 22f, 100f, 100f))
        assertFalse(curve.update(14f, 22f, 200f, 100f))
        assertFalse(curve.update(14f, 22f, Float.NaN, 200f))
        assertFalse(curve.update(Float.POSITIVE_INFINITY, 22f, 100f, 200f))
    }

    private fun sample(curve: ReplyTopologyBranchGeometry): List<Pair<Float, Float>> = (0..20).map { step ->
        val t = step / 20f
        val u = 1f - t
        fun coordinate(start: Float, first: Float, second: Float, end: Float) =
            u * u * u * start + 3f * u * u * t * first + 3f * u * t * t * second + t * t * t * end
        coordinate(curve.startX, curve.control1X, curve.control2X, curve.endX) to
            coordinate(curve.startY, curve.control1Y, curve.control2Y, curve.endY)
    }
}
