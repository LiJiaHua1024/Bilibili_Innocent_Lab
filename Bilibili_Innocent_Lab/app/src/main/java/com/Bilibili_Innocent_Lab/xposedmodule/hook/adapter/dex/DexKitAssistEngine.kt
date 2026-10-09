package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex

import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.MatchType
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.KotlinDefaultWordsLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SponsorPlayerAccess
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.SponsorPlayerLocator
import com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.CommentClassicStyleLocator

/**
 * DexKit 后台实现：只在常规 KavaRef 定位缺失时创建桥，并在每个代码 APK 查询后立即关闭。
 */
internal object DexKitAssistEngine : DexAssistEngine {

    private const val MAX_CODE_ARCHIVES = 8
    private const val MAX_MATCHES = 32
    private const val BLOCK_UPDATE_RETURN_TYPE =
        "tv.danmaku.bili.update.model.BiliUpgradeInfo"
    private const val CONTEXT_TYPE = "android.content.Context"

    /**
     * 更新检查网络边界方法体里的日志常量。同签名 `(Context) -> BiliUpgradeInfo` 的还有缓存/回退
     * 包装层（8.97.0 起每版两个 owner，8.84.0–8.96.0 三个），只按签名查一定是"命中歧义"。
     * 离线模拟 31 个本地宿主（8.84.0–9.14.0）：签名 + 这一常量在每个版本都恰好命中 1 个方法，
     * 且就是候选表里人工核定的那个网络边界（Temp/host-compat/dexkit_coverage）。
     */
    private const val BLOCK_UPDATE_NETWORK_MARK = "Do sync http request."

    /**
     * 默认画质实现方法体里的日志常量。同样离线模拟 31 个宿主：`()I` + 这一常量每版恰好 1 个方法，
     * 与人工核定的实现一致（8.84.0–8.87.0 是稳定的 `PlayerSettingHelper#getDefaultQuality`，
     * 8.88.0–8.96.0 是实例 `c()`，8.97.0 起是静态 `a()`）。同版本里只读偏好的
     * `getSettingsQuality` 与只转发的包装层都不含它。
     */
    private const val PLAYER_QUALITY_MARK = "quality settings:"
    private const val COMMENT_ITEM_TYPE = "com.bilibili.app.comment3.data.model.CommentItem"

    /**
     * 评论模块根包在 8.63.0–9.10.0 之间从未混淆，用它收窄查询范围。
     *
     * 实测同一条件下的命中量：9.8.0/9.9.0/9.10.0 各 5 个、8.90.2 为 31 个，均在
     * [MAX_MATCHES] 之内；不加包约束时 8.90.2 会达到 59 个而直接触发命中歧义。
     */
    private const val COMMENT3_PACKAGE = "com.bilibili.app.comment3"
    private const val COMMENT_MAPPER_MIN_PARAMS = 1
    private const val COMMENT_MAPPER_MAX_PARAMS = 8

    @Volatile
    private var nativeState = NativeState.NOT_TRIED

    override fun resolve(request: DexAssistRequest): DexAssistResult =
        resolveAll(setOf(request.query), request.codePaths, request.classLoader).getValue(request.query)

