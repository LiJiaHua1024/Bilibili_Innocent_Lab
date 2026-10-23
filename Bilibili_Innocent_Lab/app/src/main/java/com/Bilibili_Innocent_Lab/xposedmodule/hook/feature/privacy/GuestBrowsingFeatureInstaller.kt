package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.highcapable.kavaref.extension.isAbstract
import com.highcapable.kavaref.extension.isStatic
import com.highcapable.kavaref.extension.classOf
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstallResult
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureInstaller
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.FeatureRuntimeStage
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.reportRuntimeEvidence
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ModernHookParam
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 8.90.2 已核实的客户端游客提示入口，不改变账户状态、服务端响应或主动登录路由。
 * 暂停处理和底部登录引导尚未实现；八个锚点全部注册也不代表免登录浏览完整覆盖。
 */
internal class GuestBrowsingFeatureInstaller(
    private val enabled: Boolean,
    private val isGuest: () -> Boolean
) : FeatureInstaller {
    override val id = ID

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) return skipped(environment, "disabled")
        if (environment.processName != "tv.danmaku.bili") {
            return skipped(environment, "non-main-process")
        }
        val loader = environment.classLoader ?: return skipped(environment, "missing-class-loader")
        val observed = AtomicBoolean(false)
        val applied = AtomicBoolean(false)
        val guardFailed = AtomicBoolean(false)
        val missing = mutableListOf<String>()
        val failed = mutableListOf<String>()
        var installed = 0

        fun evidenceOnce(flag: AtomicBoolean, stage: FeatureRuntimeStage) {
            if (flag.compareAndSet(false, true)) environment.reportRuntimeEvidence(ID, stage)
        }

        fun register(
            path: String,
            method: Method?,
            returnValue: Any?,
            matches: ModernHookParam.() -> Boolean = { true }
        ) {
            if (method == null) {
                missing += path
                return
            }
            val readFailed = AtomicBoolean(false)
            val registered = runCatching {
                environment.registrar.exact(
                    "$ID.$path", method.declaringClass, method.name, *method.parameterTypes
                ) {
                    before {
                        // 不在安装时快照游客状态；登录/退出后无需重新注册。未知状态放行宿主。
                        if (!enabled) return@before
                        val guest = runCatching { isGuest() }.getOrElse {
                            if (guardFailed.compareAndSet(false, true)) {
                                runtimeFailure(environment, "guest-state")
                            }
                            false
                        }
                        if (!guest) return@before
                        val matched = runCatching { matches() }.getOrElse {
                            if (readFailed.compareAndSet(false, true)) {
                                runtimeFailure(environment, "$path-read")
                            }
                            false
                        }
                        if (!matched) return@before
                        evidenceOnce(observed, FeatureRuntimeStage.OBSERVED)
                        result = returnValue
                        // APPLIED 仅证明该客户端入口被短路，不证明 UI、分页或播放流已解锁。
                        evidenceOnce(applied, FeatureRuntimeStage.APPLIED)
                    }
                }
            }.isSuccess
            if (registered) installed++ else failed += path
        }

        val infoType = Lookup.classOrNull(loader, DIALOG_INFO)
        val contextType = Lookup.classOrNull(loader, "android.content.Context")
        val keyGetter = exactMethod(infoType, "getKey", classOf<String>(), isStatic = false)
        val addDialog = exactMethod(
            Lookup.classOrNull(loader, DIALOG_MANAGER), "addDialog", classOf<Boolean>(),
            true, infoType, contextType
        )
        // captures/comment-classic/source/.../MainDialogManager.java:148,172-174,273-313:
        // getKey() 读取 String mKey；所有拒绝分支在 b.add(info) 前返回 false，成功入队才返回 true。
        // 不回退到猜测字段，不碰其他 key（包括 login_guide_block / 系统权限与青少年提示）。
        register("login-dialog", addDialog.takeIf { keyGetter != null }, false) {
            val info = argOrNull(0)
            info != null && infoType!!.isInstance(info) && keyGetter!!.invoke(info) == "login_guide"
        }

        // host-current.apk classes31.dex: private final w(String, FragmentActivity, boolean):void
        // 构造 key=login_guide 的 DialogManagerInfo 后调用 addDialog；不是主动登录入口。
        register(
            "one-pass-guide",
            exactMethod(
                Lookup.classOrNull(loader, ONE_PASS_COMPANION), "w", Void.TYPE, false,
                classOf<String>(), Lookup.classOrNull(loader, "androidx.fragment.app.FragmentActivity"),
                classOf<Boolean>()
            ),
            null
        )

        // classes18.dex lp3.a: run() 检查 isLogin / PlayIndex.mNeedLogin，显示 force_login_toast。
        // a() 排程、c() 清理：只拦 run 入口，不破坏清理，也不宣称修复暂停或解锁服务端流。
        register(
            "force-login-toast",
            exactMethod(Lookup.classOrNull(loader, "lp3.a"), "run", Void.TYPE, false),
            null
        )

        // classes6.dex / CommentSubjectDescription.java:1348-1350,1390: k() 返回 i=guestLimit。
        // 只取消客户端分页遮罩判定；不修改模型、返回数据、hasNext 或网络请求。
        register(
            "comment-guest-limit",
            exactMethod(
                Lookup.classOrNull(loader, COMMENT_DESCRIPTION), "k", classOf<Boolean>(),
                false
            ),
            false
        )


        // Verified 8.90.2 experiments: j() otherwise clamps a guest to min(i(), h()).
        register("guest-quality-cap", exactMethod(Lookup.classOrNull(loader, "gh6.f"), "h",
            classOf<Int>(), true), Int.MAX_VALUE)
        register("force-login-experiment", exactMethod(Lookup.classOrNull(loader, "gh6.f"), "a",
            classOf<Boolean>(), false), false)
        register("overseas-forced-login", exactMethod(
            Lookup.classOrNull(loader, "tv.danmaku.bili.ui.main2.HomeLoginExpKt"), "n",
            classOf<Boolean>(), true), false)
        register("one-pass-guide-alternate", exactMethod(
            Lookup.classOrNull(loader, ONE_PASS_COMPANION), "x", Void.TYPE, true,
            classOf<String>(), Lookup.classOrNull(loader, "androidx.fragment.app.FragmentActivity"),
            classOf<Boolean>()), null)
        // 八处锚点注册数与完整功能覆盖是两回事，禁止报 success 或 complete=true。
        runCatching {
            environment.reportStatus(STATUS, "partial:$installed/8")
            environment.logInfo(
                "${ID}_coverage",
                "[BIL] 游客浏览部分覆盖: hooks=$installed/8; missing=${missing.joinToString()}; " +
                    "registration-failed=${failed.joinToString()}; " +
                    "unimplemented=playback-pause,bottom-login-guide"
            )
        }
        if (installed == 0) {
            return FeatureInstallResult.Skipped(
                if (failed.isNotEmpty()) "registration-failed" else "missing-host-structure"
            )
        }
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
        return FeatureInstallResult.Installed(installed, complete = false)
    }

    /** 当前声明类、精确参数/返回类型与静态性；不扫描父类或选取同名重载作为回退。 */
    private fun exactMethod(
        owner: Class<*>?,
        name: String,
        returnType: Class<*>,
        isStatic: Boolean,
        vararg parameters: Class<*>?
    ): Method? {
        if (owner == null || parameters.any { it == null }) return null
        return Lookup.methodOrNull(owner, name, *parameters.filterNotNull().toTypedArray())?.takeIf {
            it.declaringClass == owner && it.returnType == returnType &&
                it.isStatic == isStatic && !it.isAbstract
        }
    }

    private fun runtimeFailure(environment: HookEnvironment, reason: String) {
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ERROR)
        runCatching {
            environment.logError("${ID}_$reason", "[BIL] 游客浏览状态/结构读取失败，已放行宿主: $reason")
        }
    }

    private fun skipped(environment: HookEnvironment, reason: String): FeatureInstallResult.Skipped {
        runCatching { environment.reportStatus(STATUS, reason) }
        return FeatureInstallResult.Skipped(reason)
    }

    companion object {
        const val ID = "guest_browsing"
        private const val STATUS = "guest_browsing_status"
        private const val DIALOG_MANAGER = "com.bilibili.app.dialogmanager.MainDialogManager"
        private const val DIALOG_INFO = "$DIALOG_MANAGER\$DialogManagerInfo"
        private const val ONE_PASS_COMPANION =
            "tv.danmaku.bili.ui.login.OnePassLoginGuideHelperV2\$Companion"
        private const val COMMENT_DESCRIPTION = "com.bilibili.app.comment3.data.model.CommentSubjectDescription"
    }
}
