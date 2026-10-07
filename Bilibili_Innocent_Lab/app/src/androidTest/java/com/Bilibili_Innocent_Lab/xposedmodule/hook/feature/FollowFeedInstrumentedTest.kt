package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.content.Context
import android.content.res.Configuration
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lumen.coacervation.engine.host.LumenSurfaceBackend
import com.lumen.coacervation.engine.host.LumenSurfaceMaterial
import com.lumen.coacervation.engine.host.LumenSurfaceSession
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FollowFeedInstrumentedTest {
    private fun main(block: (Context) -> Unit) {
        val test = InstrumentationRegistry.getInstrumentation()
        test.runOnMainSync { block(test.targetContext) }
    }

    private fun pixel(view: View, width: Int = 240, height: Int = 160): Int {
        view.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val result = bitmap.getPixel(width / 2, height / 2)
        bitmap.recycle()
        return result
    }

    @Test fun installedHostRetainsTheAuditedModelAndPainterContracts() = main { context ->
        val host = context.createPackageContext("tv.danmaku.bili",
            Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
        val access = requireNotNull(FollowFeedHostAccess.resolve(host.classLoader))
        val item = host.classLoader.loadClass("com.bilibili.bplus.followinglist.model.q0")
        assertEquals(item, access.moduleClass.getDeclaredMethod("M").returnType)
        assertEquals(Long::class.javaPrimitiveType, item.getDeclaredMethod("f").returnType)
        val recycler = host.classLoader.loadClass("androidx.recyclerview.widget.RecyclerView")
        val painter = host.classLoader.loadClass("gv1.c")
        assertEquals(Void.TYPE, painter.getDeclaredMethod("k", Canvas::class.java, recycler,
            View::class.java, access.moduleClass).returnType)
        assertNotNull(recycler.getDeclaredMethod("dispatchChildAttached", View::class.java))
    }

    @Test fun realStaticPixelsAreOpaqueAndHaveNoCaptureBackend() = main { context ->
        val palette = FollowFeedStyle.palette(HostChromeColors(false, Color.BLUE))
        LumenSurfaceSession(context, palette).use { session ->
            val first = View(context)
            val second = View(context)
            val a = session.bind(first, FollowFeedStyle.card())
            val b = session.bind(second, FollowFeedStyle.card())
            assertNotSame(first.background, second.background)
            assertEquals(255, Color.alpha(pixel(first)))
            assertEquals(255, Color.alpha(pixel(second, 480, 320)))
            assertEquals(LumenSurfaceBackend.STATIC, a.diagnostics()!!.backend)
            assertEquals(LumenSurfaceMaterial.STATIC, b.diagnostics()!!.effectiveMaterial)
            assertEquals(0L, session.diagnostics().contentRecordings)
            assertEquals(0L, session.diagnostics().gpuDraws)
            assertEquals(0L, session.diagnostics().softwareDraws)
            a.close(); b.close()
            assertEquals(0, session.diagnostics().attachedSurfaces)
        }
    }

    @Test fun repeatedBindingAndExternalBackgroundOwnershipRemainBounded() = main { context ->
        val palette = FollowFeedStyle.palette(HostChromeColors(false, Color.BLUE))
        val view = View(context)
        val original = ColorDrawable(Color.RED)
        view.background = original
        LumenSurfaceSession(context, palette).use { session ->
            repeat(100) {
                val a = session.bind(view, FollowFeedStyle.card())
                val b = session.bind(view, FollowFeedStyle.card())
                assertNull(a.diagnostics())
                assertEquals(1, session.diagnostics().attachedSurfaces)
                pixel(view)
                b.close()
                assertEquals(0, session.diagnostics().attachedSurfaces)
                assertSame(original, view.background)
                session.pause(); session.resume()
            }
            val binding = session.bind(view, FollowFeedStyle.card())
            val later = ColorDrawable(Color.GREEN)
            view.background = later
            binding.close()
            assertSame(later, view.background)
            assertEquals(0L, session.diagnostics().contentRecordings)
        }
    }

    @Test fun paletteUpdateChangesDisplayedAndReboundSurfacesWithText() = main { context ->
        val light = FollowFeedStyle.palette(HostChromeColors(false, Color.BLUE))
        val dark = FollowFeedStyle.palette(HostChromeColors(true, Color.BLUE))
        val view = View(context)
        val title = TextView(context).apply { text = "长标题与文字" }
        val edits = FollowFeedViewEdits()
        LumenSurfaceSession(context, light).use { session ->
            var binding = session.bind(view, FollowFeedStyle.card())
            edits.text(title, light.textPrimary, 16f)
            val before = pixel(view)
            session.updatePalette(dark)
            edits.text(title, dark.textPrimary, 16f)
            val after = pixel(view)
            assertNotEquals(before, after)
            assertEquals(255, Color.alpha(after))
            assertEquals(dark.textPrimary, title.currentTextColor)
            binding.close()
            binding = session.bind(view, FollowFeedStyle.card())
            assertEquals(after, pixel(view))
            assertEquals(1L, session.diagnostics().paletteGeneration)
            binding.close(); edits.restore()
        }
    }

    /** 以真实宿主资源建立最小 TabLayout 契约，业务点击仍属于原 TabView。 */
    class NativeTabs(context: Context, count: Int = 2) : LinearLayout(context) {
        var actual = 1
        var clicks = 0
        fun getSelectedTabPosition() = actual
        init {
            val strip = LinearLayout(context)
            addView(strip)
            repeat(count) { index ->
                val tab = FrameLayout(context)
                tab.addView(TextView(context).apply {
                    id = context.resources.getIdentifier("dy_tv_title", "id", context.packageName)
                    check(id != 0)
                    text = if (index == 0) "全部" else "视频"
                })
                tab.setOnClickListener { clicks++; actual = index }
                strip.addView(tab)
            }
        }
    }

    @Test fun selectionPreservesBusinessClickRestorationAndUnknownLayouts() = main { context ->
        val host = context.createPackageContext("tv.danmaku.bili", 0)
        val palette = FollowFeedStyle.palette(HostChromeColors(false, Color.BLUE))
        LumenSurfaceSession(host, palette).use { session ->
            val parent = FrameLayout(host)
            val tabs = NativeTabs(host)
            parent.addView(tabs, FrameLayout.LayoutParams(-1, 100))
            repeat(5) {
                val selection = requireNotNull(FollowFeedSelection.create(tabs, session, palette))
                assertEquals(tabs.actual, selection.choice.selectedIndex)
                val before = tabs.clicks
                selection.sync(false)
                assertEquals(before, tabs.clicks)
                selection.choice.options.getChildAt(1 - tabs.actual).performClick()
                assertEquals(before + 1, tabs.clicks)
                assertEquals(tabs.actual, selection.choice.selectedIndex)
                tabs.actual = 1 - tabs.actual // 宿主恢复或滑动切页，不应重复请求业务。
                selection.sync(false)
                assertEquals(tabs.actual, selection.choice.selectedIndex)
                assertEquals(before + 1, tabs.clicks)
                selection.updatePalette(FollowFeedStyle.palette(HostChromeColors(true, Color.BLUE)))
                selection.close()
                assertSame(parent, tabs.parent)
                assertEquals(100, tabs.layoutParams.height)
                assertEquals(1f, tabs.alpha, 0f)
                assertEquals(0, session.diagnostics().attachedSurfaces)
            }
            val unsupported = NativeTabs(host, 3)
            parent.addView(unsupported)
            assertNull(FollowFeedSelection.create(unsupported, session, palette))
            assertSame(parent, unsupported.parent)
            assertEquals(1f, unsupported.alpha, 0f)
        }
    }

    @Test fun selectionFitsNarrowLargeFontLandscapeAndTabletWidths() = main { context ->
        val host = context.createPackageContext("tv.danmaku.bili", 0)
        for (scale in listOf(1f, 2f)) for (widthDp in listOf(280, 640, 1024)) {
            val config = Configuration(host.resources.configuration).apply { fontScale = scale }
            val configured = host.createConfigurationContext(config)
            val palette = FollowFeedStyle.palette(HostChromeColors(false, Color.BLUE))
            LumenSurfaceSession(configured, palette).use { session ->
                val parent = FrameLayout(configured)
                val tabs = NativeTabs(configured)
                parent.addView(tabs, FrameLayout.LayoutParams(-1, 100))
                val selection = requireNotNull(FollowFeedSelection.create(tabs, session, palette))
                val width = (widthDp * configured.resources.displayMetrics.density).toInt()
                parent.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST))
                parent.layout(0, 0, width, parent.measuredHeight)
                val a = selection.choice.options.getChildAt(0) as TextView
                val b = selection.choice.options.getChildAt(1) as TextView
                assertTrue(a.width > 0 && a.right <= b.left)
                assertTrue(a.height >= 48 * configured.resources.displayMetrics.density)
                assertTrue(a.height >= a.lineHeight)
                selection.close()
            }
        }
    }

    @Test fun nicknameAndLayoutEditsRestoreWithoutOverwritingLaterHostChanges() = main { context ->
        val name = TextView(context).apply {
            text = "一个很长的完整昵称"
            maxLines = 2
            setTextColor(Color.RED)
            layoutParams = FrameLayout.LayoutParams(180, 90)
            setPadding(7, 8, 9, 10)
        }
        val original = ColorDrawable(Color.BLUE)
        name.background = original
        val edits = FollowFeedViewEdits()
        repeat(5) {
            edits.background(name, null)
            edits.text(name, Color.GRAY, singleLine = true)
            edits.size(name, 200, 100)
            assertEquals(1, name.maxLines)
            assertEquals(name.text, name.contentDescription)
            assertEquals(7, name.paddingLeft)
            edits.restore()
            assertEquals(2, name.maxLines)
            assertEquals(180, name.layoutParams.width)
            assertSame(original, name.background)
        }
        edits.background(name, null)
        edits.text(name, Color.GRAY)
        val later = ColorDrawable(Color.GREEN)
        name.background = later
        name.setTextColor(Color.MAGENTA)
        edits.restore()
        assertSame(later, name.background)
        assertEquals(Color.MAGENTA, name.currentTextColor)
    }

    @Test fun frequentMoreNativeSelectorUsesPageColorAndRetainsClickAndRestoration() = main { context ->
        val host = context.createPackageContext("tv.danmaku.bili", 0)
        val selectorId = host.resources.getIdentifier("bg_card_selector", "drawable", host.packageName)
        check(selectorId != 0)
        val more = LinearLayout(host).apply {
            background = host.getDrawable(selectorId)
            setPadding(7, 8, 9, 10)
        }
        val original = requireNotNull(more.background)
        val untouched = requireNotNull(original.constantState).newDrawable(host.resources)
        val sibling = View(host).apply { background = untouched }
        val siblingColor = pixel(sibling)
        var clicks = 0
        more.setOnClickListener { clicks++ }
        val edits = FollowFeedViewEdits()
        for (dark in listOf(false, true, false)) {
            val palette = FollowFeedStyle.palette(HostChromeColors(dark, Color.BLUE))
            edits.backgroundTint(more, ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf()),
                intArrayOf(palette.surfaceVariant, palette.background)
            ))
            assertNotSame(original, more.background)
            assertEquals(palette.background, pixel(more))
            more.isPressed = true
            assertEquals(palette.surfaceVariant, pixel(more))
            more.isPressed = false
            assertEquals(palette.background, pixel(more))
            more.performClick()
            assertEquals(siblingColor, pixel(sibling)) // tint 不能污染宿主共享 constantState。
            assertEquals(7, more.paddingLeft)
            edits.restore()
            assertSame(original, more.background)
        }
        assertEquals(3, clicks)
        edits.backgroundTint(more, ColorStateList.valueOf(Color.BLUE))
        val later = ColorDrawable(Color.GREEN)
        more.background = later
        edits.restore()
        assertSame(later, more.background)
    }
}
