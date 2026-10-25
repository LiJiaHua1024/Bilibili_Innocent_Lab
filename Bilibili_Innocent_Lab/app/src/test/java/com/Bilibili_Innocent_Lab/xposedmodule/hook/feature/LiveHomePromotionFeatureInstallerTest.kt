package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import org.junit.Assert.*
import org.junit.Test

class LiveHomePromotionFeatureInstallerTest {
    class Card(@JvmField var cardType: String?) {
        fun getCardData(): Any? = error("invoked through Modern callback fixture")
    }
    class Page {
        private fun feedPageData(): List<Any> = emptyList()
        fun getCardList(): List<Any> = emptyList()
    }
    class WrongType { @JvmField var cardType = 1; fun getCardData(): Any? = null }
    class WrongGetter { @JvmField var cardType = "banner_v2"; fun getCardData(): String? = null }
    class WrongPage { fun feedPageData(): Any = emptyList<Any>(); fun getCardList(): List<Any> = emptyList() }
    private class Fixture(data: Class<*>? = Card::class.java, page: Class<*>? = Page::class.java,
        process: String = "tv.danmaku.bili", failRegistration: Boolean = false) {
        var lookups = 0
        val loader = object : ClassLoader(Card::class.java.classLoader) {
            override fun loadClass(name: String): Class<*> = when (name) {
                LiveHomePromotionFeatureInstaller.DATA_CLASS -> { lookups++; data ?: throw ClassNotFoundException(name) }
                LiveHomePromotionFeatureInstaller.PAGE_CLASS -> { lookups++; page ?: throw ClassNotFoundException(name) }
                else -> super.loadClass(name)
            }
        }
        val registrar = PlayerPortTestRegistrar(if (failRegistration) HOOK else null)
        val stages = mutableListOf<FeatureRuntimeStage>()
        val errors = mutableListOf<String>()
        val environment = HookEnvironment(process, loader, HookPointRegistry(loader), registrar,
            { _, _ -> }, { key, _ -> errors += key }, { _, _ -> },
            runtimeEvidence = { id, stage, _ -> assertEquals(LiveHomePromotionFeatureInstaller.ID, id); stages += stage })
        fun install(enabled: Boolean = true) = LiveHomePromotionFeatureInstaller(enabled).install(environment)
        fun read(card: Any, original: () -> Any?): Any? = registrar.invoke(HOOK, card) { original() }
    }

    @Test fun `disabled or non main process performs no host lookup or registration`() {
        for ((enabled, process) in listOf(false to "tv.danmaku.bili", true to "tv.danmaku.bili:live")) {
            val f = Fixture(process = process)
            assertTrue(f.install(enabled) is FeatureInstallResult.Skipped)
            assertEquals(0, f.lookups)
            assertTrue(f.registrar.registrationAttempts.isEmpty())
        }
    }

    @Test fun `unknown host shapes leave original behavior untouched`() {
        for (f in listOf(Fixture(data = null), Fixture(page = null), Fixture(data = WrongType::class.java),
                Fixture(data = WrongGetter::class.java), Fixture(page = WrongPage::class.java))) {
            assertTrue(f.install() is FeatureInstallResult.Skipped)
            assertTrue(f.registrar.hooks.isEmpty())
        }
    }

    @Test fun `both promotion types skip conversion even when a payload was cached`() {
        val f = Fixture(); assertEquals(FeatureInstallResult.Installed(), f.install())
        val cachedPayload = Any()
        for (type in listOf("banner_v2", "topview_v2")) {
            var called = false
            assertNull(f.read(Card(type)) { called = true; cachedPayload })
            assertFalse(called)
        }
        assertEquals(1, f.registrar.registrationAttempts.size)
    }

    @Test fun `followed channels ordinary live cards and unknown types preserve payload identity`() {
        val f = Fixture(); f.install()
        for (type in listOf("my_idol_v1", "small_card_v1", "area_entrance_v3", "activity_card_v1",
                "sea_patrol_v2", "banner_v20", "prefix_banner_v2", "BANNER_V2", "", null)) {
            val payload = Any(); var calls = 0
            assertSame(payload, f.read(Card(type)) { calls++; payload })
            assertEquals(1, calls)
        }
        assertEquals(listOf(FeatureRuntimeStage.ADAPTED), f.stages)
    }

    @Test fun `host null skipping assembles a compact list with preserved neighbor order`() {
        val f = Fixture(); f.install()
        val follow = Any(); val ordinary = Any(); val area = Any()
        val cards = listOf(Card("banner_v2") to Any(), Card("my_idol_v1") to follow,
            Card("topview_v2") to Any(), Card("small_card_v1") to ordinary, Card("area_entrance_v3") to area)
        repeat(3) { // 首屏、重复读取、刷新都经过相同转换入口。
            val list = cards.mapNotNull { (card, payload) -> f.read(card) { payload } }
            assertEquals(3, list.size)
            assertSame(follow, list[0]); assertSame(ordinary, list[1]); assertSame(area, list[2])
        }
        assertTrue(cards.all { it.first.cardType != null })
    }

    @Test fun `repeated target reads report evidence once and never run original conversion`() {
        val f = Fixture(); f.install(); val card = Card("banner_v2")
        repeat(2_000) { assertNull(f.read(card) { fail("promotion conversion ran"); Any() }) }
        assertEquals(listOf(FeatureRuntimeStage.ADAPTED, FeatureRuntimeStage.OBSERVED, FeatureRuntimeStage.APPLIED), f.stages)
        assertTrue(f.errors.isEmpty())
    }

    @Test fun `ordinary host exceptions still reach the caller`() {
        val f = Fixture(); f.install(); val failure = IllegalStateException("host failure")
        try { f.read(Card("small_card_v1")) { throw failure }; fail("exception lost") }
        catch (actual: IllegalStateException) { assertSame(failure, actual) }
    }

    @Test fun `unreadable receiver disables only this installer and logs once`() {
        val f = Fixture(); f.install(); val payload = Any()
        assertSame(payload, f.read(Any()) { payload })
        assertSame(payload, f.read(Card("banner_v2")) { payload })
        assertEquals(listOf("live_home_promotion_read_failed"), f.errors)
        assertEquals(listOf(FeatureRuntimeStage.ADAPTED), f.stages)
    }

    @Test fun `registration failure reports no applied evidence`() {
        val f = Fixture(failRegistration = true)
        assertEquals(FeatureInstallResult.Skipped("registration-failed"), f.install())
        assertTrue(f.stages.isEmpty())
    }

    companion object { private const val HOOK = LiveHomePromotionFeatureInstaller.HOOK_ID }
}
