package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeColors
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostSurfaceScope
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostSurfaceStyle
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.top.HostTopIslandBinding
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.top.HostTopIslandPageActions
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostTopIslandThemeInstrumentedTest {
    @Test fun collapsedShellHasItsOwnBackgroundAndFollowsDayNightPalette() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val parent = FrameLayout(instrumentation.targetContext)
            val dock = FrameLayout(parent.context)
            parent.addView(dock, FrameLayout.LayoutParams(300, 64))
            dock.layout(0, 0, 300, 64)
            val scope = HostSurfaceScope(dock, HostChromeColors(false, 0xFFFF6699.toInt()))
            scope.surface(dock, HostSurfaceStyle.floating(false, 32f))
            val binding = checkNotNull(HostTopIslandBinding.attach(dock, null, 1f, null, scope,
                0xFFFF6699.toInt(), HostTopIslandPageActions(parent, null)))
            for (name in listOf("collapsed", "progress")) {
                HostTopIslandBinding::class.java.getDeclaredField(name).apply {
                    isAccessible = true
                    if (name == "collapsed") setBoolean(binding, true) else setFloat(binding, 1f)
                }
            }
            val shell = parent.getChildAt(1)
            shell.layout(0, 0, 300, 64)
            parent.getChildAt(2).layout(0, 0, 300, 80)
            val bitmap = Bitmap.createBitmap(300, 64, Bitmap.Config.ARGB_8888)
            try {
                var previous = 0
                for (dark in listOf(true, false, true)) {
                    scope.updatePalette(HostChromeColors(dark, 0xFFFF6699.toInt()))
                    binding.sync()
                    assertEquals(View.INVISIBLE, dock.visibility)
                    assertNotSame(dock.background, shell.background)
                    assertSame(shell, shell.background.callback)
                    bitmap.eraseColor(0)
                    shell.draw(Canvas(bitmap))
                    val pixel = bitmap.getPixel(165, 32)
                    assertTrue(pixel ushr 24 > 0)
                    assertNotEquals(previous, pixel)
                    previous = pixel
                }
            } finally { bitmap.recycle(); scope.close() }
        }
    }
}
