package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

import android.content.Context
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.lang.management.ManagementFactory
import com.sun.management.ThreadMXBean
import org.junit.Assert.*
import org.junit.Test

class HostChromeRecyclerTypesTest {
    private class Feed(context: Context) : RecyclerView(context)
    private val classes = arrayOf(FrameLayout::class.java, Feed::class.java, RecyclerView::class.java, ArrayList::class.java)
    private fun previous(type: Class<*>): Boolean = generateSequence(type as Class<*>?) { it.superclass }
        .take(12).any { it.name == "androidx.recyclerview.widget.RecyclerView" }

    @Test fun `host list subclasses are recognized while ordinary containers stay excluded`() {
        val types = HostChromeRecyclerTypes()
        assertFalse(types.matches(FrameLayout::class.java))
        assertTrue(types.matches(Feed::class.java))
        assertTrue(types.matches(RecyclerView::class.java))
        assertFalse(types.matches(ArrayList::class.java))
        repeat(1000) { assertEquals(previous(classes[it % classes.size]), types.matches(classes[it % classes.size])) }
    }

    @Test fun `repeated source discovery preserves classification and can record local allocation cost`() {
        val types = HostChromeRecyclerTypes()
        repeat(10000) { types.matches(classes[it % classes.size]); previous(classes[it % classes.size]) }
        val output = System.getenv("INNOCENT_LAB_REVIEW_PERF_REPORT") ?: return
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        if (bean.isThreadAllocatedMemorySupported) bean.isThreadAllocatedMemoryEnabled = true
        val iterations = 200000
        fun measure(action: (Class<*>) -> Boolean): Triple<Long, Long, Int> {
            val thread = Thread.currentThread().id
            val memory = if (bean.isThreadAllocatedMemorySupported) bean.getThreadAllocatedBytes(thread) else 0L
            val start = System.nanoTime()
            var matched = 0
            repeat(iterations) { if (action(classes[it % classes.size])) matched++ }
            return Triple(System.nanoTime() - start,
                if (bean.isThreadAllocatedMemorySupported) bean.getThreadAllocatedBytes(thread) - memory else 0L, matched)
        }
        val baseline = (1..3).map { measure(::previous) }
        val cached = (1..3).map { measure(types::matches) }
        assertTrue((baseline + cached).all { it.third == iterations / 2 })
        fun rows(values: List<Triple<Long, Long, Int>>) = values.joinToString(prefix = "[", postfix = "]") {
            "{\"nanos\":${it.first},\"allocated_bytes\":${it.second},\"matches\":${it.third}}"
        }
        File(output).writeText("{\"scope\":\"local_jvm_classification_only\",\"iterations\":$iterations," +
            "\"allocation_supported\":${bean.isThreadAllocatedMemorySupported},\"previous\":${rows(baseline)},\"cached\":${rows(cached)}}")
    }
}
