package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.HostThreadGuard
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isStatic
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

/** 只使用宿主公开 protobuf/MOSS API，不修改私有字段，不重建登录信息。 */
internal class CommentReplyPreviewHost private constructor(
    override val replyInfoClass: Class<*>,
    private val id: Method,
    private val oid: Method,
    private val type: Method,
    private val root: Method,
    private val count: Method,
    private val children: Method,
    private val control: Method,
    private val invisible: Method,
    private val blocked: Method,
    private val folded: Method,
    private val plan: ProtobufBuilderPlan,
    private val clearReplies: Method,
    private val addReplies: Method,
    private val fetch: (CommentReplyPreviewKey, (Result<Any>) -> Unit) -> Unit,
    private val responseRoot: Method
) : CommentReplyPreviewPort {
    private fun number(method: Method, value: Any) = (method.invoke(value) as Number).toLong()

    private fun visible(reply: Any): Boolean {
        val flags = control.invoke(reply)
        return invisible.invoke(flags) != true && blocked.invoke(flags) != true && folded.invoke(flags) != true
    }

    override fun missingPreviewKey(reply: Any): CommentReplyPreviewKey? = KotlinMossChannel.raw {
        if (number(root, reply) != 0L || number(count, reply) <= 0L || !visible(reply)) return@raw null
        if ((children.invoke(reply) as List<*>).isNotEmpty()) return@raw null
        CommentReplyPreviewKey(number(oid, reply), number(type, reply), number(id, reply), number(count, reply))
            .takeIf { it.oid > 0 && it.type >= 0 && it.root > 0 }
    }

    override fun withReplies(reply: Any, replies: List<Any>): Any = plan.edit(reply) { builder ->
        clearReplies.invoke(builder)
        addReplies.invoke(builder, replies)
    }

    override fun request(key: CommentReplyPreviewKey, complete: (Result<List<Any>>) -> Unit) {
        fetch(key) { result ->
            complete(result.mapCatching { response ->
                KotlinMossChannel.raw {
                    val parent = checkNotNull(responseRoot.invoke(response))
                    check(number(id, parent) == key.root && number(oid, parent) == key.oid &&
                        number(type, parent) == key.type && number(root, parent) == 0L) { "preview-thread-mismatch" }
                    if (!visible(parent)) return@raw emptyList()
                    (children.invoke(parent) as List<*>).filterNotNull().asSequence()
                        .filter { replyInfoClass.isInstance(it) && number(id, it) > 0L &&
                            number(root, it) == key.root && number(oid, it) == key.oid &&
                            number(type, it) == key.type && visible(it) }
                        .distinctBy { number(id, it) }
                        .take(minOf(CommentReplyPreviewRestorer.MAX_PREVIEW_REPLIES.toLong(), key.count).toInt())
                        .toList()
                }
            })
        }
    }

    companion object {
        const val REPLY_PACKAGE = "com.bapis.bilibili.main.community.reply.v1"

        fun resolve(
            loader: ClassLoader,
            replyPackage: String = REPLY_PACKAGE,
            paginationClassName: String = "com.bapis.bilibili.pagination.FeedPagination",
            onFailure: (Throwable) -> Unit = {}
        ): CommentReplyPreviewHost? = runCatching {
            fun owner(name: String) = checkNotNull(KavaMemberLookup.classOrNull(loader, "$replyPackage.$name"))
            fun getter(owner: Class<*>, name: String) = checkNotNull(KavaMemberLookup.inheritedMethodOrNull(owner, name))
            val reply = owner("ReplyInfo")
            val control = getter(reply, "getReplyControl")
            val plan = checkNotNull(ProtobufBuilderPlan.resolve(reply))
            val requestClass = owner("DetailListReq")
            val factory = getter(requestClass, "newBuilder")
            val builderClass = factory.returnType
            fun setter(name: String, parameter: Class<*>) =
                checkNotNull(KavaMemberLookup.inheritedMethodOrNull(builderClass, name, parameter))
            val setOid = setter("setOid", classOf<Long>())
            val setType = setter("setType", classOf<Long>())
            val setRoot = setter("setRoot", classOf<Long>())
            val setRpid = setter("setRpid", classOf<Long>())
            val setMode = setter("setModeValue", classOf<Int>())
            val build = getter(builderClass, "build")
            val pagination = checkNotNull(KavaMemberLookup.classOrNull(loader, paginationClassName))
            val pageFactory = getter(pagination, "newBuilder")
            val pageOffset = checkNotNull(KavaMemberLookup.inheritedMethodOrNull(pageFactory.returnType, "setOffset", classOf<String>()))
            val pageBuild = getter(pageFactory.returnType, "build")
            val setPagination = setter("setPagination", pagination)
            val moss = owner("ReplyMoss")
            val constructor = checkNotNull(KavaMemberLookup.constructorOrNull(moss))
            val rpc = KavaMemberLookup.declaredMethods(moss, makeAccessible = true) {
                !it.isStatic && it.name == "detailList" && it.parameterCount == 2 &&
                    it.parameterTypes[0] == requestClass && it.parameterTypes[1].isInterface && it.returnType == Void.TYPE
            }.single()
            val fetch: (CommentReplyPreviewKey, (Result<Any>) -> Unit) -> Unit = { key, complete ->
                val page = checkNotNull(pageFactory.invoke(null))
                pageOffset.invoke(page, "")
                val builder = checkNotNull(factory.invoke(null))
                setOid.invoke(builder, key.oid)
                setType.invoke(builder, key.type)
                setRoot.invoke(builder, key.root)
                setRpid.invoke(builder, 0L)
                setMode.invoke(builder, 0)
                setPagination.invoke(builder, pageBuild.invoke(page))
                val request = checkNotNull(build.invoke(builder))
                val handlerClass = rpc.parameterTypes[1]
                val finished = AtomicBoolean()
                var received: Any? = null
                fun finish(result: Result<Any>) {
                    if (finished.compareAndSet(false, true)) complete(result)
                }
                val handler = Proxy.newProxyInstance(handlerClass.classLoader, arrayOf(handlerClass)) { proxy, method, args ->
                    var value: Any? = hostProxyDefaultValue(method.returnType)
                    HostThreadGuard.run("comment_preview_moss") {
                        when (method.name) {
                            "onNext", "onNextForAck" -> {
                                received = args?.getOrNull(0)
                                received?.let { finish(Result.success(it)) }
                            }
                            "onError" -> finish(Result.failure(args?.getOrNull(0) as? Throwable ?: IllegalStateException("preview-request-failed")))
                            "onCompleted" -> if (received == null) finish(Result.failure(IllegalStateException("empty-preview-response")))
                            "toString" -> value = "CommentReplyPreviewHandler"
                            "hashCode" -> value = System.identityHashCode(proxy)
                            "equals" -> value = proxy === args?.getOrNull(0)
                        }
                    }
                    value
                }
                rpc.invoke(constructor.newInstance(), request, handler)
            }
            CommentReplyPreviewHost(reply, getter(reply, "getId"), getter(reply, "getOid"), getter(reply, "getType"),
                getter(reply, "getRoot"), getter(reply, "getCount"), getter(reply, "getRepliesList"), control,
                getter(control.returnType, "getInvisible"), getter(control.returnType, "getBlocked"), getter(control.returnType, "getIsFoldedReply"),
                plan, checkNotNull(plan.method("clearReplies")), checkNotNull(plan.method("addAllReplies", classOf<Iterable<*>>())),
                fetch, getter(owner("DetailListReply"), "getRoot"))
        }.onFailure(onFailure).getOrNull()

        /** 类名会混淆，按 MainListReply + long + 辅助上下文 + boolean 的转换签名定位。 */
        fun mainListMappers(loader: ClassLoader): List<Method> {
            val main = KavaMemberLookup.classOrNull(loader, "$REPLY_PACKAGE.MainListReply") ?: return emptyList()
            return ('a'..'z').mapNotNull {
                KavaMemberLookup.classOrNull(loader, "com.bilibili.app.comment3.data.source.v1.$it")
            }.flatMap { owner ->
                KavaMemberLookup.declaredMethods(owner, makeAccessible = true) { method ->
                    method.isStatic && !method.isSynthetic && method.parameterCount == 4 &&
                        method.parameterTypes[0] == main && method.parameterTypes[1] == classOf<Long>() &&
                        !method.parameterTypes[2].isPrimitive && method.parameterTypes[3] == classOf<Boolean>() &&
                        !method.returnType.isPrimitive
                }
            }
        }
    }
}
