package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

internal class SponsorSeekAccess private constructor(val constructor: Constructor<*>, val create: Method,
    val invoke: Method, val label: Field, val unit: Any) {
    companion object {
        const val CLASS = SponsorPlayerAccess.WRAPPER_CLASS + "\$seekTo\$1"
        fun resolve(loader: ClassLoader, player: SponsorPlayerAccess,
            className: String = player.wrapper.declaringClass.name + "\$seekTo\$1"): SponsorSeekAccess? = runCatching {
            val cls = Lookup.classOrNull(loader, className) ?: return null
            val continuation = Lookup.classOrNull(loader, "kotlin.coroutines.Continuation") ?: return null
            val ctor = Lookup.declaredConstructors(cls, true) {
                it.parameterTypes.toList() == listOf(player.run.declaringClass, Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType, continuation)
            }.singleOrNull() ?: return null
            val create = Lookup.methodOrNull(cls, "create", Any::class.java, continuation)?.takeIf {
                !Modifier.isStatic(it.modifiers) && continuation.isAssignableFrom(it.returnType)
            } ?: return null
            val invoke = Lookup.methodOrNull(cls, "invokeSuspend", Any::class.java)?.takeIf {
                !Modifier.isStatic(it.modifiers) && it.returnType == Any::class.java
            } ?: return null
            val label = Lookup.fieldOrNull(cls, "label")?.takeIf {
                !Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType
            } ?: return null
            val unitClass = Lookup.classOrNull(loader, "kotlin.Unit") ?: return null
            val unit = Lookup.fieldOrNull(unitClass, "INSTANCE")?.get(null) ?: return null
            SponsorSeekAccess(ctor, create, invoke, label, unit)
        }.getOrNull()
    }
}
