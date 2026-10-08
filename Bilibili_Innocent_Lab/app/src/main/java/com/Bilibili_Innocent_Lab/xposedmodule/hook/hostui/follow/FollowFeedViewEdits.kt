package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.follow

import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.lang.ref.WeakReference

/** 仅恢复仍等于本适配器最后一次写入的属性，不覆盖宿主之后的写入。 */
internal class FollowFeedViewEdits {
    private class Background(view: View, val original: Drawable?, var installed: Drawable?) {
        val view = WeakReference(view)
    }
    private class Text(view: TextView) {
        val view = WeakReference(view)
        val colors = view.textColors
        val size = view.textSize
        val maxLines = view.maxLines
        val ellipsize = view.ellipsize
        val description = view.contentDescription
        var appliedColors: ColorStateList? = null
        var appliedSize: Float? = null
        var appliedMax: Int? = null
        var appliedDescription: CharSequence? = null
    }
    private val backgrounds = ArrayList<Background>()
    private val texts = ArrayList<Text>()
    private class Size(view: View) {
        val view = WeakReference(view)
        val width = view.layoutParams.width
        val height = view.layoutParams.height
        var appliedWidth: Int? = null
        var appliedHeight: Int? = null
    }
    private val sizes = ArrayList<Size>()
    private class Clipping(view: ViewGroup) {
        val view = WeakReference(view)
        val children = view.clipChildren
        val padding = view.clipToPadding
    }
    private val clipping = ArrayList<Clipping>()

    fun unclip(view: ViewGroup) {
        if (clipping.none { it.view.get() === view }) clipping += Clipping(view)
        view.clipChildren = false
        view.clipToPadding = false
    }

    fun size(view: View, width: Int? = null, height: Int? = null) {
        val saved = sizes.firstOrNull { it.view.get() === view } ?: Size(view).also(sizes::add)
        val params = view.layoutParams
        if (width != null) { params.width = width; saved.appliedWidth = width }
        if (height != null) { params.height = height; saved.appliedHeight = height }
        view.layoutParams = params
    }

    fun background(view: View, drawable: Drawable?) {
        val saved = backgrounds.firstOrNull { it.view.get() === view }
        if (saved == null) backgrounds.add(Background(view, view.background, drawable))
        else {
            if (view.background !== saved.installed) return
            saved.installed = drawable
        }
        val l = view.paddingLeft; val t = view.paddingTop; val r = view.paddingRight; val b = view.paddingBottom
        view.background = drawable
        view.setPadding(l, t, r, b)
    }

    /** 为宿主原 selector 的独立副本配色，保留按压状态及原控件交互。 */
    fun backgroundTint(view: View, colors: ColorStateList) {
        val drawable = view.background?.constantState?.newDrawable(view.resources)?.mutate() ?: return
        drawable.setTintList(colors)
        background(view, drawable)
    }

    fun text(view: TextView, color: Int, sizeSp: Float? = null, singleLine: Boolean = false, maxLines: Int? = null) {
        val saved = texts.firstOrNull { it.view.get() === view } ?: Text(view).also(texts::add)
        view.setTextColor(color)
        saved.appliedColors = view.textColors
        if (sizeSp != null) {
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            saved.appliedSize = view.textSize
        }
        if (singleLine || maxLines != null) {
            view.maxLines = if (singleLine) 1 else requireNotNull(maxLines)
            view.ellipsize = TextUtils.TruncateAt.END
            saved.appliedMax = view.maxLines
        }
        if (singleLine) {
            view.contentDescription = view.text
            saved.appliedDescription = view.contentDescription
        }
    }

    fun restore() {
        backgrounds.forEach { saved ->
            saved.view.get()?.let { view ->
                if (view.background === saved.installed) {
                    val l = view.paddingLeft; val t = view.paddingTop; val r = view.paddingRight; val b = view.paddingBottom
                    view.background = saved.original
                    view.setPadding(l, t, r, b)
                }
            }
        }
        texts.forEach { saved -> saved.view.get()?.let { view ->
            if (view.textColors === saved.appliedColors) view.setTextColor(saved.colors)
            if (view.textSize == saved.appliedSize) view.setTextSize(TypedValue.COMPLEX_UNIT_PX, saved.size)
            if (view.maxLines == saved.appliedMax) {
                view.maxLines = saved.maxLines
                if (view.ellipsize == TextUtils.TruncateAt.END) view.ellipsize = saved.ellipsize
            }
            if (view.contentDescription == saved.appliedDescription) view.contentDescription = saved.description
        } }
        sizes.forEach { saved -> saved.view.get()?.let { view ->
            val params = view.layoutParams
            if (params.width == saved.appliedWidth) params.width = saved.width
            if (params.height == saved.appliedHeight) params.height = saved.height
            view.layoutParams = params
        } }
        clipping.forEach { saved -> saved.view.get()?.let { view ->
            if (!view.clipChildren) view.clipChildren = saved.children
            if (!view.clipToPadding) view.clipToPadding = saved.padding
        } }
        backgrounds.clear(); texts.clear(); sizes.clear(); clipping.clear()
    }
}
