package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidMotionMappingContractTest {
    @Test fun mappingAndMaskShareTheRecordedFullTransform() {
        val renderer = SourceContract.read("ui/skin/liquid/LiquidActivityRenderer.kt")
        assertTrue(renderer.contains("surfaceCoordinates.sourceToTarget(screenTransform, root, surfaceToBackdrop)"))
        assertTrue(renderer.contains("surfaceCoordinates.localToScreen(view, footprint.screenTransform)"))
        assertTrue(renderer.contains("SamplingMatrixMath.equal(entry.value.screenTransform, refreshSurfaceTransform)"))
        val feedback = SourceContract.read("ui/skin/liquid/LiquidFeedbackSuppressor.kt")
        val mask = feedback.after("fun buildSuppressionMask(").before("fun sanitizeRealtimeCapture(")
        assertTrue(mask.contains("surfaceToCapture.setValues(footprint.screenTransform)"))
        assertFalse(mask.contains("Matrix()"))
        assertFalse(mask.contains("Path()"))
    }

    @Test fun edgePullWithoutScrollSuppressesCaptureUntilTheExistingQuietRelease() {
        val renderer = SourceContract.read("ui/skin/liquid/LiquidActivityRenderer.kt")
        val stretch = renderer.after("private fun onStretchDistanceChanged(").before("override fun onTrimMemory(")
        assertTrue(stretch.contains("if (distance > 0f) suppressRealtimeSamplingWhileScrolling()"))
        val settle = renderer.after("private fun onScrollSettleCheck(").before("private fun clearScrollSuppression(")
        assertTrue(settle.contains("stretchOpticalIntensity > 1f"))
        assertTrue(settle.contains("LiquidRealtimeCapturePolicy.WAKE_SETTLE_FRAMES"))
        val viewport = SourceContract.read("ui/skin/liquid/LiquidStretchViewport.kt")
        assertTrue(viewport.contains("if (nativeStretch) super.draw(canvas)"))
        assertTrue(stretch.contains("boundRoot?.rootView?.let(::flushSurfaceRefresh)"))
    }
}
