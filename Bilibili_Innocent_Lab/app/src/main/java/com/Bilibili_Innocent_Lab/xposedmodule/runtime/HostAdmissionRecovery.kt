package com.Bilibili_Innocent_Lab.xposedmodule.runtime

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.lang.ref.WeakReference
import java.util.concurrent.Future

/** 每轮重新读取配置和申请许可；主线程只处理生命周期与已验证的安装结果。 */
internal class HostAdmissionRecovery(
    private val application: Application,
    private val admit: () -> HostAdmissionClient.Result,
    private val accept: (HostAdmissionClient.Result, Activity) -> Boolean,
    private val log: (String) -> Unit,
    private val finished: () -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val policy = HostAdmissionRetryPolicy(SystemClock.elapsedRealtime()) { main.removeCallbacks(it) }
    private var activity = WeakReference<Activity>(null)
    private var scheduled: Runnable? = null
    private var inFlight: Future<*>? = null
    private var closed = false
    private val expire = HostThreadGuard.runnable("admission.recovery.expire") { close("budget_exhausted") }
    private val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(value: Activity) = HostThreadGuard.run("admission.recovery.resume") {
            if (closed) return@run
            activity = WeakReference(value)
            schedule()
        }

        override fun onActivityPaused(value: Activity) = HostThreadGuard.run("admission.recovery.pause") {
            releaseActivity(value)
        }

        override fun onActivityCreated(value: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(value: Activity) = Unit
        override fun onActivityStopped(value: Activity) = HostThreadGuard.run("admission.recovery.stop") { releaseActivity(value) }
        override fun onActivitySaveInstanceState(value: Activity, state: Bundle) = Unit
        override fun onActivityDestroyed(value: Activity) = HostThreadGuard.run("admission.recovery.destroy") { releaseActivity(value) }
    }

    private fun releaseActivity(value: Activity) {
        if (activity.get() !== value) return
        activity.clear()
        policy.invalidate()
        scheduled?.let(main::removeCallbacks)
        scheduled = null
        inFlight?.cancel(false)
        inFlight = null
    }

    fun start() {
        runCatching {
            application.registerActivityLifecycleCallbacks(callbacks)
            check(main.postDelayed(expire, HostAdmissionRetryPolicy.WINDOW_MS))
        }.onFailure {
            close("scheduler_unavailable")
            throw it
        }
    }

    private fun schedule() {
        if (closed || scheduled != null || inFlight != null || activity.get() == null) return
        val delay = policy.nextDelay(SystemClock.elapsedRealtime()) ?: return close("budget_exhausted")
        val task = HostThreadGuard.runnable("admission.recovery.start") start@{
            scheduled = null
            if (closed || activity.get() == null) return@start
            val token = policy.begin(SystemClock.elapsedRealtime()) ?: return@start close("budget_exhausted")
            report("[BIL] 前台授权恢复尝试(attempt=${policy.attempts})")
            runCatching {
                inFlight = worker.submit {
                    val result = HostThreadGuard.call("admission.recovery.exchange", HostAdmissionClient.Result()) { admit() }
                    val receivedAt = SystemClock.elapsedRealtime()
                    lateinit var delivery: Runnable
                    delivery = HostThreadGuard.runnable("admission.recovery.deliver") deliver@{
                        val now = SystemClock.elapsedRealtime()
                        if (!policy.takeDelivery(token, now, delivery)) return@deliver
                        inFlight = null
                        val current = activity.get()?.takeIf { !it.isFinishing && !it.isDestroyed } ?: return@deliver
                        if (now - receivedAt >= HostAdmissionContract.CHALLENGE_TIMEOUT_MS) return@deliver schedule()
                        val installed = accept(result, current)
                        when {
                            installed -> close("recovered")
                            result.grant != null -> close("install_failed")
                            !HostAdmissionRouteClassifier.retryable(result.reason) -> close("not_retryable")
                            else -> schedule()
                        }
                    }
                    policy.postDelivery(token, SystemClock.elapsedRealtime(), delivery, main::post)
                }
            }.onFailure { close("queue_unavailable") }
        }
        scheduled = task
        if (!main.postDelayed(task, delay)) close("scheduler_unavailable")
    }

    private fun close(reason: String) {
        if (closed) return
        closed = true
        policy.close()
        activity.clear()
        scheduled?.let(main::removeCallbacks)
        scheduled = null
        inFlight?.cancel(false)
        inFlight = null
        main.removeCallbacks(expire)
        runCatching { application.unregisterActivityLifecycleCallbacks(callbacks) }
        HostThreadGuard.run("admission.recovery.finish") { finished() }
        report("[BIL] 前台授权恢复结束(reason=$reason, attempts=${policy.attempts})")
    }

    private fun report(message: String) { runCatching { log(message) } }

    companion object {
        private val worker = HostReceiptWire.executor("bil-admission-recovery")
    }
}
