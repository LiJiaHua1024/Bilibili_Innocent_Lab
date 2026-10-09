package com.Bilibili_Innocent_Lab.xposedmodule.hook.adapter

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.sponsor.SponsorVideoId
import com.bilibili.app.gemini.base.player.GeminiCommonPlayableParams
import com.bilibili.app.gemini.base.player.SponsorBusiness
import com.bilibili.ship.theseus.keel.player.SponsorPlayable
import com.bilibili.ship.theseus.keel.player.TheseusKeelPlayer
import com.bilibili.ship.theseus.keel.player.`TheseusKeelPlayer$runPlayable$1`
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
        assertEquals(7, requireNotNull(access.scope).parameterCount)
        val container = requireNotNull(access.containerScope)
        assertEquals(2, container.containerIndex); assertEquals(3, container.contextIndex)
        assertEquals("getPlayerCoreService", container.core.name)
        assertEquals(SponsorPlayerAccess.CONTAINER_INTERFACE, container.core.declaringClass.name)
        assertEquals(listOf(Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType), access.seek.parameterTypes.toList())
        assertEquals(SponsorVideoId("BV14741127BN", 1), access.video(TheseusKeelPlayer(playable())))
    }

    @Test fun identityAlwaysReadsTheActivePartAndRejectsNonUgcOrInvalidMedia() {
        val access = requireNotNull(SponsorPlayerAccess.resolve(loader))
        val owner = TheseusKeelPlayer(playable())
        owner.current = playable(cid = 3)
        assertEquals(SponsorVideoId("BV14741127BN", 3), access.video(owner))
        owner.current = playable(cid = 42_072_543_108L, bvid = "BV1FnhC6qEck")
        assertEquals(SponsorVideoId("BV1FnhC6qEck", 42_072_543_108L), access.video(owner))
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

    @Test fun emptyUgcBvidUsesTheCurrentAidWithoutCachingThePartOrAcceptingMalformedIdentity() {
        val access = requireNotNull(SponsorPlayerAccess.resolve(loader))
        val owner = TheseusKeelPlayer(playable(avid = 117_312_082_479_704L,
            cid = 42_072_543_108L, bvid = ""))
        val diagnostics = mutableListOf<String>()
        assertEquals(SponsorVideoId("BV1FnhC6qEck", 42_072_543_108L), access.video(owner, diagnostics::add))
        assertEquals(listOf("aid-derived"), diagnostics)
        owner.current = playable(avid = 117_312_082_479_704L, cid = 42_072_543_109L, bvid = "")
        assertEquals(SponsorVideoId("BV1FnhC6qEck", 42_072_543_109L), access.video(owner))
        owner.current = playable(avid = 170_001L, cid = 1, bvid = "")
        assertEquals(SponsorVideoId("BV17x411w7KC", 1), access.video(owner))
        for (invalid in listOf(playable(avid = 170_001L, bvid = "broken"),
            playable(avid = 170_001L, bvid = " "), playable(avid = 0, bvid = ""),
            playable(avid = 2_251_799_813_685_248L, bvid = ""), playable(cid = 0, bvid = ""),
            playable(business = SponsorBusiness.PGC, bvid = ""))) {
            owner.current = invalid
            assertNull(access.video(owner))
        }
    }

    @Test fun seekGuardRequiresTheConcreteCoroutineCloneAndHostUnit() {
        val player = requireNotNull(SponsorPlayerAccess.resolve(loader))
        val seek = requireNotNull(SponsorSeekAccess.resolve(loader, player))
        assertSame(Unit, seek.unit)
        assertEquals("create", seek.create.name)
        assertEquals("invokeSuspend", seek.invoke.name)
        assertEquals(4, seek.constructor.parameterCount)
    }

    @Test fun activePlaybackCanBeBoundWhileRunPlayableIsSuspendedButNotWhileWaitingForItsMutex() {
        val access = requireNotNull(SponsorPlayerAccess.resolve(loader))
        val first = playable(); val next = playable(cid = 2)
        val owner = TheseusKeelPlayer(first)
        assertTrue(access.bound(owner, first, null))
        assertFalse(access.bound(owner, next, null))
        val resume = `TheseusKeelPlayer$runPlayable$1`(owner)
        resume.label = 1
        assertFalse(access.bound(owner, null, resume))
        owner.current = next; resume.label = 2
        assertTrue(access.bound(owner, null, resume))
        assertFalse(access.bound(TheseusKeelPlayer(next), null, resume))
        owner.current = null
        assertFalse(access.bound(owner, null, resume))
    }

    @Test fun eitherExplicitActivityBindingRouteCanSupportAHostButNeitherCannot() {
        fun hiding(vararg classes: String) = object : ClassLoader(loader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name in classes) throw ClassNotFoundException(name)
                return super.loadClass(name, resolve)
            }
        }
        val direct = requireNotNull(SponsorPlayerAccess.resolve(hiding(SponsorPlayerAccess.SCOPE_CLASS)))
        assertNull(direct.scope); assertNotNull(direct.containerScope)
        val fallback = requireNotNull(SponsorPlayerAccess.resolve(hiding(SponsorPlayerAccess.CONTAINER_SCOPE_CLASS)))
        assertNotNull(fallback.scope); assertNull(fallback.containerScope)
        assertNull(SponsorPlayerAccess.resolve(hiding(SponsorPlayerAccess.SCOPE_CLASS, SponsorPlayerAccess.CONTAINER_SCOPE_CLASS)))
    }
}
