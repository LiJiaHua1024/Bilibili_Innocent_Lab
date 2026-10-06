package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.isStatic
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isSubclassOf
import java.lang.reflect.Constructor
import java.lang.reflect.Field

/** 只对四份不可变可选装饰校准空实例；失败时保留宿主构造参数，避免未验证的 null 注入。 */
internal fun nullableCommentDecoration(constructor: Constructor<*>, fields: List<Field>): Boolean =
    runCatching {
        val empty = constructor.newInstance(*arrayOfNulls<Any>(constructor.parameterCount))
        fields.all { it.get(empty) == null }
    }.getOrDefault(false)

internal fun mergeCommentLayer(base: FeatureInstallResult, extra: FeatureInstallResult?): FeatureInstallResult {
    if (extra == null) return base
    if (base is FeatureInstallResult.Installed) return FeatureInstallResult.Installed(
        base.hookCount + ((extra as? FeatureInstallResult.Installed)?.hookCount ?: 0),
        base.complete && extra is FeatureInstallResult.Installed && extra.complete
    )
    if (extra is FeatureInstallResult.Installed) return FeatureInstallResult.Installed(extra.hookCount, complete = false)
    return base
}

/** Compose 的独立组件与动作；不清整份 UserInfo、活动或评论模型。 */
internal class CommentComposePurifyBridge(
    private val vote: Boolean,
    private val follow: Boolean,
    private val quickReply: Boolean
) {
    fun install(environment: HookEnvironment): Map<String, FeatureInstallResult> {
        val loader = environment.classLoader ?: return emptyMap()
        if (KavaMemberLookup.classOrNull(loader, "$MODEL_PACKAGE.CommentModel") == null) return emptyMap()
        val results = linkedMapOf<String, FeatureInstallResult>()
        if (vote) {
            val renderer = KavaMemberLookup.classOrNull(loader, "$RENDER_PACKAGE.CommentVoteRenderer")
            val element = KavaMemberLookup.classOrNull(loader, "$MODEL_PACKAGE.CommentElementType")
            val hasVoteElement = element?.enumConstants.orEmpty().any { (it as? Enum<*>)?.name == "VOTE" }
            val methods = renderer?.let { owner -> KavaMemberLookup.declaredMethods(owner).filter {
                it.returnType == Void.TYPE && it.parameterTypes.any { type -> type.name == COMPOSER }
            } }.orEmpty()
            var installed = 0
            methods.forEach { method ->
                runCatching {
                    environment.registrar.exact("comment.compose.vote.${method.name}", method.declaringClass, method.name, *method.parameterTypes) {
                        before {
                            environment.reportRuntimeEvidence(VOTE, FeatureRuntimeStage.OBSERVED)
                            result = null
                            environment.reportRuntimeEvidence(VOTE, FeatureRuntimeStage.APPLIED)
                        }
                    }
                    installed++
                }
            }
            if (renderer != null || hasVoteElement) results[VOTE] = outcome(installed, methods.size)
        }
        if (follow) {
            val user = KavaMemberLookup.classOrNull(loader, "$MODEL_PACKAGE.CommentUserInfoRendererModel")
            val decoration = user?.let { type ->
                KavaMemberLookup.declaredFields(type).map { it.type }.distinct().singleOrNull { candidate ->
                    if (!candidate.name.startsWith(type.name + "$")) return@singleOrNull false
                    val fields = KavaMemberLookup.declaredFields(candidate).filterNot { it.isStatic }
                    fields.size == 4 && fields.map { it.name }.toSet() == setOf("a", "b", "c", "d") &&
                        fields.all { !it.type.isPrimitive && it.type.name.startsWith(type.name + "$") }
                }
            }
            val followed = decoration?.let { KavaMemberLookup.fieldOrNull(it, "b") }
            val constructors = decoration?.let { KavaMemberLookup.declaredConstructors(it).filter {
                it.parameterCount == 4 && it.parameterTypes[1] == followed?.type
            } }.orEmpty()
            var installed = 0
            constructors.forEach { constructor ->
                val slots = KavaMemberLookup.declaredFields(constructor.declaringClass, makeAccessible = true)
                    .filterNot { it.isStatic }
                if (!nullableCommentDecoration(constructor, slots)) return@forEach
                runCatching {
                    environment.registrar.constructor("comment.compose.follow.${constructor.declaringClass.name}", constructor) {
                        before {
                            // 只清源码核对为可空的 follow 装饰槽；其它三种装饰原样保留。
                            val value = argOrNull(1) ?: return@before
                            if (!value.toString().startsWith("Follow(mid=")) return@before
                            environment.reportRuntimeEvidence(FOLLOW, FeatureRuntimeStage.OBSERVED)
                            setObjectExtra("comment.follow.cleared", true)
                            args[1] = null
                        }
                        after {
                            if (!hasThrowable && getObjectExtra("comment.follow.cleared") == true &&
                                instance?.let { followed?.get(it) } == null
                            ) environment.reportRuntimeEvidence(FOLLOW, FeatureRuntimeStage.APPLIED)
                        }
                    }
                    installed++
                }
            }
            val withoutFollow = user?.let { type -> KavaMemberLookup.declaredFields(type).any { field ->
                val candidate = field.type
                if (!candidate.name.startsWith(type.name + "$")) return@any false
                val fields = KavaMemberLookup.declaredFields(candidate).filterNot { it.isStatic }
                fields.size == 3 && fields.map { it.name }.toSet() == setOf("a", "b", "c") &&
                    fields.all { !it.type.isPrimitive && it.type.name.startsWith(type.name + "$") }
            } } == true
            if (decoration != null || !withoutFollow) results[FOLLOW] = outcome(installed, constructors.size)
        }
        if (quickReply) {
            val action = KavaMemberLookup.classOrNull(loader, ACTION)
            val replyTypes = action?.declaredClasses.orEmpty().filter { candidate ->
                val fields = KavaMemberLookup.declaredFields(candidate).filterNot { it.isStatic }
                fields.size in 8..9 && fields.count { it.type == classOf<Long>() } == 5 &&
                    fields.count { it.type == classOf<String>() } == 2
            }
            val stores = if (replyTypes.isEmpty()) emptyList() else (listOf("CardStore") + ('a'..'z').map(Char::toString)).mapNotNull {
                KavaMemberLookup.classOrNull(loader, "kntr.common.comment.card.store.$it")
            }
            val methods = if (action == null) emptyList() else stores.flatMap { owner ->
                KavaMemberLookup.declaredMethods(owner).filter {
                    !it.isStatic && it.returnType == Void.TYPE && it.parameterCount == 1 &&
                        it.parameterTypes[0] != classOf<Any>() && action isSubclassOf it.parameterTypes[0]
                }
            }
            var installed = 0
            methods.forEach { method ->
                runCatching {
                    environment.registrar.exact("comment.compose.quick_reply.${method.declaringClass.name}", method.declaringClass, method.name, *method.parameterTypes) {
                        before {
                            val value = argOrNull(0)?.takeIf { request -> replyTypes.any { it.isInstance(request) } }
                                ?: return@before
                            // ClickCardReply 是点击正文触发的快捷回复；显式回复和头像动作不同。
                            if (!value.toString().startsWith("ClickCard(")) return@before
                            environment.reportRuntimeEvidence(QUICK, FeatureRuntimeStage.OBSERVED)
                            result = null
                            environment.reportRuntimeEvidence(QUICK, FeatureRuntimeStage.APPLIED)
                        }
                    }
                    installed++
                }
            }
            // 更早的 KMP 没有这份快捷回复请求模型，普通点击进入回复详情必须保留。
            if (replyTypes.isNotEmpty()) results[QUICK] = outcome(installed, methods.size)
        }
        results.forEach { (capability, result) ->
            if (result !is FeatureInstallResult.Installed || !result.complete) {
                environment.logError("${capability}_compose_missing", "[BIL] $capability 的 Compose 层未完整接入")
            }
        }
        return results
    }

    private fun outcome(installed: Int, expected: Int): FeatureInstallResult =
        if (installed == 0) FeatureInstallResult.Skipped("missing-compose-structure")
        else FeatureInstallResult.Installed(installed, installed == expected)

    private companion object {
        const val MODEL_PACKAGE = "kntr.common.comment.card.model.comment"
        const val RENDER_PACKAGE = "kntr.common.comment.card.renderer.comment"
        const val ACTION = "kntr.common.comment.card.action.CardAction"
        const val COMPOSER = "androidx.compose.runtime.Composer"
        const val VOTE = "comments_vote_widgets_removed"
        const val FOLLOW = "comments_follow_buttons_removed"
        const val QUICK = "comments_quick_reply_blocked"
    }
}
