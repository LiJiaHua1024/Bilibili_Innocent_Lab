package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.view.View
import android.view.ViewTreeObserver
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** Keeps an exact VIP root hidden after asynchronous data/theme updates; detach removes observation. */
internal class MineVipVisibility(private val environment: HookEnvironment, private val keepSpace: Boolean) {
    private val guards = WeakHashMap<View, Guard>()
    fun hide(view: View) {
        val guard = guards[view] ?: Guard(view, environment, keepSpace).also {
            guards[view] = it; view.addOnAttachStateChangeListener(it)
        }
        guard.hide()
        if (view.isAttachedToWindow) guard.observe(view)
    }

    private class Guard(view: View, private val environment: HookEnvironment, keepSpace: Boolean) :
        View.OnAttachStateChangeListener, ViewTreeObserver.OnGlobalLayoutListener {
        private val target = WeakReference(view)
        private val visibility = if (keepSpace) View.INVISIBLE else View.GONE
        private var observer = WeakReference<ViewTreeObserver>(null)
        private var observed = false
        private var applied = false
        fun hide() {
            val view = target.get() ?: return
            if (!observed) { observed = true; environment.reportRuntimeEvidence(MineVipFeatureInstaller.ID, FeatureRuntimeStage.OBSERVED) }
            val current = view.visibility
            // A missing/host-hidden card must not acquire an INVISIBLE layout placeholder.
            if (current == View.GONE || current == visibility) return
            view.visibility = visibility
            if (!applied && view.visibility == visibility) {
                applied = true; environment.reportRuntimeEvidence(MineVipFeatureInstaller.ID, FeatureRuntimeStage.APPLIED)
            }
        }
        fun observe(view: View) {
            val current = view.viewTreeObserver
            if (observer.get() === current || !current.isAlive) return
            stop(); current.addOnGlobalLayoutListener(this); observer = WeakReference(current)
        }
        private fun stop() {
            observer.get()?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(this); observer.clear()
        }
        override fun onViewAttachedToWindow(view: View) = HostThreadGuard.run("mine.vip.attach") { hide(); observe(view) }
        override fun onViewDetachedFromWindow(view: View) = HostThreadGuard.run("mine.vip.detach") { stop() }
        override fun onGlobalLayout() = HostThreadGuard.run("mine.vip.layout") {
            val view = target.get()
            if (view == null || !view.isAttachedToWindow) stop() else hide()
        }
    }
}
