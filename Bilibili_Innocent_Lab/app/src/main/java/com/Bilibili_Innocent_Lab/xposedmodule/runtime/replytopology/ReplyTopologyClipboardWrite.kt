package com.Bilibili_Innocent_Lab.xposedmodule.runtime.replytopology

/** 只放行面板在本线程同步写入的确切 ClipData；同文案的官方复制仍走原拦截。 */
internal object ReplyTopologyClipboardWrite {
    private val activeClip = ThreadLocal<Any>()

    fun owns(clip: Any): Boolean = activeClip.get() === clip

    fun <T> write(clip: Any, block: () -> T): T {
        val previous = activeClip.get()
        activeClip.set(clip)
        try {
            return block()
        } finally {
            if (previous == null) activeClip.remove() else activeClip.set(previous)
        }
    }
}
