package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Identify the native fill in this root's Modifier chain, even when R8 renames its private class. */
internal class HostChromeRootFill(
    private val element: Class<*>, private val brush: Class<*>, private val shape: Class<*>,
    private val function: Class<*>, private val empty: Any, private val factory: Method
) {
    private data class Verified(val shape: Field?)
    private val verified = HashMap<Class<*>, Verified>()

    fun matches(value: Any): Boolean = shapeField(value) != null

    /** A fresh native element keeps identity ownership even when the rest is a shared Modifier. */
    fun transparent(value: Any): Any? {
        val field = shapeField(value) ?: return null
        val nativeShape = field.get(value) ?: return null
        return requireNotNull(factory.invoke(null, empty, 0L, nativeShape)).also {
            check(it.javaClass === value.javaClass)
        }
    }

    private fun shapeField(value: Any): Field? = verified.getOrPut(value.javaClass) {
        val owner = value.javaClass
        if (!element.isInstance(value)) return@getOrPut Verified(null)
        val fields = Lookup.declaredFields(owner, makeAccessible = true).filterNot { Modifier.isStatic(it.modifiers) }
        val basic = listOf(Long::class.javaPrimitiveType!!, brush, Float::class.javaPrimitiveType!!, shape)
        val expected = when (fields.size) {
            4 -> basic
            5 -> basic + function
            else -> return@getOrPut Verified(null)
        }
        if (fields.map { it.type }.groupingBy { it }.eachCount() != expected.groupingBy { it }.eachCount()) return@getOrPut Verified(null)
        val constructor = Lookup.declaredConstructors(owner).any {
            val p = it.parameterTypes.toList()
            p.take(expected.size) == expected && p.drop(expected.size).all { tail ->
                tail == Int::class.javaPrimitiveType || tail.name == "kotlin.jvm.internal.DefaultConstructorMarker"
            }
        }
        if (!constructor) return@getOrPut Verified(null)
        val field = fields.single { it.type == shape }
        val nativeShape = field.get(value) ?: return@getOrPut Verified(null)
        // A lookalike shape is insufficient. The host's own color-background factory must create
        // this exact class from an empty Modifier. The probe is never attached or drawn.
        val probe = requireNotNull(factory.invoke(null, empty, 0xFF010203L shl 32, nativeShape))
        Verified(field.takeIf { probe.javaClass === owner })
    }.shape
}
