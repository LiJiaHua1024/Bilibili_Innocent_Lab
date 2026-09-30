package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.classOf
import java.util.concurrent.atomic.AtomicReference

/**
 * 在**真实宿主类**上对 [KotlinMossReplyBridge] 的整条机制做一次安装期自检，每进程一次。
 *
 * 为什么需要：Kotlin 通道（新动态列表、新弹幕引擎）都是灰度功能，多数设备上根本不会走到，
 * 单测又只能用夹具替身——两者都验证不了"kotlinx 的 `ProtoBuf` / 生成的 `$serializer` / Java protobuf
 * 消息在这个宿主版本上真的能往返"。2026-10-01 真机上就因此漏过一个只查本类声明、找不到父类
 * `toByteArray()` 的 bug，静默没装上。这里造一条**合成**的 `DmViewReply`（带一条 `activityMeta`），
 * 经真实的 `ProtoBuf.Default` 与 `KDmViewReply` 序列化器走完整个"Kotlin→Java→清理→Kotlin"往返，
 * 再回读确认字段确实被清掉。整个流程不接触任何用户数据、不联网。
 *
 * 结果分三档，只有 [Outcome.FAILED] 才会让调用方**不装** Kotlin 通道：
 * - [Outcome.PASSED]：机制在这个宿主版本上可用；
 * - [Outcome.UNAVAILABLE]：自检所需的类/方法缺失（旧宿主），无法验证也无法否定，照常安装；
 * - [Outcome.FAILED]：自检跑起来了但结果不对或抛异常——说明真实往返有问题，宁可不装。
 */
internal object KotlinMossBridgeSelfTest {
    enum class Outcome { PASSED, FAILED, UNAVAILABLE }

    class Report(val outcome: Outcome, val detail: String)

    private const val JAVA_REPLY = "com.bapis.bilibili.community.service.dm.v1.DmViewReply"
    private const val KOTLIN_REPLY = "com.bapis.bilibili.community.service.dm.v1.KDmViewReply"
    private const val MARKER = "kmoss-selftest"
    private const val MAX_DETAIL = 160

    private val cached = AtomicReference<Report?>(null)

    fun run(loader: ClassLoader, members: KotlinMossBridgeMembers): Report =
        cached.get() ?: synchronized(this) {
            cached.get() ?: execute(loader, members).also(cached::set)
        }

    /**
     * 安装前的闸门：跑（或复用）自检并记一条日志；返回 false 表示自检 [Outcome.FAILED]，调用方不应安装 Kotlin 通道。
     * [what] 只用于日志，说明是哪个功能在问。
     */
    fun allows(environment: HookEnvironment, loader: ClassLoader, members: KotlinMossBridgeMembers, what: String): Boolean {
        val report = run(loader, members)
        return when (report.outcome) {
            Outcome.PASSED -> {
                environment.logInfo("kmoss_bridge_selftest", "[BIL] Kotlin 通道自检通过（真实 ProtoBuf / KDmViewReply 往返），$what 继续安装")
                true
            }
            Outcome.UNAVAILABLE -> {
                environment.logInfo("kmoss_bridge_selftest", "[BIL] Kotlin 通道自检不可用(${report.detail})，按未验证处理，$what 继续安装")
                true
            }
            Outcome.FAILED -> {
                environment.logError("kmoss_bridge_selftest", "[BIL] Kotlin 通道自检失败，$what 不安装 Kotlin 通道: ${report.detail}")
                false
            }
        }
    }

    private fun unavailable(reason: String) = Report(Outcome.UNAVAILABLE, reason)

    private fun execute(loader: ClassLoader, members: KotlinMossBridgeMembers): Report = try {
        run body@{
            val javaClass = KavaMemberLookup.classOrNull(loader, JAVA_REPLY) ?: return@body unavailable("no-java-reply")
            val kotlinClass = KavaMemberLookup.classOrNull(loader, KOTLIN_REPLY) ?: return@body unavailable("no-kotlin-reply")
            val codec = KotlinMossBridgeMembers.JavaReplyCodec.resolve(javaClass) ?: return@body unavailable("no-java-codec")
            val plan = ProtobufBuilderPlan.resolve(javaClass) ?: return@body unavailable("no-builder")
            val add = plan.method("addActivityMeta", classOf<String>()) ?: return@body unavailable("no-add")
            val clear = plan.method("clearActivityMeta") ?: return@body unavailable("no-clear")
            val default = KavaMemberLookup.methodOrNull(javaClass, "getDefaultInstance")?.invoke(null)
                ?: return@body unavailable("no-default")
            val protoBuf = members.defaultProtoBuf() ?: return@body unavailable("no-protobuf-default")
            val serializer = runCatching {
                val companion = kotlinClass.getField("Companion").get(null)
                companion.javaClass.getMethod("serializer").invoke(companion)
            }.getOrNull() ?: return@body unavailable("no-serializer")
            val bridge = members.bridgeFor(protoBuf, serializer, codec) ?: return@body unavailable("bridge-refused")

            // 用线格式字节判断标记是否存在，**不读 getter**：互动层自己就 Hook 了 `getActivityMetaList` 的读取
            // （非空返回空列表），读 getter 会读到被隐藏后的结果——2026-10-01 真机上就因此误判过一次。
            fun hasMarker(bytes: ByteArray): Boolean = String(bytes, Charsets.ISO_8859_1).contains(MARKER)

            val javaWithMeta = plan.edit(default) { builder -> add.invoke(builder, MARKER) }
            val javaBytes = codec.serialize(javaWithMeta)
            check(hasMarker(javaBytes)) { "synthetic reply not built (bytes=${javaBytes.size})" }
            val kotlinReply = members.decodeKotlin(protoBuf, serializer, javaBytes)
            check(hasMarker(members.encodeKotlin(protoBuf, serializer, kotlinReply))) { "kotlin decode lost the marker" }

            // 无改动：必须原样返回同一个 Kotlin 对象，且不发生回写。
            check(bridge.transform(kotlinReply) { it } === kotlinReply) { "unchanged reply was rewritten" }

            // 有改动：清掉 activityMeta，回读确认。
            val cleaned = bridge.transform(kotlinReply) { javaReply -> plan.edit(javaReply) { builder -> clear.invoke(builder) } }
            check(cleaned !== kotlinReply) { "changed reply was not replaced" }
            check(!hasMarker(members.encodeKotlin(protoBuf, serializer, cleaned))) { "activityMeta still present after round trip" }
            Report(Outcome.PASSED, "ok")
        }
    } catch (throwable: Throwable) {
        Report(Outcome.FAILED, "${throwable.javaClass.simpleName}: ${throwable.message}".take(MAX_DETAIL))
    }
}
