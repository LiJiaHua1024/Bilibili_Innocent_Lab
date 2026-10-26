package com.Bilibili_Innocent_Lab.xposedmodule.lan

import org.junit.Assert.assertEquals
import org.junit.Test

class CastCodecRatePolicyTest {
    @Test fun boundsResourceHintWithoutChangingSourceFrameRate() {
        assertEquals(240f, CastCodecRatePolicy.requested(30, 960.0, 480.0))
        assertEquals(192f, CastCodecRatePolicy.requested(24, 960.0, 480.0))
        assertEquals(60f, CastCodecRatePolicy.requested(30, 60.0, 480.0))
        assertEquals(90f, CastCodecRatePolicy.requested(30, 480.0, 90.0))
    }
    @Test fun missingInvalidOrNonAcceleratedCapabilityOmitsHint() {
        assertEquals(0f, CastCodecRatePolicy.requested(30, null, 240.0))
        assertEquals(0f, CastCodecRatePolicy.requested(30, Double.NaN, 240.0))
        assertEquals(0f, CastCodecRatePolicy.requested(30, 240.0, Double.POSITIVE_INFINITY))
        assertEquals(0f, CastCodecRatePolicy.requested(30, 30.0, 240.0))
        assertEquals(0f, CastCodecRatePolicy.requested(30, -1.0, 240.0))
    }
}
