package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.follow

import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.view.View
import com.lumen.coacervation.engine.model.LumenPalette
import java.lang.ref.WeakReference

/** 箭头由整个窗口共享，不能随某个离屏 UP 主 Fragment 销毁而恢复成宿主白色。 */
internal class FollowFeedPersonalChrome private constructor(root: View, arrow: View) :
    AutoCloseable, View.OnAttachStateChangeListener {
    private val root = WeakReference(root)
    private val arrow = WeakReference(arrow)
    private val edits = FollowFeedViewEdits()
    private var color: Int? = null
    private var installed: Drawable? = null
    var closed = false
        private set

    init { root.addOnAttachStateChangeListener(this) }

    fun updatePalette(palette: LumenPalette) {
        if (closed) return
        val view = arrow.get() ?: return
        if (color == palette.background && view.background === installed) return
        edits.restore()
        edits.backgroundTint(view, ColorStateList.valueOf(palette.background))
        color = palette.background
        installed = view.background
    }

    override fun onViewAttachedToWindow(v: View) = Unit
    override fun onViewDetachedFromWindow(v: View) = close()
    override fun close() {
        if (closed) return
        closed = true
        root.get()?.removeOnAttachStateChangeListener(this)
        edits.restore()
        installed = null
    }

    companion object {
        fun create(root: View): FollowFeedPersonalChrome? {
            val id = root.resources.getIdentifier("dy_arrow", "id", root.context.packageName)
            val arrow = if (id != 0) root.findViewById<View>(id) else null
            if (arrow?.background?.constantState == null) return null
            return FollowFeedPersonalChrome(root, arrow)
        }
    }
}
