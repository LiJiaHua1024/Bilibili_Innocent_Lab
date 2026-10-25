package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

import org.junit.Assert.*
import org.junit.Test

class HostChromeBackdropPaintTest {
    private interface Modifier {
        fun then(other: Modifier): Modifier
    }
    private class Scope(val content: () -> Unit)
    private class Element(val name: String, val paint: (Scope, MutableList<String>) -> Unit)
    private class Chain(val elements: List<Element>) : Modifier {
        override fun then(other: Modifier) = Chain(elements + (other as Chain).elements)
        fun draw(events: MutableList<String>) {
            fun visit(index: Int) {
                if (index == elements.size) { events += "native-image"; return }
                elements[index].paint(Scope { visit(index + 1) }, events)
            }
            visit(0)
        }
    }
    private class Native {
        companion object {
            @JvmStatic fun drawWithContent(original: Modifier, draw: (Any) -> Unit): Modifier =
                original.then(Chain(listOf(Element("draw-boundary") { scope, _ -> draw(scope) })))
        }
    }
    private val empty = Chain(emptyList())
    private val withContent = Native::class.java.getDeclaredMethod("drawWithContent", Modifier::class.java,
        Function1::class.java).apply { isAccessible = true }
    private val then = Modifier::class.java.getDeclaredMethod("then", Modifier::class.java).apply { isAccessible = true }
    private fun suppress(original: Modifier, active: () -> Boolean = { true }) = HostChromeBackdropPaint.suppress(original, empty,
        withContent, then, Function1::class.java, Unit, active) { (it as Scope).content() } as Chain
    private fun native(): Chain = Chain(listOf(
        Element("native-solid") { scope, events -> events += "black-fill"; scope.content() },
        Element("native-gradient") { scope, events -> events += "native-gradient"; scope.content() },
        Element("native-layout") { scope, _ -> scope.content() }
    ))

    @Test fun `outer boundary removes solid gradient and native image paint together`() {
        val original = native()
        val before = mutableListOf<String>()
        original.draw(before)
        assertEquals(listOf("black-fill", "native-gradient", "native-image"), before)
        val after = mutableListOf<String>()
        suppress(original).draw(after)
        assertTrue(after.isEmpty())
        val restored = mutableListOf<String>()
        original.draw(restored)
        assertEquals(before, restored)
    }

    @Test fun `appended content suppression leaves the native black fill visible`() {
        val original = native()
        val wrong = Native.drawWithContent(original) {} as Chain
        val events = mutableListOf<String>()
        wrong.draw(events)
        assertEquals(listOf("black-fill", "native-gradient"), events)
        events.clear()
        suppress(original).draw(events)
        assertTrue(events.isEmpty())
    }

    @Test fun `native layout and input elements retain identity and foreground stays outside boundary`() {
        val original = native()
        val cleared = suppress(original)
        assertEquals(original.elements.size + 1, cleared.elements.size)
        original.elements.forEachIndexed { index, element -> assertSame(element, cleared.elements[index + 1]) }
        val events = mutableListOf<String>()
        cleared.draw(events)
        // The native foreground sibling is drawn by its own scope, after this decorative child.
        native().draw(events)
        assertEquals(listOf("black-fill", "native-gradient", "native-image"), events)
        assertNotSame(cleared, suppress(original))
    }

    @Test fun `material failure restores all native paint even after image derives another modifier`() {
        var active = false
        val original = native()
        val supplied = suppress(original) { active }
        val imageDerived = supplied.then(Chain(listOf(Element("image-layout") { scope, _ -> scope.content() }))) as Chain
        val events = mutableListOf<String>()
        imageDerived.draw(events)
        assertEquals(listOf("black-fill", "native-gradient", "native-image"), events)
        events.clear()
        active = true
        imageDerived.draw(events)
        assertTrue(events.isEmpty())
        active = false
        imageDerived.draw(events)
        assertEquals(listOf("black-fill", "native-gradient", "native-image"), events)
        events.clear()
        imageDerived.draw(events)
        assertEquals(listOf("black-fill", "native-gradient", "native-image"), events)
    }
}
