package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KotlinDynamicMossWatchTest {

    /** 形状模仿 9.14.0 `KDynamicMoss`：类型化入口（2 参）、泛型合成重载（5 参）、静态桥、其它接口。 */
    @Suppress("unused")
    private class FakeKotlinMoss {
        fun dynAll(request: String, continuation: Any): Any? = null
        fun dynAll(request: Any, a: Any, b: Any, c: Any, d: Any): Any? = null
        fun dynVideo(request: String, handler: Runnable) = Unit
        fun dynDetail(request: String, continuation: Any): Any? = null
        fun dynAllPersonal(request: String, continuation: Any): Any? = null

        companion object {
            @JvmStatic
            fun dynAll(request: String, continuation: String): Any? = null
        }
    }

    private fun watched(): Set<String> = FakeKotlinMoss::class.java.declaredMethods
        .filter(KotlinDynamicMossWatch::isWatchedEntry)
        .map { method -> method.name + method.parameterTypes.joinToString(",", "(", ")") { it.simpleName } }
        .toSet()

    @Test
    fun `watches only the two-argument dynAll and dynVideo instance entries`() {
        assertEquals(setOf("dynAll(String,Object)", "dynVideo(String,Runnable)"), watched())
    }

    @Test
    fun `does not watch generic overloads static bridges or unrelated moss calls`() {
        val names = FakeKotlinMoss::class.java.declaredMethods
            .filterNot(KotlinDynamicMossWatch::isWatchedEntry)
            .map { it.name }
            .toSet()
        // 5 参泛型重载、静态桥、dynDetail、dynAllPersonal 都必须落在"不观测"一侧。
        assertTrue(names.containsAll(setOf("dynAll", "dynDetail", "dynAllPersonal")))
        assertFalse(
            "static two-argument bridge must not be watched",
            FakeKotlinMoss::class.java.declaredMethods.any {
                KotlinDynamicMossWatch.isWatchedEntry(it) && java.lang.reflect.Modifier.isStatic(it.modifiers)
            }
        )
    }

    @Test
    fun `class name is the unobfuscated host name`() {
        assertEquals("com.bapis.bilibili.app.dynamic.v2.KDynamicMoss", KotlinDynamicMossWatch.K_MOSS_CLASS)
    }

    @Test
    fun `only entries the kotlin filter did not take are watched`() {
        val entries = FakeKotlinMoss::class.java.declaredMethods.toList()
        // 一个都没接上：两个入口都要观测。
        assertEquals(
            setOf("dynAll", "dynVideo"),
            KotlinDynamicMossWatch.selectUncovered(entries, emptySet()).map { it.name }.toSet()
        )
        // 只接上 dynAll：dynVideo 仍必须被观测，否则它不产生任何错误证据而状态照报 success。
        assertEquals(
            setOf("dynVideo"),
            KotlinDynamicMossWatch.selectUncovered(entries, setOf("dynAll")).map { it.name }.toSet()
        )
        // 全接上：没有要观测的入口。
        assertTrue(KotlinDynamicMossWatch.selectUncovered(entries, setOf("dynAll", "dynVideo")).isEmpty())
    }
}
