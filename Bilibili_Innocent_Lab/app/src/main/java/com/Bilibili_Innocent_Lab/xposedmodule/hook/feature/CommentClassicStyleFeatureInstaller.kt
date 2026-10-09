package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isStatic

/**
 * 恢复宿主自带的旧版评论布局与楼中楼预览。
 *
 * 宿主按 next_appearance / next_appearance_experiment_3 选择评论 Holder，
 * 数据转换也读取同一组实验位；同时在后台主列表转换前补齐服务端省略的楼中楼预览。
 * 实验结果有进程级 Lazy 缓存，必须冷启动生效。
 */
internal class CommentClassicStyleFeatureInstaller(
    private val enabled: Boolean
) : FeatureInstaller {
    override val id: String = ID

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) {
            environment.reportStatus(CHANNEL_STATUS, "disabled")
            return FeatureInstallResult.Skipped("disabled")
        }
        if (environment.processName != TARGET_PACKAGE) {
            return FeatureInstallResult.Skipped("non-main-process")
        }
        val owner = KavaMemberLookup.classOrNull(environment.classLoader, DEVICE_DECISION_CLASS)
            ?: return missing(environment, "missing-config-boundary")
        val methods = KavaMemberLookup.declaredMethods(owner, makeAccessible = true) { method ->
            !method.isStatic && method.name == "getBoolean" &&
                method.returnType == classOf<Boolean>() &&
                method.parameterTypes.firstOrNull() == classOf<String>() &&
                method.parameterTypes.getOrNull(1) == classOf<Boolean>()
        }
        if (methods.isEmpty()) return missing(environment, "missing-config-boundary")

        var installed = 0
        methods.forEachIndexed { index, method ->
            runCatching {
                environment.registrar.exact(
                    "comment.classic.dd.$index", method.declaringClass, method.name,
                    *method.parameterTypes
                ) {
                    before {
                        // DeviceDecision 是全局热路径：未命中只比较 key，不复制参数或分配集合。
                        if (!isStyleExperimentKey(argOrNull(0))) return@before
                        result = false
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                    }
                }
                installed += 1
            }.onFailure { throwable ->
                environment.logError("comment_classic_register_$index", "[BIL] 旧版评论样式 Hook 注册失败: $throwable")
            }
        }
        if (installed == 0) return missing(environment, "registration-failed")
        val (previewHooks, previewComplete) = installPreviews(environment)
        val complete = installed == methods.size && previewComplete
        installed += previewHooks
        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
        environment.reportStatus(CHANNEL_STATUS, if (complete) "success" else "partial:style-and-preview")
        environment.logInfo("comment_classic_installed", "[BIL] 旧版评论已安装，hooks=$installed，previewHooks=$previewHooks")
        return FeatureInstallResult.Installed(installed, complete)
    }

    private fun installPreviews(environment: HookEnvironment): Pair<Int, Boolean> {
        val loader = environment.classLoader ?: return 0 to false
        val host = CommentReplyPreviewHost.resolve(loader, onFailure = {
            environment.logError("comment_classic_preview_shape", "[BIL] 评论预览接口解析失败: $it")
        })
        val mappers = CommentReplyPreviewHost.mainListMappers(loader)
        if (host == null || mappers.isEmpty()) {
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
                        runCatching { restorer.restore(original) }.onSuccess { args[0] = it }.onFailure {
                            environment.logError("comment_classic_preview_restore", "[BIL] 评论预览转换失败，保留原响应: $it")
                        }
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
        private const val DEVICE_DECISION_CLASS = "com.bilibili.lib.dd.DeviceDecision"

        internal fun isStyleExperimentKey(key: Any?): Boolean =
            key == "comment.next_appearance" || key == "comment.next_appearance_experiment_3"
    }
}
