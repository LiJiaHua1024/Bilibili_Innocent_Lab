package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import com.Bilibili_Innocent_Lab.xposedmodule.R
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeaturePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.settings.modulePreferences
import com.Bilibili_Innocent_Lab.xposedmodule.ui.view.MaterialSwitch

@RunWith(AndroidJUnit4::class)
class SettingsPageGestureInstrumentedTest {
    @Test fun dependentAppearanceRowsFollowTheirMastersWithoutChangingSavedValues() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.modulePreferences()
        val keys = listOf(FeaturePreferences.HOST_VIDEO_CARDS, FeaturePreferences.HOST_TOP_BAR_LIQUID_GLASS,
            FeaturePreferences.HOST_BOTTOM_BAR_LIQUID_GLASS, FeaturePreferences.HOST_BOTTOM_BAR_GLASS_MATERIAL,
            FeaturePreferences.HOST_BOTTOM_BAR_GLASS_RENDERER, FeaturePreferences.HOST_TOP_BAR_GLASS_MATERIAL,
            FeaturePreferences.HOST_TOP_BAR_GLASS_RENDERER)
        val original = prefs.all.filterKeys { it in keys }
        val materialBefore = prefs.getString(FeaturePreferences.HOST_CARD_MATERIAL, null)
        val radiusBefore = prefs.getInt(FeaturePreferences.HOST_VIDEO_CARD_RADIUS_DP, -1)
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val root = requireNotNull(activity.beautificationSettingsRoot)
                    fun toggle(label: Int) = descendants(root).filterIsInstance<MaterialSwitch>()
                        .single { it.text.toString() == context.getString(label) }
                    fun row(label: Int) = descendants(root).filterIsInstance<android.widget.TextView>()
                        .single { it.text.toString().startsWith(context.getString(label, "")) }
                    val cards = toggle(R.string.host_video_cards)
                    val material = row(R.string.host_card_material_current)
                    val radius = row(R.string.host_video_card_radius_current)
                    cards.isChecked = false
                    for (option in listOf(material, radius)) {
                        assertFalse(option.isEnabled)
                        assertEquals(.45f, option.alpha, .001f)
                        val previous = activity.activeConfirmDialog
                        option.performClick()
                        assertSame("Disabled rows must not open a settings panel", previous, activity.activeConfirmDialog)
                    }
                    assertTrue("Background settings work without floating cards", row(R.string.host_background_current).isEnabled)
                    cards.isChecked = true
                    listOf(material, radius).forEach { assertTrue(it.isEnabled); assertEquals(1f, it.alpha, .001f) }
                    assertEquals(materialBefore, prefs.getString(FeaturePreferences.HOST_CARD_MATERIAL, null))
                    assertEquals(radiusBefore, prefs.getInt(FeaturePreferences.HOST_VIDEO_CARD_RADIUS_DP, -1))
                    val bottom = toggle(R.string.host_bottom_bar_liquid_glass)
                    bottom.isChecked = false
                    assertFalse(toggle(R.string.host_bottom_bar_compact).isEnabled)
                    assertFalse(toggle(R.string.host_bottom_bar_icon_only).isEnabled)
                    assertTrue("Touch glow is independent of the glass switch", toggle(R.string.host_bottom_bar_touch_glow).isEnabled)
                    bottom.isChecked = true
                    assertTrue(toggle(R.string.host_bottom_bar_compact).isEnabled)
                    assertTrue(toggle(R.string.host_bottom_bar_icon_only).isEnabled)
                    val copy = activity.freeCopyEnabled
                    val description = activity.freeCopyDescEnabled
                    activity.freeCopyEnabled = false
                    activity.freeCopyDescEnabled = false
                    activity.refreshCopyAppearanceOptions()
                    assertFalse(toggle(R.string.free_copy_auto_light).isEnabled)
                    assertFalse(toggle(R.string.free_copy_light_mode).isEnabled)
                    activity.freeCopyDescEnabled = true
                    activity.refreshCopyAppearanceOptions()
                    assertTrue(toggle(R.string.free_copy_auto_light).isEnabled)
                    assertTrue(toggle(R.string.free_copy_light_mode).isEnabled)
                    activity.freeCopyEnabled = copy
                    activity.freeCopyDescEnabled = description
                    activity.refreshCopyAppearanceOptions()
                }
            }
        } finally {
            prefs.edit().apply {
                keys.forEach { key -> when (val value = original[key]) {
                    is Boolean -> putBoolean(key, value)
                    is String -> putString(key, value)
                    else -> remove(key)
                } }
            }.commit()
        }
    }

    @Test fun productionSettingsSwipeBothWaysAndRetainVerticalScrolling() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var pager: SettingsPagePager
            scenario.onActivity { activity ->
                pager = descendants(activity.window.decorView).filterIsInstance<SettingsPagePager>().single()
                pager.selectPage(0, false)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                swipe(pager, .8f, .45f, .15f, .45f)
                assertEquals("Favorites must swipe to purification", 1, pager.selectedPage)
                pager.selectPage(1, false)
                swipe(pager, .2f, .3f, .85f, .3f)
                assertEquals("The reverse swipe must return to favorites", 0, pager.selectedPage)
                pager.selectPage(1, false)
                val scroll = descendants(pager.getChildAt(1)).filterIsInstance<SettingsHomeScrollView>().single()
                scroll.scrollTo(0, 0)
                swipe(pager, .5f, .65f, .5f, .35f)
                assertEquals(1, pager.selectedPage)
                assertTrue("Settings must still scroll vertically", scroll.scrollY > 0)
                scroll.scrollTo(0, 0)
                pager.selectPage(0, false)
            }
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun swipe(view: View, x0: Float, y0: Float, x1: Float, y1: Float) {
        val down = SystemClock.uptimeMillis()
        fun send(step: Int, action: Int, fraction: Float) {
            val event = MotionEvent.obtain(down, down + step * 16L, action,
                view.width * (x0 + (x1 - x0) * fraction), view.height * (y0 + (y1 - y0) * fraction), 0)
            try { view.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        send(0, MotionEvent.ACTION_DOWN, 0f)
        for (step in 1..12) send(step, MotionEvent.ACTION_MOVE, step / 12f)
        send(13, MotionEvent.ACTION_UP, 1f)
    }
}
