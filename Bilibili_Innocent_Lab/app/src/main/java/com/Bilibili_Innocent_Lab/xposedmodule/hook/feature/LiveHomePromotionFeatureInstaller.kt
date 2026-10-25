package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isStatic
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 直播分栏顶部推广：在原始卡片转换为列表条目前返回宿主已有的“无数据”值。
 *
 * BiliLiveHomeFeedPage#feedPageData 将 getCardData 的非空结果加入列表，空值直接跳过；
 * 按类型查询单个模块也使用同一个 getter。因此同时覆盖首屏、刷新、缓存及单模块读取，
 * 不创建轮播/播放器，不生成空列表条目，也不改分页、关注栏或普通直播数据。
 * 只读取稳定 cardType 字段；不依赖 bannerV2 的混淆实现类、不扫描 View 或全局方法。
 */
internal class LiveHomePromotionFeatureInstaller(private val enabled: Boolean) : FeatureInstaller {
    override val id: String = ID
    override val capabilityIds: List<String> get() = if (enabled) listOf(ID) else emptyList()

    override fun install(environment: HookEnvironment): FeatureInstallResult {
        if (!enabled) return FeatureInstallResult.Skipped("disabled")
        if (environment.processName != TARGET_PACKAGE) {
            return FeatureInstallResult.Skipped("non-main-process")
        }
        val loader = environment.classLoader
            ?: return FeatureInstallResult.Skipped("missing-class-loader")
        val dataClass = KavaMemberLookup.classOrNull(loader, DATA_CLASS)
            ?: return FeatureInstallResult.Skipped("missing-live-feed-data")
        val pageClass = KavaMemberLookup.classOrNull(loader, PAGE_CLASS)
            ?: return FeatureInstallResult.Skipped("missing-live-feed-page")
        val type = KavaMemberLookup.fieldOrNull(dataClass, "cardType")
            ?.takeIf { !it.isStatic && it.type == classOf<String>() }
            ?: return FeatureInstallResult.Skipped("missing-live-card-type")
        val getter = KavaMemberLookup.methodOrNull(dataClass, "getCardData")
            ?.takeIf { !it.isStatic && it.returnType == classOf<Any>() }
            ?: return FeatureInstallResult.Skipped("missing-live-card-getter")
        // 校验装配边界的形状；结构漂移时不在未知消费者中注入空值。
        if (listOf("feedPageData", "getCardList").any { name ->
                KavaMemberLookup.methodOrNull(pageClass, name)
                    ?.let { !it.isStatic && it.returnType == classOf<List<*>>() } != true
            }) return FeatureInstallResult.Skipped("missing-live-feed-assembly")

        val degraded = AtomicBoolean(false)
        val applied = AtomicBoolean(false)
        return runCatching {
            environment.registrar.exact(HOOK_ID, dataClass, getter.name) {
                before {
                    if (degraded.get()) return@before
                    val card = instance ?: return@before
                    val cardType = try {
                        type.get(card) as? String
                    } catch (failure: Throwable) {
                        if (degraded.compareAndSet(false, true)) {
                            environment.logError("live_home_promotion_read_failed",
                                "[BIL] 直播推广卡类型读取失败，已停止过滤并保留宿主行为: $failure")
                        }
                        return@before
                    }
                    if (cardType != "banner_v2" && cardType != "topview_v2") return@before
                    result = null
                    if (applied.compareAndSet(false, true)) {
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.OBSERVED)
                        environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.APPLIED)
                    }
                }
            }
            environment.reportRuntimeEvidence(ID, FeatureRuntimeStage.ADAPTED)
            environment.reportCapability(ID, FeatureInstallResult.Installed())
            FeatureInstallResult.Installed()
        }.getOrElse { failure ->
            environment.logError("live_home_promotion_registration_failed",
                "[BIL] 直播推广大卡入口注册失败，保留宿主行为: $failure")
            FeatureInstallResult.Skipped("registration-failed")
        }
    }

    companion object {
        const val ID = "live_home_promotion"
        internal const val HOOK_ID = "live.home.promotion.card_data"
        internal const val DATA_CLASS = "com.bilibili.bililive.extension.api.home.BiliLiveHomeFeedData"
        internal const val PAGE_CLASS = "com.bilibili.bililive.extension.api.home.BiliLiveHomeFeedPage"
        private const val TARGET_PACKAGE = "tv.danmaku.bili"
    }
}
