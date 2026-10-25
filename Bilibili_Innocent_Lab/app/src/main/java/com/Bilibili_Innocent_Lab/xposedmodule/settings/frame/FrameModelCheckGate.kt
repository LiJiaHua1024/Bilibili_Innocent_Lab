package com.Bilibili_Innocent_Lab.xposedmodule.settings.frame

/** Main-thread ownership of a model row. Events coalesce while a read/download is running. */
internal class FrameModelCheckGate {
    var attached = false
        private set
    private var revision = 0L
    private var busy = false
    fun attach(value: Boolean) { attached = value; invalidate() }
    fun invalidate() { revision++ }
    fun begin(): Long? = if (!attached || busy) null else revision.also { busy = true }
    fun current(token: Long): Boolean = attached && token == revision
    fun finish(token: Long): Boolean { busy = false; return attached && token != revision }
}
