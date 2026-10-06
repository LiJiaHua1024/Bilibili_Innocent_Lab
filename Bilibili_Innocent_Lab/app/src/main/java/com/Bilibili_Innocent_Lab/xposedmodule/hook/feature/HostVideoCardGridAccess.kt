package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.lang.reflect.Method

/** 安装期定位成员，测量热路径只使用本地 Method 引用，不再查询反射缓存。 */
internal class HostVideoCardGridAccess private constructor(
    val measureChild: Method,
    private val paramsClass: Class<*>,
    private val spanCount: Method,
    private val orientation: Method,
    private val spanSize: Method,
    private val spanIndex: Method
) {
    fun doubleColumnSpan(manager: Any, params: Any): Int {
        if (!paramsClass.isInstance(params) || spanCount.invoke(manager) != 2 || orientation.invoke(manager) != 1 || spanSize.invoke(params) != 1) return -1
        return (spanIndex.invoke(params) as? Int)?.takeIf { it in 0..1 } ?: -1
    }

    companion object {
        fun resolve(loader: ClassLoader): HostVideoCardGridAccess? {
            val grid = KavaMemberLookup.classOrNull(loader, "androidx.recyclerview.widget.GridLayoutManager") ?: return null
            val params = KavaMemberLookup.classOrNull(loader, "androidx.recyclerview.widget.GridLayoutManager\$LayoutParams") ?: return null
            val signature = arrayOf(android.view.View::class.java, Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!)
            val measure = KavaMemberLookup.methodOrNull(grid, "measureChild", *signature)
                ?: KavaMemberLookup.declaredMethods(grid, makeAccessible = true) {
                    it.returnType == Void.TYPE && it.parameterTypes.contentEquals(signature)
                }.singleOrNull() ?: return null
            return HostVideoCardGridAccess(measure, params,
                KavaMemberLookup.inheritedMethodOrNull(grid, "getSpanCount") ?: return null,
                KavaMemberLookup.inheritedMethodOrNull(grid, "getOrientation") ?: return null,
                KavaMemberLookup.inheritedMethodOrNull(params, "getSpanSize") ?: return null,
                KavaMemberLookup.inheritedMethodOrNull(params, "getSpanIndex") ?: return null)
        }
    }
}
