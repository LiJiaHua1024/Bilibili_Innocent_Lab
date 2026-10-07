package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity
import com.google.android.material.tabs.TabLayout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DynamicVideoTabPreferenceInstrumentedTest {
    class Page : Fragment() {
        override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
            View(requireContext()).apply { setBackgroundColor(Color.WHITE) }
    }

    @Test fun defaultVideoWaitsForInitialTransactionAndKeepsOnlyOnePageVisible() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val selected = CountDownLatch(1)
            val preference = DynamicVideoTabPreference()
            val all = Page()
            val video = Page()
            lateinit var tabs: TabLayout
            lateinit var videoTab: TabLayout.Tab
            var containerId = 0
            scenario.onActivity { activity ->
                val parent = FrameLayout(activity)
                val container = FrameLayout(activity).apply { id = View.generateViewId() }
                containerId = container.id
                parent.addView(container, FrameLayout.LayoutParams(-1, -1))
                tabs = TabLayout(activity)
                parent.addView(tabs, FrameLayout.LayoutParams(-1, 120))
                activity.setContentView(parent)
                val manager = activity.supportFragmentManager
                fun switchPage(page: Page, tag: String) {
                    val visible = manager.fragments.filter { it.isVisible }
                    if (page in visible) return
                    manager.beginTransaction().apply {
                        visible.forEach { hide(it) }
                        if (page.isAdded) show(page) else add(containerId, page, tag)
                    }.commit()
                }
                tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                    override fun onTabSelected(tab: TabLayout.Tab) {
                        if (tab.position == 1) switchPage(video, "video")
                        else if (all.isAdded) switchPage(all, "all")
                    }
                    override fun onTabUnselected(tab: TabLayout.Tab) = Unit
                    override fun onTabReselected(tab: TabLayout.Tab) = Unit
                })
                tabs.addTab(tabs.newTab().setText("All"), true)
                videoTab = tabs.newTab().setText("Video")
                tabs.addTab(videoTab, false)
                preference.schedule(tabs, videoTab, { selected.countDown() }, { throw AssertionError(it) })
                assertEquals(0, tabs.selectedTabPosition)
                // 与宿主一致：标签构建后才按原始 UI 状态提交默认页，事务异步执行。
                switchPage(all, "all")
                assertFalse(all.isAdded)
            }
            assertTrue("Default selection never ran", selected.await(5, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals(1, tabs.selectedTabPosition)
                assertTrue(all.isHidden)
                assertTrue(video.isVisible)
                assertEquals(1, activity.supportFragmentManager.fragments.count {
                    it.id == containerId && it.isVisible
                })
                tabs.getTabAt(0)!!.select()
                // 重复绑定不能覆盖用户手动切回全部的选择。
                preference.schedule(tabs, videoTab, { throw AssertionError("Preference ran twice") },
                    { throw AssertionError(it) })
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals(0, tabs.selectedTabPosition)
                assertTrue(all.isVisible)
                assertTrue(video.isHidden)
                assertEquals(1, activity.supportFragmentManager.fragments.count {
                    it.id == containerId && it.isVisible
                })
            }
        }
    }

    @Test fun rebuiltTabsRejectThePendingOldTab() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val nextFrame = CountDownLatch(1)
            lateinit var tabs: TabLayout
            scenario.onActivity { activity ->
                tabs = TabLayout(activity)
                activity.setContentView(tabs)
                tabs.addTab(tabs.newTab().setText("All"), true)
                val oldVideo = tabs.newTab().setText("Video")
                tabs.addTab(oldVideo, false)
                DynamicVideoTabPreference().schedule(tabs, oldVideo,
                    { throw AssertionError("Selected a removed tab") }, { throw AssertionError(it) })
                tabs.removeTab(oldVideo)
                tabs.postOnAnimation { nextFrame.countDown() }
            }
            assertTrue(nextFrame.await(5, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            scenario.onActivity { assertEquals(0, tabs.selectedTabPosition) }
        }
    }
}
