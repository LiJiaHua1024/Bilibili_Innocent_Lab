package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.runtime

import androidx.annotation.MainThread
import java.util.WeakHashMap

/**
 * 模块设置 App 进程内、可丢弃图形资源的弱登记表。
 *
 * 公平内存 TRIM/KILL 不会走 Activity.onTrimMemory，所以不能只靠各 Activity 自己的
 * 回调。这里只释放 Liquid 图形；不关页面、不清偏好、不拆跨进程通道。
 */
internal fun interface ModuleMemoryPressureListener {
    fun onReleaseGraphics()
}

internal object ModuleMemoryPressureHub {
    private val lock = Any()
    private val listeners = WeakHashMap<ModuleMemoryPressureListener, Unit>()

    fun addListener(listener: ModuleMemoryPressureListener) {
        synchronized(lock) { listeners[listener] = Unit }
    }

    fun removeListener(listener: ModuleMemoryPressureListener) {
        synchronized(lock) { listeners.remove(listener) }
    }

    @MainThread
    fun releaseGraphics() {
        com.lumen.coacervation.engine.LumenEngine.releaseGraphics()
        snapshot().forEach { listener ->
            runCatching { listener.onReleaseGraphics() }
        }
    }

    /**
     * 查杀前的现场保存。模块设置已在每次用户操作时写入私有 prefs，这里刻意
     * 不做第二份持久化，以免与授权镜像 / Remote `hook_config` 抢写。
     */
    fun persistForImminentKill() = Unit

    private fun snapshot(): List<ModuleMemoryPressureListener> = synchronized(lock) {
        listeners.keys.toList()
    }
}
