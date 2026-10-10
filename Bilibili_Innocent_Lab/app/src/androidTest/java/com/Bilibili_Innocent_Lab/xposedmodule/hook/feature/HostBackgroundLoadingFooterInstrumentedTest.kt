package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundController
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundPreset
import com.Bilibili_Innocent_Lab.xposedmodule.test.R as TestR
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostBackgroundLoadingFooterInstrumentedTest {
    private fun footer(context: Context) = FrameLayout(context).apply {
        setBackgroundColor(Color.WHITE)
        addView(TextView(context).apply { id = TestR.id.tv_left; text = "Retry" })
        addView(TextView(context).apply { id = TestR.id.tv_right })
        addView(ProgressBar(context).apply { id = TestR.id.loading; isIndeterminate = true })
    }

    @Test fun loadingFooterKeepsItsSpinnerAndRetryActionAcrossThemeReapplication() {
        val test = InstrumentationRegistry.getInstrumentation()
        test.runOnMainSync {
            val context = test.context
            val list = FrameLayout(context)
            val footer = footer(context)
            list.addView(footer)
            val spinner = footer.getChildAt(2) as ProgressBar
            val animation = spinner.indeterminateDrawable
            val retry = footer.getChildAt(0) as TextView
            var clicked = false
            retry.setOnClickListener { clicked = true }
            val controller = HostBackgroundController(HostBackgroundConfig(HostBackgroundPreset.STARRY), { throw it })
            controller.apply(list, false)
            assertNull(footer.background)
            // 宿主换肤重新设置背景后，下一次绘制前仍应透出壁纸。
            footer.setBackgroundColor(Color.DKGRAY)
            controller.apply(list, true)
            assertNull(footer.background)
            assertSame(animation, spinner.indeterminateDrawable)
            assertTrue(spinner.isIndeterminate)
            assertEquals(View.VISIBLE, spinner.visibility)
            assertEquals("Retry", retry.text.toString())
            retry.performClick()
            assertTrue(clicked)
        }
    }

    @Test fun disabledBackgroundAndUnrelatedProgressRowsKeepTheirSurfaces() {
        val test = InstrumentationRegistry.getInstrumentation()
        test.runOnMainSync {
            val context = test.context
            val list = FrameLayout(context)
            val footer = footer(context)
            val original = footer.background
            list.addView(footer)
            HostBackgroundController(HostBackgroundConfig(), { throw it }).apply(list, false)
            assertSame(original, footer.background)
            val unrelated = footer(context).apply {
                // 同样有进度条，但没有宿主尾项的完整提示结构。
                getChildAt(0).id = View.NO_ID
            }
            val unrelatedSurface = unrelated.background
            list.addView(unrelated)
            val nested = FrameLayout(context).apply { setBackgroundColor(Color.WHITE); addView(footer(context)) }
            val nestedSurface = nested.background
            val nestedFooter = (nested.getChildAt(0) as FrameLayout).background
            list.addView(nested)
            HostBackgroundController(HostBackgroundConfig(HostBackgroundPreset.AURORA), { throw it }).apply(list, false)
            assertNull(footer.background)
            assertSame(unrelatedSurface, unrelated.background)
            assertSame(nestedSurface, nested.background)
            assertSame(nestedFooter, nested.getChildAt(0).background)
        }
    }
}
