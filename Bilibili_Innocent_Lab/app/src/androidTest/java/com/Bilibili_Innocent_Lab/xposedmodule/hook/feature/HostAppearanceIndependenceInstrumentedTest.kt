package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.bottom.HostBottomBarFxConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.bottom.HostBottomBarFxController
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostTouchGlowBinding
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.top.HostTopBarFxConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.top.HostTopBarFxController
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 真实窗口内验证光晕可见、原版外观及触摸处理保留，不写用户偏好。 */
@RunWith(AndroidJUnit4::class)
class HostAppearanceIndependenceInstrumentedTest {
    @Test fun standaloneBottomGlowKeepsNativeGeometryBackgroundAndClickListeners() {
        verifyStandaloneGlow(top = false)
    }

    @Test fun standaloneTopGlowKeepsNativeGeometryBackgroundAndClickListeners() {
        verifyStandaloneGlow(top = true)
    }

    private fun verifyStandaloneGlow(top: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // 当前测试机拦截测试包后台启动；由同一 MAIN/LAUNCHER Intent 先带到前台。
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.executeShellCommand(
            "am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER " +
                "-f 0x10008000 -n ${context.packageName}/.ui.activity.MainActivity"
        ).let { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var host: FrameLayout
            lateinit var item: FrameLayout
            lateinit var nativeBackground: ColorDrawable
            lateinit var nativeForeground: ColorDrawable
            var clicks = 0
            var touches = 0
            scenario.onActivity { activity ->
                val root = FrameLayout(activity)
                host = FrameLayout(activity).apply {
                    nativeBackground = ColorDrawable(Color.WHITE)
                    background = nativeBackground
                    setPadding(13, 7, 11, 9)
                    elevation = 3f
                }
                val tabs = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
                host.addView(tabs, FrameLayout.LayoutParams(-1, -1))
                item = FrameLayout(activity).apply {
                    nativeForeground = ColorDrawable(0x11000000)
                    foreground = nativeForeground
                    setOnClickListener { clicks++ }
                    setOnTouchListener { _, _ -> touches++; false }
                }
                tabs.addView(item, LinearLayout.LayoutParams(0, -1, 1f))
                item.addView(TextView(activity).apply { text = "推荐" }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
                root.addView(host, FrameLayout.LayoutParams(700, 170).apply { leftMargin = 21; topMargin = 50 })
                activity.setContentView(root)
                if (top) HostTopBarFxController.attach(root, null, HostTopBarFxConfig(liquidGlass = false))
                else HostBottomBarFxController.attach(host,
                    HostBottomBarFxConfig(liquidGlass = false, compact = true, iconOnly = true))
            }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(100L)
            scenario.onActivity {
                assertEquals(700, host.width)
                assertEquals(170, host.height)
                assertEquals(21, host.left)
                assertEquals(50, host.top)
                assertEquals(listOf(13, 7, 11, 9), listOf(host.paddingLeft, host.paddingTop, host.paddingRight, host.paddingBottom))
                assertEquals(3f, host.elevation, 0f)
                assertSame(nativeBackground, host.background)
                assertSame(nativeForeground, item.foreground)
                assertFalse(host.clipToOutline)
                assertEquals(1, host.childCount)
                val before = render(host)
                val now = SystemClock.uptimeMillis()
                val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 350f, 80f, 0)
                try {
                    HostTouchGlowBinding.observeTouch(host, event)
                    assertTrue(host.dispatchTouchEvent(event))
                    val during = render(host)
                    assertFalse("Engine glow must be visible over the native background", before.sameAs(during))
                    event.action = MotionEvent.ACTION_UP
                    HostTouchGlowBinding.observeTouch(host, event)
                    assertTrue(host.dispatchTouchEvent(event))
                    val after = render(host)
                    assertTrue("Release must restore the exact native appearance", before.sameAs(after))
                    during.recycle(); after.recycle()
                    // 取消不能把光晕留在原版栏上。
                    event.action = MotionEvent.ACTION_DOWN
                    HostTouchGlowBinding.observeTouch(host, event)
                    event.action = MotionEvent.ACTION_CANCEL
                    HostTouchGlowBinding.observeTouch(host, event)
                    val canceled = render(host)
                    assertTrue(before.sameAs(canceled))
                    canceled.recycle(); before.recycle()
                } finally { event.recycle() }
            }
            // View 在 UP 后投递 PerformClick，等待主线程执行后再断言原生点击。
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertEquals(1, clicks)
                assertEquals(2, touches)
            }
        }
    }

    @Test fun staleCapsuleOptionsLeaveNativeContentAndGeometryUnchanged() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext.createPackageContext("tv.danmaku.bili", 0)
            val normalId = context.resources.getIdentifier("normal_ll", "id", context.packageName)
            check(normalId != 0)
            for (compact in listOf(false, true)) for (icons in listOf(false, true)) {
                val config = HostBottomBarFxConfig(liquidGlass = false, touchGlow = true, compact = compact, iconOnly = icons)
                val host = FrameLayout(context)
                host.setPadding(7, 11, 13, 17)
                val tabs = LinearLayout(context)
                tabs.setPadding(2, 3, 4, 5)
                host.addView(tabs, FrameLayout.LayoutParams(-1, -1))
                val background = ColorDrawable(Color.WHITE)
                val foreground = ColorDrawable(Color.LTGRAY)
                val item = FrameLayout(context).apply {
                    this.background = background
                    this.foreground = foreground
                    isPressed = true
                }
                tabs.addView(item, LinearLayout.LayoutParams(300, -1))
                val content = LinearLayout(context).apply { id = normalId; orientation = LinearLayout.VERTICAL }
                val label = TextView(context).apply { text = "Home" }
                content.addView(View(context), LinearLayout.LayoutParams(48, 48))
                content.addView(label, LinearLayout.LayoutParams(48, 32))
                item.addView(content, FrameLayout.LayoutParams(-2, -2))
                val height = 170
                host.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                host.layout(0, 0, 300, height)
                val before = render(host)
                val itemBounds = listOf(item.left, item.top, item.right, item.bottom)
                val contentBounds = listOf(content.left, content.top, content.right, content.bottom)
                HostBottomBarFxController.alignTabContent(host, tabs, 1f, 4, config)
                assertSame(background, item.background)
                assertSame(foreground, item.foreground)
                assertTrue(item.isPressed)
                assertEquals(View.VISIBLE, label.visibility)
                assertEquals(height, host.height)
                assertEquals(listOf(2, 3, 4, 5), listOf(tabs.paddingLeft, tabs.paddingTop, tabs.paddingRight, tabs.paddingBottom))
                assertEquals(itemBounds, listOf(item.left, item.top, item.right, item.bottom))
                assertEquals(contentBounds, listOf(content.left, content.top, content.right, content.bottom))
                val after = render(host)
                assertTrue("Disabled capsule options must preserve every native pixel", before.sameAs(after))
                before.recycle(); after.recycle()
            }
        }
    }

    private fun render(view: View): Bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
        view.draw(Canvas(it))
    }
}
