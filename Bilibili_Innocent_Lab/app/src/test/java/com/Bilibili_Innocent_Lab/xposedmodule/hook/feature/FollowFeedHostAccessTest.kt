package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.follow.FollowFeedHostAccess
import org.junit.Assert.*
import org.junit.Test

class FollowFeedHostAccessTest {
    enum class Kind { Author, Desc, OpusDesc, OpusDescList, Topic, Archive, Footer }
    class Item(val id: Long, val parent: Item? = null) {
        val children = ArrayList<Module>()
        fun f() = id
        fun i(): List<Module> = children
    }
    open class Module(val item: Item, val kind: Kind) {
        init { item.children += this }
        fun M() = item.parent ?: item
        fun O() = item
        fun f0() = item.parent != null
        fun T() = kind
        fun d0() = M().children.firstOrNull() === this
        fun g0() = M().children.lastOrNull() === this
    }
    class Video(item: Item) : Module(item, Kind.Archive)
    class Holder(var module: Module?)

    private fun access(): FollowFeedHostAccess {
        val module = Module::class.java
        val item = Item::class.java
        return FollowFeedHostAccess::class.java.declaredConstructors.single { !it.isSynthetic }.apply { isAccessible = true }
            .newInstance(module, module.getMethod("M"), item.getMethod("f"), module.getMethod("d0"),
                module.getMethod("g0"), module.getMethod("O"), module.getMethod("f0"), item.getMethod("i"),
                module.getMethod("T"), Video::class.java) as FollowFeedHostAccess
    }

    @Test fun quotedVideoAndDynamicUseTheirOwnBoundariesInsideTheParent() {
        for (videoQuote in listOf(false, true)) {
            val parent = Item(42)
            Module(parent, Kind.Author)
            Module(parent, Kind.Desc)
            val quoted = Item(77, parent)
            Module(quoted, Kind.Author)
            if (videoQuote) Video(quoted) else Module(quoted, Kind.Desc)
            parent.children.addAll(quoted.children)
            Module(parent, Kind.Footer)
            val access = access()
            quoted.children.forEachIndexed { index, module ->
                val row = requireNotNull(access.row(Holder(module), index + 2))
                val reference = requireNotNull(row.reference)
                assertEquals(42L, row.identity)
                assertFalse(row.first)
                assertFalse(row.last)
                assertEquals(77L, reference.identity)
                assertEquals(index == 0, reference.first)
                assertEquals(index == quoted.children.lastIndex, reference.last)
                assertTrue(reference.quoted)
            }
            assertNull(access.row(Holder(parent.children.last()), 4)?.reference)
        }
    }

    @Test fun bodyWithAnEmbeddedVideoGetsAFrameButPlainVideoPostsDoNot() {
        for (kind in Kind.entries) {
            val item = Item(42)
            Module(item, Kind.Author)
            Module(item, kind)
            Module(item, Kind.Topic) // 话题等模块可能夹在正文和视频之间。
            val video = Video(item)
            Module(item, Kind.Footer)
            val row = requireNotNull(access().row(Holder(video), 3))
            if (kind in setOf(Kind.Desc, Kind.OpusDesc, Kind.OpusDescList)) {
                val reference = requireNotNull(row.reference)
                assertTrue(reference.first && reference.last)
                assertFalse(reference.quoted)
            } else assertNull(row.reference)
        }
        val item = Item(43)
        val video = Video(item)
        Module(item, Kind.Desc) // 视频之后的正文不把该视频变成正文引用。
        assertNull(access().row(Holder(video), 0)?.reference)
    }

    @Test fun holderReuseRereadsReferenceIdentityAndRejectsStaleMembership() {
        val access = access()
        val parent = Item(42)
        val quoted = Item(77, parent)
        val holder = Holder(Video(quoted))
        parent.children.addAll(quoted.children)
        assertEquals(77L, access.row(holder, 1)?.reference?.identity)
        holder.module = Video(Item(43))
        val rebound = requireNotNull(access.row(holder, 2))
        assertEquals(43L, rebound.identity)
        assertNull(rebound.reference)
        holder.module!!.item.children.clear()
        assertNull(access.row(holder, 2))
        holder.module = null
        assertNull(access.row(holder, 2))
    }
}
