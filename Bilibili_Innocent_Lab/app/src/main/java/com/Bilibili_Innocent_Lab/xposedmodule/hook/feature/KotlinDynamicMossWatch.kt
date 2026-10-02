package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.isStatic
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * 观测动态页新数据通道 `KDynamicMoss#dynAll / dynVideo` 是否被宿主调用；**只观测，不改写**。
 *
 * 9.14.0 起动态页出现 Compose 新列表（`kntr.app.following.followinglist`），它的数据层调用 Kotlin
 * 序列化版 `KDynamicMoss`，而动态过滤挂在 Java `DynamicMoss` 上（见 [DynamicPurifyFeatureInstaller]）。
 * 静态分析无法判断新列表是否已对某台设备启用；启用后过滤会"装上了、永不触发"，而安装状态仍是
 * success——这正是"静默失效"。本类把这件事变成可见证据：
 * - 每个入口每进程一条错误日志（含 Java 通道此前是否见过响应，便于判断两条通道是否并存）；
 * - 对用户已启用的动态能力打运行期错误标记，诊断页会在对应能力后显示"运行期错误"，导出的诊断
 *   报告与遥测里也带这个标记。
 *
 * 不读、不改任何请求/响应对象；回调里只有一次原子判重，对宿主行为与性能零影响。
 * 旧宿主没有 `KDynamicMoss`（或没有对应入口）时安装数为 0，等于什么都没做。
 */
internal object KotlinDynamicMossWatch {
    const val K_MOSS_CLASS = "com.bapis.bilibili.app.dynamic.v2.KDynamicMoss"

    private val WATCHED_NAMES = setOf("dynAll", "dynVideo")

    /**
     * 只挑"业务入口"形态：非静态、非合成、恰好两个参数（请求 + `Continuation` 或回调）。
     * 泛型序列化重载（5 个参数，合成）与 `$default` 静态桥是它们的内部实现，不重复挂，
     * 免得一次调用触发多次。请求/回调类型在宿主里被混淆，所以只按名字与参数个数认。
     */
    internal fun isWatchedEntry(method: Method): Boolean =
        method.name in WATCHED_NAMES &&
            !method.isStatic &&
            !method.isSynthetic &&
            method.parameterCount == 2

    /**
     * 挑出真正要观测的入口：形态合法，且**没有**被 Kotlin 通道过滤接住。
     * 只接上一部分页签时，剩下那条通道仍必须留下证据，否则它会安静地不过滤而状态还是 success。
     */
    internal fun selectUncovered(entries: List<Method>, coveredNames: Set<String>): List<Method> =
        entries.filter(::isWatchedEntry).filterNot { it.name in coveredNames }.distinctBy(Method::toGenericString)

    /**
     * @param evidenceIds 要打运行期错误标记的能力/功能 id（调用方传"用户已启用的动态能力 + 功能本身"）。
     * @param javaPathObserved Java 通道此前是否已见过响应，仅写进日志。
     * @param coveredNames 已经被 Kotlin 通道过滤接住的名字，不再观测——只盯真正没覆盖上的入口。
     * @return 成功注册的观测点个数。
     */
    fun install(
        environment: HookEnvironment,
        loader: ClassLoader,
        evidenceIds: List<String>,
        javaPathObserved: () -> Boolean,
        coveredNames: Set<String> = emptySet()
    ): Int {
        val mossClass = KavaMemberLookup.classOrNull(loader, K_MOSS_CLASS) ?: return 0
        val entries = selectUncovered(KavaMemberLookup.declaredMethods(mossClass, makeAccessible = true), coveredNames)
        val reported = ConcurrentHashMap.newKeySet<String>()
        var installed = 0
        entries.forEachIndexed { index, method ->
            runCatching {
                environment.registrar.exact(
                    "dynamic.purify.kmoss_watch.${method.name}.$index",
                    method.declaringClass,
                    method.name,
                    *method.parameterTypes
                ) {
                    before {
                        if (!reported.add(method.name)) return@before
                        evidenceIds.forEach { environment.reportRuntimeEvidence(it, FeatureRuntimeStage.ERROR) }
                        environment.logError(
                            "dynamic_kmoss_uncovered",
                            "[BIL] 动态页新列表数据通道 KDynamicMoss#${method.name} 已被宿主调用；" +
                                "动态过滤当前只覆盖 Java DynamicMoss，这条通道上的内容不会被过滤" +
                                "（javaPathObserved=${javaPathObserved()}）"
                        )
                    }
                }
                installed += 1
            }.onFailure { throwable ->
                environment.logError(
                    "dynamic_kmoss_watch_${method.name}",
                    "[BIL] 动态页新通道观测点注册失败(${method.name}): $throwable"
                )
            }
        }
        return installed
    }
}
