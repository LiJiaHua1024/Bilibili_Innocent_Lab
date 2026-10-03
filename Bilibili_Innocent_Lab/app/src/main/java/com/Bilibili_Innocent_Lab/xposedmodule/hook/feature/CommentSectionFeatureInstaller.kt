package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.VersionAdapter
import java.lang.reflect.Method

/** 在详情页 Tab 配置进入宿主前移除 LocatableTag.Comment，不扫描或隐藏全局 View。 */
internal class CommentSectionFeatureInstaller(
    private val enabled: Boolean,
    private val points: VersionAdapter.CommentSectionPoints?
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
        val adapted = points ?: return missing(environment, "missing-adapter-point")
        val tagGetter = environment.hookPoints.resolveAdapted(
            "comment.section.resolve.tag",
            adapted.locatableTagGetter.className,
            adapted.locatableTagGetter.methodName,
            adapted.locatableTagGetter.paramClassNames
        ) ?: return missing(environment, "missing-tag-getter")

        var installed = 0
        adapted.listConstructors.forEachIndexed { index, point ->
            val constructor = environment.hookPoints.resolveConstructor(
                "comment.section.resolve.constructor.$index",
                point.className,
                point.paramClassNames
            ) ?: return@forEachIndexed
            runCatching {
                environment.registrar.constructor("comment.section.constructor.$index", constructor) {
                    before {
                        val source = args.getOrNull(point.listParameterIndex) as? List<*>
                            ?: return@before
                        val filtered = CopyOnFilter.list(source) { item ->
                            isCommentTab(item, tagGetter)
                        }
                        if (filtered !== source) {
                            args[point.listParameterIndex] = filtered
                            environment.logInfo(
                                "comment_section_removed",
                                "[BIL] 已从详情页 Tab 配置移除评论区"
                            )
                        }
                    }
                }
                installed += 1
            }.onFailure { throwable ->
                environment.logError(
                    "comment_section_constructor_$index",
                    "[BIL] 评论区隐藏 Hook 注册失败(${point.className}): $throwable"
                )
            }
        }
        if (installed == 0) return missing(environment, "registration-failed")
        // 适配出的每个构造器都是本宿主确实存在的落点：解析或注册失败必须让
        // complete 掉成 false，否则部分宿主路径静默漏挂却显示"已就绪"。
        val expected = adapted.listConstructors.size
        val complete = installed == expected
        environment.reportStatus(CHANNEL_STATUS, if (complete) "success" else "partial:$installed/$expected")
        return FeatureInstallResult.Installed(installed, complete = complete)
    }

    private fun isCommentTab(item: Any, tagGetter: Method): Boolean {
        if (!tagGetter.declaringClass.isInstance(item)) return false
        val tag = runCatching { tagGetter.invoke(item) as? Enum<*> }.getOrNull() ?: return false
        return isCommentTag(tag)
    }

    private fun missing(
        environment: HookEnvironment,
        reason: String
    ): FeatureInstallResult.Skipped {
        environment.reportStatus(CHANNEL_STATUS, reason)
        environment.logError("comment_section_missing", "[BIL] 评论区隐藏适配不完整: $reason")
        return FeatureInstallResult.Skipped(reason)
    }

    companion object {
        const val ID = "comment_section"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
        private const val CHANNEL_STATUS = "comment_section_status"
        private const val COMMENT_TAG = "Comment"

        internal fun isCommentTag(tag: Enum<*>?): Boolean =
            tag?.name?.equals(COMMENT_TAG, ignoreCase = true) == true
    }
}
