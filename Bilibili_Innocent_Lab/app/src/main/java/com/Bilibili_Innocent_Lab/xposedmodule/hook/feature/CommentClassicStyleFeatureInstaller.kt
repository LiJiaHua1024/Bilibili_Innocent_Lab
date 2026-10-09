package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.CommentClassicStyleLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.CommentClassicStyleLocator.Family
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.CommentClassicStylePoints
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 恢复宿主自带的旧版评论布局与楼中楼预览。
 *
 * 先关闭 kntr 容器实验，再按 next_appearance / next_appearance_experiment_3 恢复原生 Holder，
 * 数据转换也读取同一组实验位；同时在后台主列表转换前补齐服务端省略的楼中楼预览。
 * 新宿主内联转换器后，在主列表 fetch 协程的响应恢复边界处理相同 protobuf。
 * 实验结果有进程级 Lazy 缓存，必须冷启动生效。
 */
internal class CommentClassicStyleFeatureInstaller(
    private val enabled: Boolean,
    private val cachedPoints: () -> CommentClassicStylePoints? = { null }
) : FeatureInstaller {
    override val id: String = ID
    @Volatile var requiresAdaptationRetry = false
        private set
    private val registered = linkedSetOf<Method>()
    private var previewResult: Pair<Int, Boolean>? = null
    @Volatile private var nativeFallbackReady = false
    private val observedFamily = Array(Family.entries.size) { AtomicBoolean(false) }

    @Synchronized
    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) {
            environment.reportStatus(CHANNEL_STATUS, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }
        if (environment.processName != TARGET_PACKAGE) {
            return FeatureInstallResult.Skipped("non-main-process")
        }
        val loader = environment.classLoader ?: return missing(environment, "missing-config-boundary")
        if (!KavaMemberLookup.hasClass(loader, CommentClassicStyleLocator.NATIVE_ENTRY))
            return missing(environment, "missing-legacy-container")
        val cache = cachedPoints()
        val families = if (CommentClassicStyleLocator.kotlinApplicable(loader)) Family.entries else listOf(Family.NATIVE)
        val readers = families.associateWith { CommentClassicStyleLocator.readers(loader, cache, it) }
        requiresAdaptationRetry = readers.values.any { it.isEmpty() }
        readers.forEach { (family, methods) ->
            methods.forEachIndexed { index, method ->
                if (method in registered) return@forEachIndexed
                val observedKey = "comment_classic_observed_${family.name}"
                val observedMessage = "[BIL] 旧版评论配置读取已拦截(family=${family.name})；不代表当前页面已切换布局"
                runCatching {
                    environment.registrar.exact(
                        "comment.classic.${if (family == Family.NATIVE) "dd" else "kotlin"}.$index", method.declaringClass, method.name,
                        *method.parameterTypes
                    ) {
                        before {
                            // DeviceDecision 是全局热路径：未命中只比较 key，不复制参数或分配集合。
                            val key = argOrNull(family.keyIndex)
                            if (!CommentClassicStyleLocator.matchesKey(key) ||
                                CommentClassicStyleLocator.isContainerKey(key) && !nativeFallbackReady) return@before
                            result = false
                            environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                            environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                            if (observedFamily[family.ordinal].compareAndSet(false, true))
                                environment.logInfo(observedKey, observedMessage)
                        }
                    }
                    registered += method
                }.onFailure { throwable ->
                    environment.logError("comment_classic_register_$index", "[BIL] 旧版评论样式 Hook 注册失败: $throwable")
                }
            }
            if (family == Family.NATIVE) nativeFallbackReady = methods.isNotEmpty() && methods.all(registered::contains)
        }
        if (registered.isEmpty()) return missing(environment, if (requiresAdaptationRetry) "missing-config-boundary" else "registration-failed")
        val (previewHooks, previewComplete) = previewResult ?: installPreviews(environment).also { previewResult = it }
        val complete = readers.values.all { it.isNotEmpty() && it.all(registered::contains) } && previewComplete
        val installed = registered.size + previewHooks
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
        environment.reportStatus(CHANNEL_STATUS, if (complete) "success" else "partial:style-and-preview")
        environment.logInfo("comment_classic_installed", "[BIL] 旧版评论已安装，hooks=$installed，previewHooks=$previewHooks")
        if (!complete) environment.logInfo("comment_classic_partial", "[BIL] 旧版评论部分接入：native=${readers[Family.NATIVE]?.count(registered::contains) ?: 0}, kotlin=${readers[Family.KOTLIN]?.count(registered::contains) ?: 0}, preview=$previewComplete；补齐适配后需冷启动")
        return FeatureInstallResult.Installed(installed, complete)
    }

    private fun installPreviews(environment: HookEnvironment): Pair<Int, Boolean> {
        val loader = environment.classLoader ?: return 0 to false
        val host = CommentReplyPreviewHost.resolve(loader, onFailure = {
            environment.logError("comment_classic_preview_shape", "[BIL] 评论预览接口解析失败: $it")
        })
        val mappers = CommentReplyPreviewHost.mainListMappers(loader)
        val coroutine = if (mappers.isEmpty()) CommentReplyPreviewCoroutineBoundary.resolve(loader) else null
        if (host == null || (mappers.isEmpty() && coroutine == null)) {
            environment.logError("comment_classic_preview_missing", "[BIL] 评论预览补取边界缺失，当前仅恢复旧版布局")
            return 0 to false
        }
        val restorer = CommentReplyPreviewRestorer(host,
            observed = { environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED, it) },
            applied = {
                environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED, it)
                environment.logInfo("comment_classic_preview_applied", "[BIL] 已补齐 $it 条主评论的楼中楼预览")
            },
            failure = { environment.logError("comment_classic_preview_request", "[BIL] 评论预览补取失败: $it") }
        )
        fun restore(original: Any): Any = runCatching { restorer.restore(original) }.getOrElse {
            environment.logError("comment_classic_preview_restore", "[BIL] 评论预览转换失败，保留原响应: $it")
            original
        }
        if (coroutine != null) return coroutine.install(environment, ::restore)
        var installed = 0
        mappers.forEachIndexed { index, method ->
            runCatching {
                environment.registrar.exact("comment.classic.preview.$index", method.declaringClass,
                    method.name, *method.parameterTypes) {
                    before {
                        // 8.90.2 的 MainListDataSourceV1 在 Dispatchers.Default 调用该转换器。
                        // 新版本若改到主线程则保留原响应，不能让补取阻塞界面。
                        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return@before
                        val original = argOrNull(0) ?: return@before
                        args[0] = restore(original)
                    }
                }
                installed++
            }.onFailure {
                environment.logError("comment_classic_preview_register", "[BIL] 评论预览 Hook 注册失败: $it")
            }
        }
        return installed to (installed == mappers.size)
    }

    private fun missing(environment: HookEnvironment, reason: String): FeatureInstallResult.Skipped {
        environment.reportStatus(CHANNEL_STATUS, reason)
        environment.logError("comment_classic_missing", "[BIL] 旧版评论样式适配不完整: $reason")
        return FeatureInstallResult.Skipped(reason)
    }

    companion object {
        const val ID = "comment_classic_style"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL_STATUS = "comment_classic_style_status"

        internal fun isStyleExperimentKey(key: Any?): Boolean =
            key == "comment.next_appearance" || key == "comment.next_appearance_experiment_3"
    }
}
