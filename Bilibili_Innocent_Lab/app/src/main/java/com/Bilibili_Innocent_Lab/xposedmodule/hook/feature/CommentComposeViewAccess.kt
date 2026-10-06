package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.lang.reflect.Method

/** 安装期解析宿主 Compose ABI；每次绑定只调用已解析的接口方法。 */
internal class CommentComposeViewAccess private constructor(private val consume: Method, private val local: Any) {
    fun read(composer: Any): View? = consume.invoke(composer, local) as? View

    companion object {
        fun resolve(loader: ClassLoader): CommentComposeViewAccess? = runCatching {
            val owner = KavaMemberLookup.classOrNull(loader, "androidx.compose.ui.platform.AndroidCompositionLocals_androidKt")
                ?: return@runCatching null
            val local = KavaMemberLookup.methodOrNull(owner, "getLocalView")?.invoke(null) ?: return@runCatching null
            val base = KavaMemberLookup.classOrNull(loader, "androidx.compose.runtime.CompositionLocal") ?: return@runCatching null
            val composer = KavaMemberLookup.classOrNull(loader, "androidx.compose.runtime.Composer") ?: return@runCatching null
            val consume = KavaMemberLookup.methodOrNull(composer, "consume", base) ?: return@runCatching null
            CommentComposeViewAccess(consume, local)
        }.getOrNull()
    }
}

internal fun commentOwnerActivity(start: Context): Activity? {
    var current: Context? = start
    repeat(8) {
        val context = current ?: return null
        if (context is Activity) return context
        val next = (context as? ContextWrapper)?.baseContext ?: return null
        if (next === context) return null
        current = next
    }
    return null
}
