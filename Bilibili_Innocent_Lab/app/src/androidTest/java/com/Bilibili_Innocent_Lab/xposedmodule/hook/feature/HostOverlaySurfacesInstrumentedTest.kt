package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.overlays.HostOverlaySurfaces
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostOverlaySurfacesInstrumentedTest {
    @Test fun staticPanelRemainsOpaqueAfterZeroAlphaEntranceAnimation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var window: Window
            lateinit var root: LinearLayout
            lateinit var surfaces: HostOverlaySurfaces
            val sample = IntArray(2)
            scenario.onActivity { activity ->
                window = activity.window
                val parent = FrameLayout(activity).apply { setBackgroundColor(Color.RED) }
                root = LinearLayout(activity)
                surfaces = HostOverlaySurfaces(root, Color.WHITE, Color.GRAY, false, radiusDp = 0f)
                // 模拟回复树：背景与子控件先绑定，再以属性动画从完全透明进入。
                root.addView(View(activity), LinearLayout.LayoutParams(100, 100))
                parent.addView(root, FrameLayout.LayoutParams(-1, -1))
                activity.setContentView(parent)
                root.alpha = 0f
            }
            try {
                instrumentation.waitForIdleSync()
                val finished = CountDownLatch(1)
                scenario.onActivity {
                    root.getLocationInWindow(sample)
                    sample[0] += 150
                    sample[1] += 150
                    root.animate().alpha(1f).setDuration(160L).withEndAction { finished.countDown() }.start()
                }
                assertTrue("Entrance animation timed out", finished.await(3, TimeUnit.SECONDS))
                instrumentation.waitForIdleSync()
                val bitmap = Bitmap.createBitmap(window.decorView.width, window.decorView.height, Bitmap.Config.ARGB_8888)
                try {
                    val copied = CountDownLatch(1)
                    var result = -1
                    PixelCopy.request(window, bitmap, { result = it; copied.countDown() }, Handler(Looper.getMainLooper()))
                    assertTrue("Window capture timed out", copied.await(3, TimeUnit.SECONDS))
                    assertEquals(PixelCopy.SUCCESS, result)
                    assertEquals("Root background disappeared from the hardware display list", Color.WHITE,
                        bitmap.getPixel(sample[0], sample[1]))
                } finally { bitmap.recycle() }
            } finally { instrumentation.runOnMainSync { surfaces.close() } }
        }
    }
}
