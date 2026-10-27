package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.agent.host.HostAgentRouter
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.CastToolbarClickLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.CastToolbarClickPoint
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.PlayerBufferLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SponsorPlayerClasses
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SponsorPlayerLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor.SponsorPlayerBindings
import com.Bilibili_Innocent_Lab.xposedmodule.lan.CastSource
import com.Bilibili_Innocent_Lab.xposedmodule.lan.ContinuityState
import com.Bilibili_Innocent_Lab.xposedmodule.lan.HostLanPanel
import com.Bilibili_Innocent_Lab.xposedmodule.lan.LanRuntime
import com.Bilibili_Innocent_Lab.xposedmodule.lan.LanTrustStore
import com.Bilibili_Innocent_Lab.xposedmodule.lan.LanVideoPart
import com.Bilibili_Innocent_Lab.xposedmodule.lan.CastDanmakuStyle
import com.Bilibili_Innocent_Lab.xposedmodule.lan.LanHostMedia
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.highcapable.betterandroid.ui.extension.component.hostActivity
import com.highcapable.betterandroid.ui.extension.view.toast
import java.lang.ref.WeakReference

/** Instrumentation hooks are required to observe host-owned Activities across their class loaders. */
internal class LanContinuityFeatureInstaller(private val enabled: Boolean,
    private val classes: () -> SponsorPlayerClasses?,
    private val castClick: () -> CastToolbarClickPoint? = { null }) : FeatureInstaller {
    @Volatile var requiresAdaptationRetry = false
        private set
    private val registrationStarted = java.util.concurrent.atomic.AtomicBoolean(false)
    override val id = "lan_continuity"
    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) return FeatureInstallResult.Skipped("disabled")
        if (environment.processName != "tv.danmaku.bili") return FeatureInstallResult.Skipped("non-main-process")
        val loader = environment.classLoader ?: return FeatureInstallResult.Skipped("missing-class-loader")
        val lifecycle = listOf("callActivityOnResume", "callActivityOnPause", "callActivityOnDestroy").map { name ->
            Lookup.methodOrNull(Instrumentation::class.java, name, Activity::class.java) ?: return FeatureInstallResult.Skipped("missing-lifecycle")
        }
        val resolved = SponsorPlayerLocator.direct(loader) ?: classes()?.let { SponsorPlayerLocator.resolve(loader, it) }
        if (resolved == null) { requiresAdaptationRetry = true; return FeatureInstallResult.Skipped("missing-player-structure") }
        requiresAdaptationRetry = false
        val access = resolved.player
        fun castUnavailable(reason: String): FeatureInstallResult {
            environment.logError("lan_entry_$reason", "[BIL-LAN] cast entry unavailable: $reason")
            return FeatureInstallResult.Skipped(reason)
        }
        // 8.84.0–8.96.0 是 Repository 内的静态回调；8.97.0 起改为合并 lambda 的 onClick(View)V。
        // 缓存形态在每次使用前都会用当前宿主 ClassLoader 重新验真。
        val castPoint = CastToolbarClickLocator.direct(loader)
            ?: CastToolbarClickLocator.cached(loader, castClick())
        if (castPoint == null) {
            // 后台适配尚未完成（或宿主确实没有该入口）：请求补装一次，找不到就保持跳过。
            requiresAdaptationRetry = CastToolbarClickLocator.applicable(loader)
            return castUnavailable("missing-native-cast-entry")
        }
        if (!registrationStarted.compareAndSet(false, true)) return FeatureInstallResult.Skipped("already-registered")
        val speedGetter = Lookup.inheritedMethodOrNull(access.position.declaringClass, "getPlaySpeed", Boolean::class.javaPrimitiveType!!)
            ?.takeIf { it.returnType == Float::class.javaPrimitiveType }
        val assets = PlayerBufferLocator.resolve(loader)
        val mediaAccess = assets?.let { LanHostMedia.resolve(loader, it) }
        val router = HostAgentRouter.resolve(loader)
        val handler = Handler(Looper.getMainLooper())
        val bindings = SponsorPlayerBindings()
        var foreground = WeakReference<Activity>(null)
        var playerActivity = WeakReference<Activity>(null)
        var network: LanRuntime? = null
        var autoChecked = false
        var autoAllowed = false
        var autoSuppressed = false
        fun autoStart(activity: Activity) {
            if (autoSuppressed || network?.running == true) return
            if (!autoChecked) {
                autoChecked = true
                val app = activity.applicationContext
                Thread({
                    val paired = LanTrustStore.hasSavedBindings(app)
                    handler.post {
                        autoAllowed = paired
                        foreground.get()?.let { autoStart(it) }
                    }
                }, "BIL-LAN-TrustLoad").apply { isDaemon = true; start() }
                return
            }
            if (autoAllowed || network?.trust?.list()?.isNotEmpty() == true) {
                runCatching { LanRuntime(activity).also { network = it; autoAllowed = true } }
            }
        }
        var snapshot: ContinuityState? = null
        var session = ContinuityState.sessionId()
        var identity: Pair<String, Long>? = null
        data class Resume(val state: ContinuityState, val expires: Long, var submitted: Boolean = false)
        var resume: Resume? = null
        data class PartCast(val part: LanVideoPart, val network: LanRuntime, val style: CastDanmakuStyle,
            val previous: CastSource?, val expires: Long)
        var partCast: PartCast? = null
        fun source(): CastSource? = runCatching {
            val activity = foreground.get() ?: return null
            val (owner, candidate) = bindings.select(activity) ?: return null
            val video = access.video(owner) ?: return null
            val core = candidate.core.get() ?: return null
            val points = assets ?: return null
            val item = points.coreCurrent.invoke(core) ?: return null
            if (!points.realItem.declaringClass.isInstance(item)) return null
            val real = points.realItem.invoke(item) ?: return null
            val asset = points.assetField.get(real) ?: return null
            (mediaAccess ?: error("host-media-access-unavailable")).read(real, asset, points, video.bvid, video.cid).also {
                environment.logInfo("lan_current_source", "[BIL-LAN] current source bvid=${it.bvid} cid=${it.cid} tracks=${it.urls.size} candidates=${it.backups.values.sumOf { urls -> urls.size } + it.urls.size}")
            }
        }.onFailure {
            android.util.Log.w("BIL-LAN", "current source unavailable: ${it.message?.takeIf { text -> text.matches(Regex("[a-z0-9-]{1,60}")) } ?: it.javaClass.simpleName}")
        }.getOrNull()
        val panel = HostLanPanel({ network?.takeIf { it.running } }, { activity ->
            LanRuntime(activity).also { network = it; autoSuppressed = false; autoAllowed = it.trust.list().isNotEmpty() }
        }, { autoSuppressed = true; network?.close(); network = null }, ::source, { snapshot }, { activity, offer ->
            val navigator = router ?: error("host-router-unavailable")
            resume = Resume(offer.state, SystemClock.elapsedRealtime() + 30_000)
            // CID is authoritative. Do not also supply an invented part index.
            navigator.open(activity, "bilibili://video/${offer.state.bvid}?cid=${offer.state.cid}")
        }, { activity, part, style ->
            val navigator = router ?: error("host-router-unavailable")
            val net = network?.takeIf { it.running && it.selected != null } ?: error("no-selected-receiver")
            net.cancelPreparation()
            partCast = PartCast(part, net, style, source(), SystemClock.elapsedRealtime() + 30_000)
            navigator.open(activity, "bilibili://video/${part.bvid}?cid=${part.cid}&p=${part.page}")
        })
        val tick = object : Runnable {
            override fun run() {
                val front = foreground.get()
                val activity = front?.takeIf { bindings.select(it) != null } ?: playerActivity.get() ?: front ?: return
                try {
                    resume?.takeIf { SystemClock.elapsedRealtime() > it.expires }?.let {
                        resume = null; activity.toast("接力超时：未取得目标播放器或未能验证进度与倍速")
                    }
                    partCast?.takeIf { SystemClock.elapsedRealtime() > it.expires || network !== it.network || !it.network.running }?.let {
                        partCast = null; activity.toast("分 P 切换未就绪，已取消自动推送")
                    }
                    val pair = bindings.select(activity)
                    if (pair != null) playerActivity = WeakReference(activity)
                    val current = if (pair != null) runCatching {
                        val (owner, candidate) = pair
                        val epoch = candidate.epoch.get()
                        val wrapper = candidate.wrapper.get() ?: return@runCatching null
                        val core = candidate.core.get() ?: return@runCatching null
                        val video = access.video(owner) ?: return@runCatching null
                        val position = (access.position.invoke(wrapper) as? Int)?.toLong() ?: return@runCatching null
                        val duration = (access.duration.invoke(wrapper) as? Int)?.toLong() ?: return@runCatching null
                        val state = access.state.invoke(wrapper) as? Int
                        if (state !in setOf(4, 5) || duration <= 0 || position !in 0..duration) return@runCatching null
                        val speed = (speedGetter?.invoke(core, false) as? Float)?.takeIf {
                            it.isFinite() && it in PlayerSpeedConfig.MIN_MULTIPLIER..PlayerSpeedConfig.MAX_MULTIPLIER
                        }
                            ?: return@runCatching null
                        if (identity != (video.bvid to video.cid)) { session = ContinuityState.sessionId(); identity = video.bvid to video.cid }
                        val previous = snapshot
                        val now = System.currentTimeMillis()
                        val at = if (state == 5 && previous?.playing == false && previous.bvid == video.bvid &&
                            previous.cid == video.cid && previous.positionMs == position && previous.speed == speed) previous.capturedAt else now
                        val selectedPart = partCast?.part?.takeIf { it.bvid == video.bvid && it.cid == video.cid }
                        val title = selectedPart?.title ?: previous?.takeIf { it.bvid == video.bvid && it.cid == video.cid }?.title ?: video.bvid
                        val value = ContinuityState(session, video.bvid, video.cid, selectedPart?.page ?: 1, title, position, duration, speed, state == 4, at)
                        resume?.let { pending ->
                            if (pending.state.bvid == video.bvid && pending.state.cid == video.cid) {
                                val target = pending.state.positionAt(now).coerceAtMost(duration).toInt()
                                if (!pending.submitted) {
                                    val speedSetter = Lookup.inheritedMethodOrNull(core.javaClass, "setPlaySpeed", Float::class.javaPrimitiveType!!)
                                        ?.takeIf { it.returnType == Void.TYPE } ?: error("resume-speed-unavailable")
                                    val play = Lookup.inheritedMethodOrNull(core.javaClass, "play")?.takeIf { it.returnType == Void.TYPE }
                                        ?: error("resume-play-unavailable")
                                    check(bindings.current(owner, candidate, epoch, wrapper, activity) && access.video(owner) == video)
                                    // Invoke the explicitly bound core, avoiding the wrapper's deferred seek coroutine.
                                    access.seek.invoke(core, target, false)
                                    speedSetter.invoke(core, pending.state.speed)
                                    play.invoke(core)
                                    pending.submitted = true
                                } else if (kotlin.math.abs(position - target.toLong()) < 2500 && kotlin.math.abs(speed - pending.state.speed) < .01f && state == 4) {
                                    resume = null; activity.toast("已接续播放进度与倍速")
                                }
                            }
                        }
                        value
                    }.onFailure {
                        if (resume != null) { resume = null; activity.toast("未能安全续播：目标播放器或倍速能力不可用") }
                    }.getOrNull() else null
                    snapshot = current
                    partCast?.let { pending ->
                        if (current?.bvid == pending.part.bvid && current.cid == pending.part.cid && !pending.network.isPreparing) {
                            val media = source()?.takeIf { it.bvid == pending.part.bvid && it.cid == pending.part.cid }
                            val device = pending.network.selected
                            // Do not label a still-bound old asset with the new playable's CID during a route transition.
                            val old = pending.previous
                            val changed = old == null || old.cid == pending.part.cid || media?.urls != old.urls
                            if (media != null && device != null && changed) {
                                partCast = null
                                pending.network.cast(device, media, pending.part.title, current.positionMs,
                                    pending.style.takeIf { it.enabled }) { }
                            }
                        }
                    }
                    network?.watching = current
                    front?.let(panel::render)
                } catch (_: Exception) { /* A LAN UI failure must not escape the host main thread. */ }
                if (foreground.get() != null || (network?.running == true && playerActivity.get() != null)) handler.postDelayed(this, 1000)
            }
        }
        fun refresh() { handler.removeCallbacks(tick); if (foreground.get() != null ||
            (network?.running == true && playerActivity.get() != null)) handler.post(tick) }
        var count = 0
        // 工具栏 action 才是可点击对象；cast_screen_icon 子视图不是。
        // 静态回调直接以 (Repository, View) 定位；合并 lambda 则要求 payload 就是该 Repository，
        // 否则同类的其他点击分支一律放行给宿主。
        val clickOwner = castPoint.owner
        val clickMethod = castPoint.method
        val clickIsStatic = java.lang.reflect.Modifier.isStatic(clickMethod.modifiers)
        environment.registrar.exact("lan.native-cast-click", clickOwner, clickMethod.name, *clickMethod.parameterTypes) {
            before {
                if (!CastToolbarClickLocator.matches(instance, castPoint)) return@before
                val view = (if (clickIsStatic) argOrNull(1) else argOrNull(0)) as? View ?: return@before
                val activity = view.context.hostActivity ?: foreground.get()?.takeIf { it.window.decorView === view.rootView } ?: return@before
                if (!view.isAttachedToWindow || !view.isShown || activity.isFinishing) return@before
                result = null
                foreground = WeakReference(activity); refresh()
                environment.logInfo("lan_native_click", "[BIL-LAN] native cast click playerBound=${bindings.select(activity) != null}")
                runCatching { panel.open(activity) }.onFailure {
                    activity.toast("局域网投屏面板暂不可用")
                }
            }
        }; count++
        environment.logInfo("lan_native_installed", "[BIL-LAN] native cast entry registered; mediaAccess=${mediaAccess != null}")
        lifecycle.forEachIndexed { index, method ->
            environment.registrar.exact("lan.lifecycle.$index", method.declaringClass, method.name, *method.parameterTypes) {
                after {
                    if (hasThrowable) return@after
                    val activity = argOrNull(0) as? Activity ?: return@after
                    if (index == 0) { foreground = WeakReference(activity); autoStart(activity); refresh() }
                    else {
                        panel.detach(activity)
                        if (foreground.get() === activity) foreground.clear()
                        if (index == 2) {
                            bindings.destroy(activity)
                            if (playerActivity.get() === activity) { playerActivity.clear(); snapshot = null; network?.watching = null }
                        }
                        refresh()
                    }
                }
            }; count++
        }
        run {
            environment.registrar.constructor("lan.wrapper", access.wrapper) { after {
                if (hasThrowable) return@after
                val owner = argOrNull(access.wrapperOwnerIndex) ?: return@after
                bindings.wrapper(owner, instance ?: return@after, argOrNull(access.wrapperCoreIndex) ?: return@after)
            } }; count++
            access.scope?.let { constructor ->
                environment.registrar.constructor("lan.scope", constructor) { after {
                    if (hasThrowable) return@after
                    val owner = argOrNull(2) ?: return@after
                    val activity = (argOrNull(1) as? Context)?.hostActivity ?: return@after
                    bindings.activity(owner, activity)
                } }; count++
            }
            access.containerScope?.let { scope ->
                environment.registrar.constructor("lan.container", scope.constructor) { after {
                    if (hasThrowable) return@after
                    val activity = (argOrNull(scope.contextIndex) as? Context)?.hostActivity ?: return@after
                    val container = argOrNull(scope.containerIndex) ?: return@after
                    scope.core.invoke(container)?.let { bindings.container(it, activity) }
                } }; count++
            }
            environment.registrar.exact("lan.media", access.run.declaringClass, access.run.name, *access.run.parameterTypes) {
                before { instance?.let { setObjectExtra("lan.epoch", bindings.begin(it)) } }
                after {
                    if (hasThrowable) return@after
                    val owner = instance ?: return@after
                    if (access.bound(owner, argOrNull(0), argOrNull(1))) {
                        val epoch = getObjectExtra("lan.epoch") as? Long ?: return@after
                        bindings.ready(owner, epoch)
                    }
                }
            }; count++
        }
        return FeatureInstallResult.Installed(count, complete = assets != null && mediaAccess != null && router != null && speedGetter != null)
    }
}