    override fun resolveAll(
        queries: Set<DexAssistQuery>,
        codePaths: List<String>,
        classLoader: ClassLoader
    ): Map<DexAssistQuery, DexAssistResult> {
        fun all(reason: DexAssistResult.Reason) =
            queries.associateWith { DexAssistResult.Unavailable(reason) as DexAssistResult }
        val paths = codePaths.distinct()
        if (paths.isEmpty()) return all(DexAssistResult.Reason.NO_CODE_PATH)
        if (paths.size > MAX_CODE_ARCHIVES) return all(DexAssistResult.Reason.TOO_MANY_ARCHIVES)
        if (!ensureNativeLoaded()) return all(DexAssistResult.Reason.NATIVE_UNAVAILABLE)

        val methods = queries.associateWith { mutableListOf<Method>() }
        val classes = queries.associateWith { linkedSetOf<Class<*>>() }
        val related = queries.associateWith { linkedMapOf<Method, MutableSet<Class<*>>>() }
        val failed = mutableMapOf<DexAssistQuery, DexAssistResult.Reason>()
        val bridged = runCatching {
            paths.forEach { path ->
                DexKitBridge.create(path).use { bridge ->
                    queries.filter { it !in failed }.forEach { query ->
                        val found = methods.getValue(query)
                        runCatching { find(bridge, query) }
                            .onSuccess { matches ->
                                if (matches.size + found.size > MAX_MATCHES) {
                                    failed[query] = DexAssistResult.Reason.TOO_MANY_MATCHES
                                } else {
                                    matches.forEach { data ->
                                        if (query == DexAssistQuery.COMMENT_CLASSIC_NATIVE || query == DexAssistQuery.COMMENT_CLASSIC_KOTLIN) {
                                            // R8 会把实验 lambda 与其它业务合并；只挂其调用的 key 参数读取器。
                                            val invokes = data.invokes
                                            if (invokes.size > 128) { failed[query] = DexAssistResult.Reason.TOO_MANY_MATCHES; return@forEach }
                                            val family = if (query == DexAssistQuery.COMMENT_CLASSIC_NATIVE)
                                                CommentClassicStyleLocator.Family.NATIVE else CommentClassicStyleLocator.Family.KOTLIN
                                            invokes.forEach { callee ->
                                                val method = runCatching { callee.getMethodInstance(classLoader) }.getOrNull()
                                                    ?: return@forEach
                                                if (!CommentClassicStyleLocator.verified(method, family)) return@forEach
                                                found += method
                                                if (family == CommentClassicStyleLocator.Family.KOTLIN && method.parameterCount == 6) {
                                                    // 常量调用者可能只引用 default 包装；它委托的四参 getter 也必须覆盖。
                                                    val delegates = callee.invokes
                                                    if (delegates.size > 128) { failed[query] = DexAssistResult.Reason.TOO_MANY_MATCHES; return@forEach }
                                                    delegates.filter { it.declaredClassName == callee.declaredClassName }
                                                        .mapNotNull { runCatching { it.getMethodInstance(classLoader) }.getOrNull() }
                                                        .filter { CommentClassicStyleLocator.isDefaultDelegate(method, it) }.forEach { found += it }
                                                }
                                            }
                                            if (found.size > MAX_MATCHES) failed[query] = DexAssistResult.Reason.TOO_MANY_MATCHES
                                        } else if (query == DexAssistQuery.SPONSOR_CONTAINER_SCOPE) {
                                            runCatching { data.getConstructorInstance(classLoader).declaringClass }
                                                .getOrNull()?.let { classes.getValue(query) += it }
                                        } else {
                                            val method = runCatching { data.getMethodInstance(classLoader) }.getOrNull()
                                                ?: return@forEach
                                            found += method
                                            if (query in SponsorPlayerLocator.queries) {
                                                // 同一真实调用者的构造引用保持成对，不跨包装器拼接保护协程。
                                                val invokes = data.invokes
                                                if (invokes.size > 128) { failed[query] = DexAssistResult.Reason.TOO_MANY_MATCHES; return@forEach }
                                                invokes.filter { it.name == "<init>" && it.paramCount in 2..4 &&
                                                    it.declaredClassName.startsWith("com.bilibili.ship.theseus.") }
                                                    .mapNotNull { runCatching { it.getConstructorInstance(classLoader).declaringClass }.getOrNull() }
                                                    .forEach { related.getValue(query).getOrPut(method) { linkedSetOf() } += it }
                                            }
                                        }
                                    }
                                    if (classes.getValue(query).size > MAX_MATCHES ||
                                        related.getValue(query).values.any { it.size > MAX_MATCHES }) {
                                        failed[query] = DexAssistResult.Reason.TOO_MANY_MATCHES
                                    }
                                }
                            }
                            .onFailure { failed[query] = DexAssistResult.Reason.QUERY_FAILED }
                    }
                }
            }
        }.isSuccess
        return queries.associateWith { query ->
            val reason = failed[query] ?: if (!bridged) DexAssistResult.Reason.QUERY_FAILED else null
            if (reason != null) return@associateWith DexAssistResult.Unavailable(reason)
            val found = methods.getValue(query).distinctBy(Method::toGenericString)
            if (found.isEmpty() && classes.getValue(query).isEmpty()) DexAssistResult.Unavailable(DexAssistResult.Reason.NO_MATCH)
            else DexAssistResult.Candidates(found, classes.getValue(query).toList(),
                related.getValue(query).mapValues { it.value.toList() })
        }
    }

