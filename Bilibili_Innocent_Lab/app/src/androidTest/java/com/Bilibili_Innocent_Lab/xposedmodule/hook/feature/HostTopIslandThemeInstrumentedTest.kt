package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.material.ModernMaterialPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.SurfaceRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostTopIslandThemeInstrumentedTest {
    @Test fun replacedCollapsedShellStillDrawsAcrossDayNightChanges() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val parent = FrameLayout(context)
            val dock = FrameLayout(context)
            parent.addView(dock, FrameLayout.LayoutParams(300, 64))
            dock.layout(0, 0, 300, 64)
            val binding = checkNotNull(HostTopIslandBinding.attach(dock, null, 1f, null, null,
                0xFFFF6699.toInt(), HostTopIslandPageActions(parent, null)))
            // 模拟弹簧已到收起终点，宿主 dock 隐藏，由输入层代画外壳。
            HostTopIslandBinding::class.java.getDeclaredField("collapsed").apply {
                isAccessible = true
                setBoolean(binding, true)
            }
            HostTopIslandBinding::class.java.getDeclaredField("progress").apply {
                isAccessible = true
                setFloat(binding, 1f)
            }
            val input = parent.getChildAt(1)
            input.layout(0, 0, 300, 80)
            val bitmap = Bitmap.createBitmap(300, 80, Bitmap.Config.ARGB_8888)
            try {
                var previousPixel = 0
                for (dark in listOf(true, false, true)) {
                    val colors = HostChromeColors(dark, 0xFFFF6699.toInt()).palette()
                    val surface = HostLiquidSurfaceDrawable(colors.surface, 32f, 1f,
                        ModernMaterialPolicy.surface(SurfaceRole.FLOATING, dark))
                    dock.background = surface
                    // 隐藏 View 未走 drawBackground，新 Drawable 仍没有绘制边界。
                    surface.setBounds(0, 0, 0, 0)
                    binding.sync()
                    assertEquals(View.INVISIBLE, dock.visibility)
                    assertEquals(300, surface.bounds.width())
                    assertEquals(64, surface.bounds.height())
                    bitmap.eraseColor(0)
                    input.draw(Canvas(bitmap))
                    // 取圆心旁边，避开箭头：必须存在实际玻璃表面，且日夜颜色改变。
                    val pixel = bitmap.getPixel(165, 40)
                    assertTrue(pixel ushr 24 > 0)
                    assertNotEquals(previousPixel, pixel)
                    previousPixel = pixel
                }
            } finally {
                bitmap.recycle()
            }
        }
    }
}
