package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor.SponsorVideoId
import com.bilibili.app.gemini.base.player.GeminiCommonPlayableParams
import com.bilibili.app.gemini.base.player.SponsorBusiness
import com.bilibili.ship.theseus.keel.player.SponsorPlayable
import com.bilibili.ship.theseus.keel.player.TheseusKeelPlayer
import org.junit.Assert.*
import org.junit.Test

class SponsorPlayerAccessTest {
    private val loader = javaClass.classLoader!!
    private fun playable(cid: Long = 1, avid: Long = 2, bvid: String = "BV14741127BN",
        business: SponsorBusiness = SponsorBusiness.UGC) = object : SponsorPlayable {
        override fun values() = GeminiCommonPlayableParams(bvid, cid, avid, business)
    }

    @Test fun inheritedBusinessGetterAndExactTwoArgumentSeekAreResolved() {
        val access = requireNotNull(SponsorPlayerAccess.resolve(loader))
        assertEquals(listOf(Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType), access.seek.parameterTypes.toList())
        assertEquals(SponsorVideoId("BV14741127BN", 1), access.video(TheseusKeelPlayer(playable())))
    }

    @Test fun identityAlwaysReadsTheActivePartAndRejectsNonUgcOrInvalidMedia() {
        val access = requireNotNull(SponsorPlayerAccess.resolve(loader))
        val owner = TheseusKeelPlayer(playable())
        owner.current = playable(cid = 3)
        assertEquals(SponsorVideoId("BV14741127BN", 3), access.video(owner))
        for (invalid in listOf(playable(business = SponsorBusiness.PGC), playable(cid = 0),
            playable(avid = 0), playable(bvid = "invalid"), null)) {
            owner.current = invalid
            assertNull(access.video(owner))
        }
    }

    @Test fun missingAnyRequiredHostBoundaryDisablesTheFeature() {
        for (missing in listOf(SponsorPlayerAccess.RUN_CLASS, SponsorPlayerAccess.PARAMS_CLASS,
            SponsorPlayerAccess.CORE_CLASS, SponsorPlayerAccess.WRAPPER_CLASS,
            "tv.danmaku.biliplayerv2.service.PlayerSeekObserver")) {
            val incomplete = object : ClassLoader(loader) {
                override fun loadClass(name: String, resolve: Boolean): Class<*> {
                    if (name == missing) throw ClassNotFoundException(name)
                    return super.loadClass(name, resolve)
                }
            }
            assertNull(missing, SponsorPlayerAccess.resolve(incomplete))
        }
    }
}