    private fun find(bridge: DexKitBridge, query: DexAssistQuery) = when (query) {
        DexAssistQuery.COMMENT_CLASSIC_NATIVE, DexAssistQuery.COMMENT_CLASSIC_KOTLIN -> bridge.findMethod {
            matcher {
                usingStrings(listOf(if (query == DexAssistQuery.COMMENT_CLASSIC_NATIVE)
                    CommentClassicStyleLocator.NATIVE_ANCHOR else CommentClassicStyleLocator.KOTLIN_ANCHOR), StringMatchType.Equals)
            }
        }
        DexAssistQuery.SPONSOR_RUN_PLAYABLE -> bridge.findMethod {
            searchPackages(SponsorPlayerLocator.FAMILY)
            matcher {
                returnType = "java.lang.Object"; paramCount(2)
                usingStrings(listOf(SponsorPlayerLocator.COROUTINE_ERROR), StringMatchType.Equals)
                addInvoke { declaredClass = "kotlinx.coroutines.sync.Mutex"; name = "lock"; paramCount(2) }
                addInvoke { declaredClass = "kotlinx.coroutines.flow.MutableStateFlow"; name = "setValue"; paramCount(1) }
            }
        }
        DexAssistQuery.SPONSOR_PLAYER_WRAPPER -> bridge.findMethod {
            searchPackages(SponsorPlayerLocator.CONTAINER_PACKAGE)
            matcher {
                name = "seekTo"; returnType = "void"; paramTypes("int", "boolean")
                declaredClass {
                    addFieldForType(SponsorPlayerAccess.CORE_CLASS)
                    addFieldForType("kotlinx.coroutines.CoroutineScope")
                }
                addInvoke { name = "<init>"; paramCount(4) }
            }
        }
        DexAssistQuery.SPONSOR_CONTAINER_SCOPE -> bridge.findMethod {
            searchPackages(SponsorPlayerLocator.CONTAINER_PACKAGE)
            matcher {
                name = "<init>"; paramCount(3, 16)
                declaredClass {
                    superClass = "kotlin.coroutines.jvm.internal.SuspendLambda"
                    addFieldForType("android.content.Context")
                    addFieldForType(SponsorPlayerAccess.CONTAINER_CLASS)
                }
                addInvoke {
                    declaredClass = "kotlin.coroutines.jvm.internal.SuspendLambda"; name = "<init>"
                    paramTypes("int", "kotlin.coroutines.Continuation")
                }
            }
        }
        DexAssistQuery.BLOCK_UPDATE -> bridge.findMethod {
            matcher {
                returnType = BLOCK_UPDATE_RETURN_TYPE
                paramTypes(CONTEXT_TYPE)
                usingStrings(listOf(BLOCK_UPDATE_NETWORK_MARK), StringMatchType.Equals)
            }
        }

        // owner 是顶层混淆包、每版都换，无法用 searchPackages 收窄；方法体常量本身
        // 已足够强（全宿主唯一），命中量由 MAX_MATCHES 兜住。
        DexAssistQuery.PLAYER_DEFAULT_QUALITY -> bridge.findMethod {
            matcher {
                returnType = "int"
                paramCount(0)
                usingStrings(listOf(PLAYER_QUALITY_MARK), StringMatchType.Equals)
            }
        }

        // 首参类型无法在这里表达（paramTypes 会同时锁死参数个数，而 mapper 的
        // 参数个数在 2-5 之间漂移），因此只按返回类型 + static + 参数区间收窄，
        // 首参是否为 ReplyInfo 交给 VersionAdapter 用宿主 ClassLoader 复核。
        DexAssistQuery.COMMENT_REPLY_MAPPER -> bridge.findMethod {
            searchPackages(COMMENT3_PACKAGE)
            matcher {
                returnType = COMMENT_ITEM_TYPE
                modifiers(Modifier.STATIC, MatchType.Contains)
                paramCount(COMMENT_MAPPER_MIN_PARAMS, COMMENT_MAPPER_MAX_PARAMS)
            }
        }

        // 该泛型入口把服务与 RPC 描述符交给 Companion getter，不含服务名字符串。
        // 真实方法体的引用常量 + 参数区间收窄；宿主 ClassLoader 再核对完整五参数 ABI。
        DexAssistQuery.SEARCH_DEFAULT_WORDS_KOTLIN -> bridge.findMethod {
            matcher {
                returnType = "void"
                paramCount(5)
                addInvoke {
                    name = KotlinDefaultWordsLocator.DESCRIPTOR_GETTER
                    paramCount(0)
                }
            }
        }
    }

    private fun ensureNativeLoaded(): Boolean {
        if (nativeState != NativeState.NOT_TRIED) return nativeState == NativeState.AVAILABLE
        synchronized(this) {
            if (nativeState == NativeState.NOT_TRIED) {
                nativeState = if (runCatching { System.loadLibrary("dexkit") }.isSuccess) {
                    NativeState.AVAILABLE
                } else {
                    NativeState.UNAVAILABLE
                }
            }
        }
        return nativeState == NativeState.AVAILABLE
    }

    private enum class NativeState {
        NOT_TRIED,
        AVAILABLE,
        UNAVAILABLE
    }
}
