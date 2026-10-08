package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.follow

import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import com.lumen.coacervation.engine.model.LumenPalette
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import kotlin.math.roundToInt

/** 读取宿主的选中索引；外圈留在原头像项的 overlay 中，随原生缩放和滚动移动。 */
internal class FollowFeedPersonalSelection private constructor(
    list: ViewGroup,
    private val adapter: Method,
    private val target: Method,
    private val holder: Method,
    private val item: Field,
    private val avatarId: Int
) : AutoCloseable {
    private val list = WeakReference(list)
    private var selected = WeakReference<View>(null)
    private val density = list.resources.displayMetrics.density
    private val stroke = (2 * density).roundToInt().coerceAtLeast(1)
    private val gap = (2 * density).roundToInt()
    private val bounds = Rect()
    private var color: Int? = null
    private val ring = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.TRANSPARENT)
    }

    fun sync(palette: LumenPalette): Boolean {
        val recycler = list.get() ?: return clear()
        val nativeAdapter = adapter.invoke(recycler) ?: return clear()
        if (!target.declaringClass.isInstance(nativeAdapter)) return clear()
        val index = target.invoke(nativeAdapter) as? Int ?: return clear()
        if (index < 0) return clear()
        val nativeHolder = holder.invoke(recycler, index) ?: return clear()
        val view = item.get(nativeHolder) as? ViewGroup ?: return clear()
        if (view.parent !== recycler || view.visibility != View.VISIBLE) return clear()
        val avatar = view.findViewById<View>(avatarId) ?: return clear()
        if (avatar.visibility != View.VISIBLE || avatar.width <= 0 || avatar.height <= 0) return clear()

        bounds.set(0, 0, avatar.width, avatar.height)
        view.offsetDescendantRectToMyCoords(avatar, bounds)
        val side = minOf(bounds.width(), bounds.height())
        val left = bounds.left + (bounds.width() - side) / 2 - gap - stroke
        val top = bounds.top + (bounds.height() - side) / 2 - gap - stroke
        bounds.set(left, top, left + side + (gap + stroke) * 2, top + side + (gap + stroke) * 2)
        if (ring.bounds != bounds) ring.bounds = bounds
        if (color != palette.primary) {
            color = palette.primary
            ring.setStroke(stroke, palette.primary)
        }
        if (selected.get() !== view) {
            clear()
            view.overlay.add(ring)
            selected = WeakReference(view)
        }
        return true
    }

    private fun clear(): Boolean {
        selected.get()?.overlay?.remove(ring)
        selected.clear()
        return false
    }

    override fun close() { clear() }

    companion object {
        fun create(list: ViewGroup): FollowFeedPersonalSelection? = runCatching {
            val adapter = list.javaClass.getMethod("getAdapter")
            val nativeAdapter = adapter.invoke(list) ?: return null
            val target = nativeAdapter.javaClass.getMethod("getTarget")
                .takeIf { it.returnType == Int::class.javaPrimitiveType } ?: return null
            val holder = list.javaClass.getMethod("findViewHolderForAdapterPosition", Int::class.javaPrimitiveType)
            val item = holder.returnType.getField("itemView")
                .takeIf { it.type == View::class.java } ?: return null
            val avatarId = list.resources.getIdentifier("avatar_container", "id", list.context.packageName)
                .takeIf { it != 0 } ?: return null
            FollowFeedPersonalSelection(list, adapter, target, holder, item, avatarId)
        }.getOrNull()
    }
}
