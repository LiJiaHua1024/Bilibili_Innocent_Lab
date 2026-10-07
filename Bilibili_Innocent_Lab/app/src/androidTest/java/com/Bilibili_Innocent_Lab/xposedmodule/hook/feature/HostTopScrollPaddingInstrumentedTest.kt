package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostTopScrollPaddingInstrumentedTest {
    private fun layout(view: View) {
        view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1800, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 1080, 1800)
    }

    @Test fun lateTopPaddingKeepsFirstRowAtTopWithoutResettingScrolledFeed() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val list = RecyclerView(instrumentation.targetContext)
            val manager = GridLayoutManager(list.context, 2)
            list.layoutManager = manager
            list.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun getItemCount() = 40
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                    object : RecyclerView.ViewHolder(View(parent.context).apply {
                        layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 400)
                    }) {}
                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
            }
            layout(list)
            assertFalse(list.canScrollVertically(-1))

            // 冷启动先显示缓存卡片，顶栏 Hook 随后才增加内边距。
            HostTopBarFxController.applyScrollPadding(list, 155, 2.75f)
            layout(list)
            assertEquals(155, manager.getDecoratedTop(manager.findViewByPosition(0)!!))
            assertFalse(list.canScrollVertically(-1))
            assertEquals(0f, HostTopListPosition(list).edge()!!.distance, 0f)

            list.scrollBy(0, 260)
            val scrolledTop = manager.getDecoratedTop(manager.findViewByPosition(0)!!)
            HostTopBarFxController.applyScrollPadding(list, 155, 2.75f)
            layout(list)
            assertEquals(scrolledTop, manager.getDecoratedTop(manager.findViewByPosition(0)!!))
            assertTrue(list.canScrollVertically(-1))
            assertEquals(260f, HostTopListPosition(list).edge()!!.distance, 0f)

            // 宿主重写 padding 后恢复配置，也不能把用户的滚动位置重置到首项。
            HostTopBarFxController.applyScrollPadding(list, 160, 2.75f)
            layout(list)
            assertTrue(list.canScrollVertically(-1))
        }
    }
}
