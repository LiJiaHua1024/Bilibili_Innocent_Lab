package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.os.Looper
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isStatic
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 8.97.0 起主列表转换被 R8 内联到 fetch 协程。该协程由 Dispatchers.Default 调度，
 * label=1 的恢复参数仍为 Java MainListReply；请求直接返回时则在 suspendMainList 返回处处理。
 * 两个入口共用类型、协程实例和状态检查，不拦截其它评论请求，不等待 MOSS 收包线程。
 */
internal class CommentReplyPreviewCoroutineBoundary private constructor(
    private val responseClass: Class<*>,
    private val coroutineClass: Class<*>,
    private val label: Field,
    private val resume: Method,
    private val immediate: Method
) {
    internal fun update(receiver: Any?, response: Any?, mainThread: Boolean, restore: (Any) -> Any): Any? {
        if (mainThread || receiver == null || !coroutineClass.isInstance(receiver) ||
            response == null || !responseClass.isInstance(response) ||
            runCatching { label.getInt(receiver) }.getOrNull() != 1) return response
        return restore(response)
    }

    fun install(
        environment: HookEnvironment,
        restore: (Any) -> Any,
        mainThread: () -> Boolean = { Looper.myLooper() == Looper.getMainLooper() }
    ): Pair<Int, Boolean> {
        var installed = 0
        fun register(id: String, method: Method, resumed: Boolean) {
            runCatching {
                environment.registrar.exact(id, method.declaringClass, method.name, *method.parameterTypes) {
                    if (resumed) before {
                        val original = argOrNull(0) ?: return@before
                        args[0] = update(instance, original, mainThread(), restore)
                    } else after {
                        if (hasThrowable) return@after
                        val original = result ?: return@after
                        val updated = update(argOrNull(2), original, mainThread(), restore)
                        if (updated !== original) result = updated
                    }
                }
                installed++
            }.onFailure {
                environment.logError("comment_classic_coroutine_register", "[BIL] 评论预览协程 Hook 注册失败: $it")
            }
        }
        register("comment.classic.preview.resume", resume, true)
        register("comment.classic.preview.immediate", immediate, false)
        return installed to (installed == 2)
    }

    companion object {
        const val COROUTINE_CLASS = "com.bilibili.app.comment3.data.source.v1.MainListDataSourceV1\$fetch\$2"
        private const val RPC_CLASS = "com.bapis.bilibili.main.community.reply.v1.ReplyMossKtxKt"

        fun resolve(
            loader: ClassLoader,
            replyPackage: String = CommentReplyPreviewHost.REPLY_PACKAGE,
            coroutineName: String = COROUTINE_CLASS,
            rpcName: String = RPC_CLASS
        ): CommentReplyPreviewCoroutineBoundary? = runCatching {
            val response = checkNotNull(KavaMemberLookup.classOrNull(loader, "$replyPackage.MainListReply"))
            val coroutine = checkNotNull(KavaMemberLookup.classOrNull(loader, coroutineName))
            check(coroutine.superclass?.name == "kotlin.coroutines.jvm.internal.SuspendLambda")
            val label = checkNotNull(KavaMemberLookup.fieldOrNull(coroutine, "label"))
            check(!java.lang.reflect.Modifier.isStatic(label.modifiers) && label.type == classOf<Int>())
            val resume = checkNotNull(KavaMemberLookup.methodOrNull(coroutine, "invokeSuspend", classOf<Any>()))
            check(!resume.isStatic && !resume.isSynthetic && resume.returnType == classOf<Any>())
            val rpc = checkNotNull(KavaMemberLookup.classOrNull(loader, rpcName))
            val moss = checkNotNull(KavaMemberLookup.classOrNull(loader, "$replyPackage.ReplyMoss"))
            val request = checkNotNull(KavaMemberLookup.classOrNull(loader, "$replyPackage.MainListReq"))
            val continuation = checkNotNull(KavaMemberLookup.classOrNull(loader, "kotlin.coroutines.Continuation"))
            val immediate = checkNotNull(KavaMemberLookup.methodOrNull(rpc, "suspendMainList", moss, request, continuation))
            check(immediate.isStatic && immediate.returnType == classOf<Any>())
            CommentReplyPreviewCoroutineBoundary(response, coroutine, label, resume, immediate)
        }.getOrNull()
    }
}
