package com.Bilibili_Innocent_Lab.xposedmodule.ui.overlay

import com.Bilibili_Innocent_Lab.xposedmodule.contract.SourceContract
import com.Bilibili_Innocent_Lab.xposedmodule.contract.after
import com.Bilibili_Innocent_Lab.xposedmodule.contract.before
import org.junit.Assert.*
import org.junit.Test

class ReplyTopologyTreeExplorerContractTest {
    @Test fun compactRowsAndTracksShareOneBudgetAndNeverPaintOverText() {
        val adapter = SourceContract.read("ui/overlay/ReplyTopologyWorkflowAdapter.kt")
        assertTrue(adapter.contains("ReplyTopologyTrackLayout.resolve(w.toFloat(), density, resources.configuration.fontScale)"))
        assertTrue(adapter.contains("setPadding(layout.trackWidth.roundToInt()"))
        val draw = adapter.after("override fun onDrawOver").before("private fun laneX")
        assertTrue(draw.contains("canvas.clipRect(0f, 0f, layout.trackWidth"))
        assertTrue(draw.contains("canvas.restoreToCount(saved)"))
        assertTrue(draw.contains("trackLayout.width != parent.width.toFloat()"))
        assertTrue(adapter.contains("strings.depthLabel(trueDepth)"))
    }

    @Test fun fullTreeIsAvailableWhenDepthIsCompressedAndPathHasItsOwnAction() {
        val panel = SourceContract.read("ui/overlay/ReplyTopologyPanelView.kt")
        assertTrue(panel.contains("workflowAdapter.hasCompressedDepth(layout.maxLane)"))
        assertTrue(panel.contains("showTreeExplorer(rpid, path = true)"))
        assertTrue(panel.contains("showTreeExplorer(workflowAdapter.selectedNode(), path = false)"))
        val adapter = SourceContract.read("ui/overlay/ReplyTopologyWorkflowAdapter.kt")
        assertTrue(adapter.contains("row.setOnPathRequested"))
        assertTrue(adapter.contains("row.setOnClickListener"))
        assertTrue(adapter.contains("row.setOnMessageLongPress"))
        assertTrue(adapter.contains("text = strings.viewPath"))
        assertTrue(panel.contains("treeExplorer?.submit(snapshot.graph"))
    }

    @Test fun fullTreeUsesVirtualCanvasAndOriginalGraphDepthsWithoutMassiveViews() {
        val layout = SourceContract.read("runtime/replytopology/ReplyTopologyTreeLayout.kt")
        assertTrue(layout.contains("graph.depths[index] - baseDepth"))
        assertFalse(layout.contains("coerceAtMost(7)"))
        val canvas = SourceContract.read("ui/overlay/ReplyTopologyTreeCanvas.kt")
        assertTrue(canvas.contains("private val viewport = ReplyTopologyTreeViewport()"))
        assertTrue(canvas.contains("value.visibleRows("))
        assertTrue(canvas.contains("LruCache<Long, TextBlock>(96)"))
        val draw = canvas.after("private fun drawScene").before("private fun textBlock")
        assertFalse(draw.contains("Bitmap.createBitmap"))
        assertFalse(draw.contains("TextView("))
        assertTrue(draw.contains("cardWidth * zoom < 24.0"))
        assertTrue(canvas.contains("ScaleGestureDetector"))
        assertTrue(canvas.contains("viewport.pan(-distanceX.toDouble(), -distanceY.toDouble())"))
    }

    @Test fun callbacksAndGestureQueuesAreReleasedWhenTheWindowCloses() {
        val panel = SourceContract.read("ui/overlay/ReplyTopologyPanelView.kt")
        assertTrue(panel.after("fun playExit").before("fun submit").contains("closeTreeExplorer()"))
        assertTrue(panel.after("fun releaseResources").before("override fun onDetachedFromWindow").contains("closeTreeExplorer()"))
        val explorer = SourceContract.read("ui/overlay/ReplyTopologyTreeExplorer.kt")
        assertTrue(explorer.contains("tree.release()"))
        assertTrue(explorer.contains("controls.forEach { it.setOnClickListener(null) }"))
        assertTrue(explorer.contains("PredictiveBackApi33.unregister(dispatcher, callback)"))
        assertFalse(explorer.contains("android.window."))
        val canvas = SourceContract.read("ui/overlay/ReplyTopologyTreeCanvas.kt")
        assertTrue(canvas.contains("removeCallbacks(flingFrame)"))
        assertTrue(canvas.contains("MotionEvent.ACTION_CANCEL"))
        assertTrue(canvas.contains("textCache.evictAll()"))
        assertTrue(canvas.contains("HostThreadGuard.runnable(\"reply_topology.tree_fling\")"))
    }

    @Test fun resizingRecomputesSizeBeforePositionAndFullViewUsesTheSourceWindow() {
        val panel = SourceContract.read("ui/overlay/ReplyTopologyPanelView.kt")
        val listener = panel.after("private val parentLayoutListener").before("init {")
        assertTrue(listener.contains("updateDimensionsToParent()"))
        val resize = panel.after("private fun updateDimensionsToParent").before("private fun createKeywordRow")
        assertTrue(resize.contains("params.width = newWidth"))
        assertTrue(resize.contains("expandedHeightPx = newHeight"))
        assertTrue(resize.contains("compactAnimator = null"))
        val show = panel.after("private fun showTreeExplorer").before("private fun closeTreeExplorer")
        assertTrue(show.contains("it === this.parent && it.windowToken == windowToken"))
        assertTrue(show.contains("parent.addView(viewer, params)"))
        assertFalse(show.contains("SYSTEM_ALERT_WINDOW"))
    }
}
