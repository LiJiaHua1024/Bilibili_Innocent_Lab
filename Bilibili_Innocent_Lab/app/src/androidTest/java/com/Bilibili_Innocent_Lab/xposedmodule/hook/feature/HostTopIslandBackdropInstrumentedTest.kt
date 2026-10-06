package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.Window
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.material.ModernMaterialPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.SurfaceRole
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 真正的窗口帧：隐藏 dock 后只改变内容，不能靠主动刷新输入层掩盖失效的采样通知。 */
@SdkSuppress(minSdkVersion = 31)
@RunWith(AndroidJUnit4::class)
class HostTopIslandBackdropInstrumentedTest {
    @Test fun gpuCollapsedShellTracksLiveContent() = tracksLiveContent(true)
    @Test fun softwareCollapsedShellTracksLiveContent() = tracksLiveContent(false)

    private fun tracksLiveContent(preferGpu: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var window: Window
            lateinit var content: View
            lateinit var dock: FrameLayout
            lateinit var binding: HostTopIslandBinding
            lateinit var backdrop: HostBottomBarBackdrop
            val sample = IntArray(2)
            scenario.onActivity { activity ->
                window = activity.window
                val parent = FrameLayout(activity)
                content = View(activity).apply { setBackgroundColor(Color.RED) }
                parent.addView(content, FrameLayout.LayoutParams(-1, -1))
                dock = FrameLayout(activity)
                parent.addView(dock, FrameLayout.LayoutParams(600, 120).apply {
                    leftMargin = 40
                    topMargin = 200
                })
                backdrop = HostBottomBarBackdrop(1f, preferGpu)
                dock.background = HostLiquidSurfaceDrawable(Color.WHITE, 60f, 1f,
                    ModernMaterialPolicy.surface(SurfaceRole.FLOATING, false), backdrop = backdrop)
                binding = checkNotNull(HostTopIslandBinding.attach(dock, null, 1f, null, backdrop,
                    Color.MAGENTA, HostTopIslandPageActions(parent, null)))
                HostTopIslandBinding::class.java.getDeclaredField("progress").apply {
                    isAccessible = true
                    setFloat(binding, 1f)
                }
                activity.setContentView(parent)
                backdrop.attach(dock, content)
            }
            try {
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    binding.sync()
                    dock.getLocationInWindow(sample)
                    // 避开正中央箭头，在圆形表面右半侧取样。
                    sample[0] += 325
                    sample[1] += 60
                }
                awaitColor(window, sample, red = true)
                instrumentation.runOnMainSync {
                    HostTopIslandBinding::class.java.getDeclaredField("collapsed").apply {
                        isAccessible = true
                        setBoolean(binding, true)
                    }
                    binding.sync()
                    assertEquals(View.INVISIBLE, dock.visibility)
                    // 状态切换首帧需要画出输入层；之后采样器必须自己通知它刷新。
                    (dock.parent as FrameLayout).getChildAt(2).invalidate()
                }
                awaitColor(window, sample, red = true)
                instrumentation.runOnMainSync {
                    content.setBackgroundColor(Color.BLUE)
                    backdrop.onVisualMovement()
                }
                awaitColor(window, sample, red = false)
            } finally {
                instrumentation.runOnMainSync { backdrop.close() }
            }
        }
    }

    private fun awaitColor(window: Window, sample: IntArray, red: Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5000L
        var pixel = 0
        do {
            val bitmap = Bitmap.createBitmap(window.decorView.width, window.decorView.height,
                Bitmap.Config.ARGB_8888)
            try {
                val latch = CountDownLatch(1)
                var result = -1
                PixelCopy.request(window, bitmap, { result = it; latch.countDown() }, Handler(Looper.getMainLooper()))
                assertTrue("Window capture timed out", latch.await(3, TimeUnit.SECONDS))
                assertEquals(PixelCopy.SUCCESS, result)
                pixel = bitmap.getPixel(sample[0], sample[1])
                val difference = Color.red(pixel) - Color.blue(pixel)
                if (if (red) difference > 40 else difference < -40) return
            } finally { bitmap.recycle() }
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        assertTrue("Collapsed shell did not follow content: red=$red pixel=${Integer.toHexString(pixel)}", false)
    }
}
