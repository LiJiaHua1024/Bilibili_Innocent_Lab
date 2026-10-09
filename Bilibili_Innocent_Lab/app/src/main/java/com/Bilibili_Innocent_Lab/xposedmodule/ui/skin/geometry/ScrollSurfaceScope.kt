package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.geometry

/** Scrolling moves descendants, not the scroll host's background or another page. */
internal object ScrollSurfaceScope {
    inline fun <T : Any> contains(surface: T, scrollHost: T, parentOf: (T) -> T?): Boolean {
        if (surface === scrollHost) return false
        var ancestor = parentOf(surface)
        while (ancestor != null) {
            if (ancestor === scrollHost) return true
            ancestor = parentOf(ancestor)
        }
        return false
    }
}
