package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleSkinPositionContractTest {
    @Test fun rowTravelAndEndpointRestorationNotifyOnlyAfterWritingTheFrame() {
        val controller = SourceContract.read("ui/activity/BubbleMotionController.kt")
        val frame = controller.after("private fun apply(").before("private fun finish(")
        assertTrue(frame.indexOf("layer.applyFrame(clamped, entryShape)") < frame.indexOf("onContentMoved()"))
        val settle = controller.after("private fun settleExpanded()").before("fun beginPredictiveBack(")
        assertTrue(settle.indexOf("layer.settleExpanded()") < settle.indexOf("onContentMoved()"))
        val presenter = SourceContract.read("ui/activity/MainActivity.kt")
            .after("val bubbleController = if (bubbleLayer != null)").before("val titleMotion =")
        assertTrue(presenter.contains("onContentMoved = { notifyPreparedSkinPositionChanged() }"))
    }

    @Test fun unnotifiedWindowPanIsDetectedBeforeTheSurfaceBatchGate() {
        val renderer = SourceContract.read("ui/skin/liquid/LiquidActivityRenderer.kt")
        val window = renderer.after("private fun registerRefreshWindow(").before("private fun removeRefreshWindow(")
        assertTrue(window.contains("surfaceCoordinates.localToScreen(root, refreshRootTransform)"))
        assertTrue(window.contains("state.position.update(refreshRootTransform)"))
        assertTrue(window.indexOf("state.position.update(refreshRootTransform)") < window.indexOf("flushSurfaceRefresh(root)"))
    }
}
