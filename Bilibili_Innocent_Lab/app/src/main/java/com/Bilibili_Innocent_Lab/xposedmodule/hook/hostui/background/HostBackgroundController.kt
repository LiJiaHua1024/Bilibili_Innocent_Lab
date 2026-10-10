package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background

import android.graphics.Bitmap
import android.view.ViewGroup
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.Executors

/** 仅挂在已识别为视频卡片列表的背景，不插入叠层、不修改 Fragment 可见性。 */
internal class HostBackgroundController(private val config: HostBackgroundConfig, private val onError: (Throwable) -> Unit,
    private val onApplied: () -> Unit = {}) {
    private val surfaces = WeakHashMap<ViewGroup, HostBackgroundDrawable>()
    private var image: Bitmap? = null
    private var requested = false
    private var applied = false

    fun scroll(list: ViewGroup, dy: Int) {
        if (dy == 0 || config.preset == HostBackgroundPreset.CUSTOM) return
        val surface = surfaces[list] ?: return
        surface.scrollOffset = (surface.scrollOffset + dy).coerceAtLeast(0L)
    }

    fun apply(list: ViewGroup, night: Boolean) {
        if (!config.enabled) return
        val surface = surfaces.getOrPut(list) { HostBackgroundDrawable(config, night, image) }
        // 回到顶部或刷新后从原点重新对齐；不能用 RecyclerView.scrollY（通常始终为 0）。
        if (config.preset != HostBackgroundPreset.CUSTOM && list.childCount > 0 && !list.canScrollVertically(-1)) surface.scrollOffset = 0L
        if (surface.night != night) { surface.night = night; surface.invalidateSelf() }
        if (list.background !== surface) list.background = surface
        if (!applied) { applied = true; onApplied() }
        if (config.preset != HostBackgroundPreset.CUSTOM || requested) return
        requested = true
        val app = list.context.applicationContext
        val anchor = WeakReference(list)
        worker.execute {
            val loaded = HostBackgroundImages.load(app, config)
            anchor.get()?.post {
                if (loaded == null) {
                    onError(IllegalStateException("Custom host background unavailable; using aurora"))
                } else {
                    image = loaded
                    // 每个列表持有自己的 Drawable，共享只读位图，避免 Drawable callback 串页。
                    for ((view, old) in surfaces.toMap()) {
                        val next = HostBackgroundDrawable(config, old.night, loaded)
                        surfaces[view] = next
                        view.background = next
                    }
                }
            }
        }
    }

    companion object {
        private val worker = Executors.newSingleThreadExecutor { Thread(it, "host-background").apply { isDaemon = true } }
    }
}
