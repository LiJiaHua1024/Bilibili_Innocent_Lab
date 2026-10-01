package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter.dex

import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.enums.MatchType
import org.luckypray.dexkit.query.enums.StringMatchType
import java.lang.reflect.Method
import java.lang.reflect.Modifier

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
                                    matches.mapNotNullTo(found) { data ->
                                        runCatching { data.getMethodInstance(classLoader) }.getOrNull()
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
            methods.getValue(query).distinctBy(Method::toGenericString).takeIf(List<Method>::isNotEmpty)
                ?.let(DexAssistResult::Candidates)
                ?: DexAssistResult.Unavailable(DexAssistResult.Reason.NO_MATCH)
        }
    }

    private fun find(bridge: DexKitBridge, query: DexAssistQuery) = when (query) {
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
