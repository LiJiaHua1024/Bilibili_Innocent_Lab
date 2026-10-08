package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.follow.FollowFeedController
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.follow.FollowFeedHostAccess

import android.os.Bundle
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.util.concurrent.atomic.AtomicBoolean

internal class FollowFeedFeatureInstaller(private val enabled: Boolean) : FeatureInstaller {
    override val id = "follow_feed_style"

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) return FeatureInstallResult.Skipped("disabled")
        if (environment.processName != "tv.danmaku.bili") return FeatureInstallResult.Skipped("non-main-process")
        val loader = environment.classLoader ?: return FeatureInstallResult.Skipped("missing-classloader")
        val host = FollowFeedHostAccess.resolve(loader) ?: return FeatureInstallResult.Skipped("missing-dynamic-model")
        val fragment = KavaMemberLookup.classOrNull(loader, "com.bilibili.bplus.followinglist.home.mediator.MediatorFragment")
            ?: return FeatureInstallResult.Skipped("missing-follow-fragment")
        val recycler = KavaMemberLookup.classOrNull(loader, "androidx.recyclerview.widget.RecyclerView")
            ?: return FeatureInstallResult.Skipped("missing-recycler")
        val adapter = KavaMemberLookup.classOrNull(loader, "androidx.recyclerview.widget.RecyclerView\$Adapter")
            ?: return FeatureInstallResult.Skipped("missing-adapter")
        val holder = KavaMemberLookup.classOrNull(loader, "androidx.recyclerview.widget.RecyclerView\$ViewHolder")
            ?: return FeatureInstallResult.Skipped("missing-holder")
        val itemView = holder.getField("itemView")
        val getView = fragment.getMethod("getView")
        // 已读宿主源码：z0/gv1.c.k 仅调用矩形模块底色绘制。保留整个 ItemDecoration，
        // 尤其 getItemOffsets 和 onDrawOver；未知版本没有这条契约时整体安全跳过。
        val painter = KavaMemberLookup.classOrNull(loader, "gv1.c")
            ?: return FeatureInstallResult.Skipped("missing-module-background-painter")
        val paint = KavaMemberLookup.methodOrNull(painter, "k", Canvas::class.java, recycler, View::class.java, host.moduleClass)
            ?.takeIf { it.returnType == Void.TYPE }
            ?: return FeatureInstallResult.Skipped("missing-module-background-contract")
        // 在任何注入前确认关键边界，不在注册一半后才发现不可恢复的布局能力缺失。
        recycler.getDeclaredMethod("getItemDecorInsetsForChild", View::class.java)
        recycler.getDeclaredMethod("dispatchChildAttached", View::class.java)
        fragment.getDeclaredMethod("onViewCreated", View::class.java, Bundle::class.java)
        fragment.getDeclaredMethod("onDestroyView")
        val headerPadding = KavaMemberLookup.methodOrNull(fragment, "ye", Int::class.javaPrimitiveType!!)
            ?.takeIf { it.returnType == Boolean::class.javaPrimitiveType }
        val personalFragment = KavaMemberLookup.classOrNull(loader,
            "com.bilibili.bplus.followinglist.quick.consume.VideoQuickConsumeFragment")?.takeIf {
            KavaMemberLookup.methodOrNull(it, "onViewCreated", View::class.java, Bundle::class.java) != null &&
                KavaMemberLookup.methodOrNull(it, "onDestroyView") != null &&
                KavaMemberLookup.methodOrNull(it, "setUserVisibleCompat", Boolean::class.javaPrimitiveType!!) != null
        }
        val separator = KavaMemberLookup.classOrNull(loader, "com.bilibili.bplus.followinglist.quick.consume.y0")
        val recyclerState = KavaMemberLookup.classOrNull(loader, "androidx.recyclerview.widget.RecyclerView\$State")
        val separatorDraw = if (separator != null && recyclerState != null)
            KavaMemberLookup.methodOrNull(separator, "onDrawOver", Canvas::class.java, recycler, recyclerState)
                ?.takeIf { it.returnType == Void.TYPE } else null
        val failed = AtomicBoolean(false)
        val applied = AtomicBoolean(false)
        val controller = FollowFeedController(host, compactHeaderSupported = headerPadding != null, report = {
            // 日志入口按 key 去重；每个固定生命周期阶段各输出一次，不泄漏业务身份。
            environment.logInfo("follow_feed_diagnostics_${it.substringBefore(':')}", it)
            if (applied.compareAndSet(false, true)) environment.reportRuntimeEvidence(id, FeatureRuntimeStage.APPLIED)
        }, error = {
            if (failed.compareAndSet(false, true)) environment.logError("follow_feed_error",
                "[BIL] 关注页样式安全跳过异常边界: ${it.javaClass.simpleName}")
        })
        fun registerPage(type: Class<*>, key: String, personalFeed: Boolean) {
            val pageView = type.getMethod("getView")
            val visibleHint = if (personalFeed) type.getMethod("getUserVisibleHint") else null
            environment.registrar.exact("$key.create", type, "onViewCreated", View::class.java, Bundle::class.java) {
                after {
                    if (hasThrowable) return@after
                    val root = argOrNull(0) as? View ?: return@after
                    val target = instance
                    root.post {
                        if (root.isAttachedToWindow) {
                            controller.attach(root, personalFeed)
                            if (visibleHint != null) controller.visibility(root, visibleHint.invoke(target) == true)
                        }
                    }
                }
            }
            environment.registrar.exact("$key.destroy", type, "onDestroyView") {
                before { (instance?.let { pageView.invoke(it) } as? View)?.let(controller::close) }
            }
            for (event in listOf("onPause", "onResume")) {
                val method = type.getMethod(event)
                environment.registrar.exact("$key.$event", method.declaringClass, event) {
                    after {
                        val target = instance?.takeIf { type.isInstance(it) } ?: return@after
                        val root = pageView.invoke(target) as? View ?: return@after
                        if (event == "onPause") controller.pause(root) else controller.resume(root, personalFeed)
                    }
                }
            }
            if (personalFeed) environment.registrar.exact("$key.visibility", type,
                "setUserVisibleCompat", Boolean::class.javaPrimitiveType!!) {
                after {
                    val root = instance?.let { pageView.invoke(it) } as? View ?: return@after
                    controller.visibility(root, argOrNull(0) == true)
                }
            }
        }
        registerPage(fragment, "follow_feed", personalFeed = false)
        if (personalFragment != null && separatorDraw != null) {
            // 视频与全部动态都使用这个 Fragment；仅接入已审阅的 RelativeLayout 列表。
            registerPage(personalFragment, "follow_feed.personal", personalFeed = true)
            environment.registrar.exact("follow_feed.personal.separator", separatorDraw.declaringClass,
                separatorDraw.name, *separatorDraw.parameterTypes) {
                before {
                    val list = argOrNull(1) as? ViewGroup ?: return@before
                    if (controller.replacesPersonalSeparators(list)) result = null
                }
            }
        }
        if (headerPadding != null) environment.registrar.exact("follow_feed.header_padding", fragment,
            headerPadding.name, *headerPadding.parameterTypes) {
            before {
                val root = instance?.let { getView.invoke(it) } as? View ?: return@before
                val requested = argOrNull(0) as? Int ?: return@before
                args[0] = controller.headerPadding(root, requested)
            }
        }
        environment.registrar.exact("follow_feed.bind", adapter, "bindViewHolder", holder, Int::class.javaPrimitiveType!!) {
            after {
                if (hasThrowable) return@after
                val bound = argOrNull(0) ?: return@after
                val root = itemView.get(bound) as? View ?: return@after
                controller.bind(root, bound, argOrNull(1) as? Int ?: return@after)
            }
        }
        environment.registrar.exact("follow_feed.insets", recycler, "getItemDecorInsetsForChild", View::class.java) {
            after {
                val root = argOrNull(0) as? View ?: return@after
                val rect = result as? android.graphics.Rect ?: return@after
                controller.insets(root, rect)
            }
        }
        environment.registrar.exact("follow_feed.child_attach", recycler, "dispatchChildAttached", View::class.java) {
            after { (argOrNull(0) as? View)?.let(controller::childAttached) }
        }
        environment.registrar.exact("follow_feed.module_background", painter, paint.name, *paint.parameterTypes) {
            before {
                val list = argOrNull(1) as? ViewGroup ?: return@before
                val row = argOrNull(2) as? View ?: return@before
                if (controller.replacesModuleBackground(list, row)) result = null
            }
        }
        val pool = KavaMemberLookup.classOrNull(loader, "androidx.recyclerview.widget.RecyclerView\$Recycler")
        if (pool != null && KavaMemberLookup.methodOrNull(pool, "recycleViewHolderInternal", holder) != null) {
            environment.registrar.exact("follow_feed.recycle", pool, "recycleViewHolderInternal", holder) {
                before { argOrNull(0)?.let { itemView.get(it) as? View }?.let(controller::recycle) }
            }
        }
        val personalSupported = personalFragment != null && separatorDraw != null
        environment.logInfo("follow_feed_installed", "[BIL] 关注页凝光接入完成（卡片/分段无采样，状态栏按需融合，UP 主页=$personalSupported）")
        return FeatureInstallResult.Installed((if (headerPadding == null) 9 else 10) + if (personalSupported) 6 else 0)
    }
}
