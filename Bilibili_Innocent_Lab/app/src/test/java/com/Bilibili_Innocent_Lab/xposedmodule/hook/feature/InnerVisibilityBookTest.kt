package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 内层隐藏账本：还原必须回到隐藏前的可见性，而不是一律 VISIBLE。 */
class InnerVisibilityBookTest {

    private class FakeBox(override var visibility: Int) : VisibilityBox {
        override val key: Any get() = this
    }

    private val book = InnerVisibilityBook()

    @Test fun `hiding a visible control and restoring brings it back visible`() {
        val box = FakeBox(VISIBLE)
        assertTrue(book.hide(box))
        assertEquals(GONE, box.visibility)
        assertTrue(book.tracks(box))
        assertTrue(book.restore(box))
        assertEquals(VISIBLE, box.visibility)
        assertFalse(book.tracks(box))
    }

    @Test fun `hiding an invisible control restores it as invisible`() {
        val box = FakeBox(INVISIBLE)
        assertTrue(book.hide(box))
        assertEquals(GONE, box.visibility)
        assertTrue(book.restore(box))
        assertEquals(INVISIBLE, box.visibility)
    }

    @Test fun `a control that is already gone is neither changed nor tracked`() {
        val box = FakeBox(GONE)
        assertFalse(book.hide(box))
        assertFalse(book.tracks(box))
        assertFalse(book.restore(box))
        assertEquals(GONE, box.visibility)
    }

    @Test fun `hiding twice keeps the first original visibility`() {
        val box = FakeBox(INVISIBLE)
        assertTrue(book.hide(box))
        assertFalse(book.hide(box))
        assertTrue(book.restore(box))
        assertEquals(INVISIBLE, box.visibility)
    }

    @Test fun `restore leaves a control the host already made visible and untracks it`() {
        val box = FakeBox(INVISIBLE)
        book.hide(box)
        box.visibility = VISIBLE
        assertTrue(book.restore(box))
        assertEquals(VISIBLE, box.visibility)
        assertFalse(book.tracks(box))
    }

    @Test fun `tracked keys are a snapshot that survives restoring during iteration`() {
        val a = FakeBox(VISIBLE)
        val b = FakeBox(INVISIBLE)
        book.hide(a)
        book.hide(b)
        for (key in book.trackedKeys()) book.restore(key as FakeBox)
        assertEquals(emptyList<Any>(), book.trackedKeys())
        assertEquals(VISIBLE, a.visibility)
        assertEquals(INVISIBLE, b.visibility)
    }

    private companion object {
        const val VISIBLE = 0
        const val INVISIBLE = 4
        const val GONE = 8
    }
}
