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

@RunWith(AndroidJUnit4::class)
class HostBackgroundContinuousInstrumentedTest {
    private lateinit var scenario: ActivityScenario<MainActivity>
    // 本机系统会冻结不可见的模块测试进程，保持一个真实前台 Activity。
    @Before fun foreground() { scenario = ActivityScenario.launch(MainActivity::class.java) }
    @After fun close() { if (::scenario.isInitialized) scenario.close() }
    private val presets = HostBackgroundPreset.entries.filter { it != HostBackgroundPreset.OFF && it != HostBackgroundPreset.CUSTOM }

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
