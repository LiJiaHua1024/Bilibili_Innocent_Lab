package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeColors
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostSurfaceScope
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostSurfaceStyle
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostSurfaceScopeInstrumentedTest {
    @Test fun restoresOwnedBackgroundAndRebindsAfterDetachWithoutLosingHostPadding() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val parent = FrameLayout(activity)
                val content = View(activity)
                val surface = View(activity)
                val original = ColorDrawable(Color.RED)
                surface.background = original
                surface.setPadding(3, 5, 7, 11)
                parent.addView(content, FrameLayout.LayoutParams(-1, -1))
                parent.addView(surface, FrameLayout.LayoutParams(300, 100))
                activity.setContentView(parent)
                val colors = HostChromeColors(false, Color.MAGENTA)
                val scope = HostSurfaceScope(surface, colors)
                try {
                    scope.surface(surface, HostSurfaceStyle.selection(false))
                    scope.attach(surface, content)
                    assertTrue(scope.owns(surface))
                    assertSame(surface, surface.background.callback)
                    assertEquals(listOf(3, 5, 7, 11), listOf(surface.paddingLeft, surface.paddingTop,
                        surface.paddingRight, surface.paddingBottom))
                    assertEquals(1, scope.diagnostics()?.attachedSurfaces)
                    scope.detach()
                    assertSame(original, surface.background)
                    assertNull(scope.diagnostics())
                    scope.attach(surface, content)
                    assertTrue(scope.owns(surface))
                    scope.updatePalette(HostChromeColors(true, Color.CYAN))
                    scope.revalidate(content)
                    assertTrue(scope.owns(surface))
                    // 宿主主动替换背景后，恢复路径不能把这个新对象覆盖为旧背景。
                    val replacement = ColorDrawable(Color.BLUE)
                    surface.background = replacement
                    scope.revalidate(content)
                    assertTrue(scope.owns(surface))
                    scope.close()
                    assertSame(replacement, surface.background)
                    scope.attach(surface, content)
                    assertNull(scope.diagnostics())
                } finally { scope.close() }
            }
        }
    }
}
