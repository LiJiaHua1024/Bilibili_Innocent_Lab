package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background

import android.graphics.Bitmap
import android.view.ViewGroup
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.Executors

/** 仅挂在已识别为视频卡片列表的背景，不插入叠层、不修改 Fragment 可见性。 */
internal class HostBackgroundController(private val config: HostBackgroundConfig, private val onError: (Throwable) -> Unit,
    private val onApplied: () -> Unit = {}) {
    private class Surface(val drawable: HostBackgroundDrawable, val position: HostBackgroundListPosition)
    private val surfaces = WeakHashMap<ViewGroup, Surface>()
    private var image: Bitmap? = null
    private var requested = false
    private var applied = false

    fun scroll(list: ViewGroup, dy: Int) {
        if (dy == 0 || config.preset == HostBackgroundPreset.CUSTOM) return
        val surface = surfaces[list]?.drawable ?: return
        // 列表可能在中途恢复或替换；原点尚未确认时也必须响应向上滚动，不能锁死在零。
        surface.scrollOffset += dy
    }

    fun apply(list: ViewGroup, night: Boolean) {
        if (!config.enabled) return
        val state = surfaces.getOrPut(list) {
            Surface(HostBackgroundDrawable(config, night, image), HostBackgroundListPosition(list.javaClass))
        }
        val surface = state.drawable
        // 稳定布局中首条内容确实回到顶部才归零，不能依赖滚动条的临时边界报告。
        if (config.preset != HostBackgroundPreset.CUSTOM && surface.scrollOffset != 0L && state.position.isAtTop(list)) surface.scrollOffset = 0L
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
                        val next = HostBackgroundDrawable(config, old.drawable.night, loaded)
                        surfaces[view] = Surface(next, old.position)
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
