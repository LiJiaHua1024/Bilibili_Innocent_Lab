package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ActivityScenario
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundDrawable
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundPreset
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundScene
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity
import kotlin.math.abs
import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundController

@RunWith(AndroidJUnit4::class)
class HostBackgroundContinuousInstrumentedTest {
    private lateinit var scenario: ActivityScenario<MainActivity>
    // 本机系统会冻结不可见的模块测试进程，保持一个真实前台 Activity。
    @Before fun foreground() { scenario = ActivityScenario.launch(MainActivity::class.java) }
    @After fun close() { if (::scenario.isInitialized) scenario.close() }
    private val presets = HostBackgroundPreset.entries.filter { it != HostBackgroundPreset.OFF && it != HostBackgroundPreset.CUSTOM }

    private class TransientEdgeRecycler(context: Context) : RecyclerView(context) {
        var reportEdge = false
        override fun canScrollVertically(direction: Int) =
            if (reportEdge && direction == -1) false else super.canScrollVertically(direction)
    }

    @Test fun transientEdgeReportsDoNotResetTheWorldDuringFastReverseScrolling() {
        lateinit var list: TransientEdgeRecycler
        val controller = HostBackgroundController(HostBackgroundConfig(HostBackgroundPreset.STARRY), { throw it })
        scenario.onActivity { activity ->
            list = TransientEdgeRecycler(activity).apply {
                layoutManager = LinearLayoutManager(activity)
                adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                    override fun getItemCount() = 200
                    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(
                        View(activity).apply { layoutParams = RecyclerView.LayoutParams(240, 120) }) {}
                    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
                }
            }
            (activity.window.decorView as ViewGroup).addView(list, ViewGroup.LayoutParams(240, 480))
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        fun layout() {
            list.forceLayout()
            list.measure(View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY))
            list.layout(0, 0, 240, 480)
        }
        scenario.onActivity { (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(80, 0); layout() }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        scenario.onActivity {
            val first = (list.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
            assertTrue("Fixture must reach row 80 before simulating a transient edge: first=$first, children=${list.childCount}, size=${list.width}x${list.height}", first >= 80)
            controller.apply(list, false)
            controller.scroll(list, 9600)
            val background = list.background as HostBackgroundDrawable
            list.reportEdge = true // 模拟重新布局时 canScrollVertically 暂时返回 false。
            controller.apply(list, false)
            assertEquals("A transient edge report at row 80 must not erase the scroll history", 9600L, background.scrollOffset)
            list.reportEdge = false
            controller.scroll(list, -400)
            assertEquals("The background must follow rapid upward scrolling", 9200L, background.scrollOffset)
        }
        // 新列表恢复到中途时尚未知道顶部距离，也不能把向上滚动钳制成零。
        val restored = HostBackgroundController(HostBackgroundConfig(HostBackgroundPreset.STARRY), { throw it })
        scenario.onActivity {
            restored.apply(list, false)
            restored.scroll(list, -400)
            assertEquals(-400L, (list.background as HostBackgroundDrawable).scrollOffset)
            (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(0, 0)
            layout()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        scenario.onActivity {
            restored.apply(list, false)
            assertEquals("A stable return to the first row must still align the world origin", 0L,
                (list.background as HostBackgroundDrawable).scrollOffset)
        }
    }

    @Test fun overlappingWindowsFollowContentAndReturnToTheSameWorldCoordinates() {
        for (preset in presets) {
            val drawable = HostBackgroundDrawable(HostBackgroundConfig(preset), cacheEnabled = false)
            drawable.setBounds(0, 0, 240, 480)
            fun frame(offset: Long): Bitmap {
                drawable.scrollOffset = offset
                return Bitmap.createBitmap(240, 480, Bitmap.Config.ARGB_8888).also { drawable.draw(Canvas(it)) }
            }
            val original = frame(0)
            val scrolled = frame(137)
            val distant = frame(3_000_000_137L)
            val returned = frame(0)
            try {
                for (y in 0 until 343) for (x in 0 until 240) {
                    assertEquals("World pixels drifted for $preset at ($x,$y)", original.getPixel(x, y + 137), scrolled.getPixel(x, y))
                }
                assertFalse("A distant viewport must generate new scenery: $preset", scrolled.sameAs(distant))
                assertTrue("Returning must restore the same scene: $preset", original.sameAs(returned))
            } finally { original.recycle(); scrolled.recycle(); distant.recycle(); returned.recycle() }
        }
    }

    @Test fun neighboringTilesShareCloudsStarsAndMeteorHalosAcrossTheirSeam() {
        for (preset in presets) for (band in listOf(0L, 12_500_000L)) {
            val config = HostBackgroundConfig(preset)
            val before = HostBackgroundScene.render(HostBackgroundScene.Key(config, false, 240, band))
            val after = HostBackgroundScene.render(HostBackgroundScene.Key(config, false, 240, band + 1))
            val regenerated = HostBackgroundScene.render(HostBackgroundScene.Key(config, false, 240, band))
            try {
                assertTrue("Regenerating an evicted tile must preserve its pixels", before.sameAs(regenerated))
                for (x in 0 until 240) {
                    for (row in 0..1) for (shift in listOf(16, 8, 0)) {
                        val a = (before.getPixel(x, 240 + row) ushr shift) and 255
                        val b = (after.getPixel(x, row) ushr shift) and 255
                        // 跨界光晕的 Shader 矩阵在不同局部原点混色，允许一个色阶的浮点舍入。
                        assertTrue("Visible seam for $preset/$band/$x/$row: $a vs $b", abs(a - b) <= 1)
                    }
                }
            } finally { before.recycle(); after.recycle(); regenerated.recycle() }
        }
    }

    @Test fun retainedTilesStayBoundedWhenTraversingManyScreens() {
        val scene = HostBackgroundScene(HostBackgroundConfig(HostBackgroundPreset.STARRY)) {}
        val frame = Bitmap.createBitmap(120, 240, Bitmap.Config.ARGB_8888)
        try {
            for (screen in 0..80) {
                scene.draw(Canvas(frame), Rect(0, 0, 120, 240), screen * 237L, false, 255, null, synchronous = true)
                assertTrue("Scroll history must not grow the live tile set", scene.retainedTileCount <= 5)
                assertTrue(scene.isReady)
            }
        } finally { frame.recycle() }
    }
}
