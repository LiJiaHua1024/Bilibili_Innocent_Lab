package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.lang.reflect.Field
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isStatic
import java.util.concurrent.ConcurrentHashMap

/** comment2 正文监听器的同步捕获；不遍历 Activity、整个 Holder 或列表。 */
internal class CommentLegacyCopyBridge(loader: ClassLoader) {
    private val model = KavaMemberLookup.classOrNull(loader, "com.bilibili.app.comm.comment2.model.BiliComment")
    private val content = model?.let { KavaMemberLookup.fieldOrNull(it, "mContent") }
    private val message = content?.let { KavaMemberLookup.fieldOrNull(it.type, "mMsg") }
        ?.takeIf { it.type == classOf<String>() }
    private val bodies = listOf("CommentExpandableTextView", "CommentSpanEllipsisTextView").mapNotNull {
        KavaMemberLookup.classOrNull(loader, "com.bilibili.app.comm.comment2.widget.$it")
    }
    private val fields = ConcurrentHashMap<Class<*>, List<Field>>()

    fun isApplicable(): Boolean = model != null && bodies.isNotEmpty()
    fun isReady(): Boolean = content != null && message != null && bodies.isNotEmpty()
    fun isBody(view: View): Boolean = bodies.any { it.isInstance(view) }

    fun capture(listener: Any?): Pair<String, Any>? {
        if (listener == null || !isReady()) return null
        val candidates = ArrayList<Any>()
        fun read(value: Any, descend: Boolean) {
            if (model!!.isInstance(value)) {
                if (candidates.none { it === value }) candidates += value
                return
            }
            val slots = fields[value.javaClass] ?: KavaMemberLookup.fields(value.javaClass,
                includeSuperclasses = true, makeAccessible = true).filter {
                !it.isStatic && !it.type.isPrimitive
            }.take(16).also { fields[value.javaClass] = it }
            slots.forEach { field ->
                val child = runCatching { field.get(value) }.getOrNull() ?: return@forEach
                if (model.isInstance(child)) {
                    if (candidates.none { it === child }) candidates += child
                } else if (descend && child.javaClass.name.startsWith("com.bilibili.app.comm.comment2.")) {
                    read(child, false)
                }
            }
        }
        read(listener, true)
        val value = candidates.singleOrNull()?.let { content!!.get(it) } ?: return null
        val raw = message!!.get(value) as? String ?: return null
        return raw.takeIf { it.isNotBlank() }?.let { it to value }
    }
}
