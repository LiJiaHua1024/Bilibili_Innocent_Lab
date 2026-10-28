package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerPortTestRegistrar
import com.bilibili.lib.router.Router
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GuestVideoFavoriteHooksTest {
    private val statuses = linkedMapOf<String, String>()
    private val proxy = Router.RouterProxy()
    private val notified = mutableListOf<Boolean>()
    private var scene: String? = FAV_SCENE
    private var extend: String? = """{"oid":"100","sid":"3631785"}"""

    private class FakeStore(var known: Set<Long> = setOf(100L), var succeed: Boolean = true) :
        GuestVideoFavoriteHooks.FavoriteStore {
        val toggled = mutableListOf<Long>()
        override fun metadata(aid: Long) =
            if (aid in known) LocalVirtualLibrary.Video(aid, 1L, "t$aid", 1000L, 0L, 1L) else null
        override fun toggle(video: LocalVirtualLibrary.Video, onSaved: () -> Unit, onFailed: () -> Unit) {
            toggled += video.aid
            if (succeed) onSaved() else onFailed()
        }
    }

    @Before fun reset() {
        statuses.clear()
        proxy.opened.clear()
        notified.clear()
        scene = FAV_SCENE
        extend = """{"oid":"100","sid":"3631785"}"""
    }

    private fun environment(registrar: PlayerPortTestRegistrar) =
        HookEnvironment("tv.danmaku.bili", javaClass.classLoader!!, HookPointRegistry(javaClass.classLoader!!), registrar,
            { _, _ -> }, { _, _ -> }, { key, value -> statuses[key] = value })

    private fun hooks(store: FakeStore, guest: () -> Boolean = { true }, proxyClass: String = GuestVideoFavoriteHooks.PROXY) =
        GuestVideoFavoriteHooks(store, guest, proxyClass, { scene to extend }, { _, saved -> notified += saved })

    /** Runs `open(route)` through the registered hook; the original (recording) open runs only when the hook did not take over. */
    private fun PlayerPortTestRegistrar.openString(route: String) =
        invoke("guest.video.favorite.string", proxy, arrayOf(route)) { proxy.open(route); null }

    @Test fun guestStarTapOnTheLoginRouteBecomesALocalFavoriteAndTheLoginScreenIsNotOpened() {
        val store = FakeStore()
        val registrar = PlayerPortTestRegistrar()
        assertEquals(2, hooks(store).install(environment(registrar)))

        registrar.openString("activity://main/login/")

        assertEquals(listOf(100L), store.toggled)
        assertTrue(proxy.opened.isEmpty())                 // the original open() never ran
        assertEquals(listOf(true), notified)
        assertEquals("seen", statuses["guest_video_favorite_applied"])
        assertEquals("seen", statuses["guest_video_favorite_saved"])
        assertNull(statuses["guest_video_favorite_failed"])
        assertEquals("installed", statuses["guest_video_favorite"]) // the install report is never overwritten
    }

    @Test fun bothLoginRouteSpellingsAreAccepted() {
        val store = FakeStore()
        val registrar = PlayerPortTestRegistrar()
        hooks(store).install(environment(registrar))
        registrar.openString("bilibili://login")
        registrar.openString("activity://main/login")
        registrar.openString("activity://main/login/?from=x")
        assertEquals(listOf(100L, 100L, 100L), store.toggled)
        assertTrue(proxy.opened.isEmpty())
    }

    @Test fun otherScenesKeepTheLoginScreen() {
        val store = FakeStore()
        val registrar = PlayerPortTestRegistrar()
        hooks(store).install(environment(registrar))
        scene = "player.ugc-video-detail.user-action.unlike.click"
        registrar.openString("activity://main/login/")
        scene = null
        registrar.openString("activity://main/login/")
        assertTrue(store.toggled.isEmpty())
        assertEquals(2, proxy.opened.size)                 // the host opened the login screen both times
        assertNull(statuses["guest_video_favorite_applied"])
    }

    @Test fun routesOtherThanLoginAreNeverTouched() {
        val store = FakeStore()
        val registrar = PlayerPortTestRegistrar()
        hooks(store).install(environment(registrar))
        registrar.openString("bilibili://video/100")
        registrar.openString("bilibili://main/favorite")
        assertTrue(store.toggled.isEmpty())
        assertEquals(listOf("bilibili://video/100", "bilibili://main/favorite"), proxy.opened)
    }

    @Test fun anUnknownVideoKeepsTheLoginScreenSoTheTapIsNeverSwallowed() {
        val store = FakeStore(known = emptySet())
        val registrar = PlayerPortTestRegistrar()
        hooks(store).install(environment(registrar))
        registrar.openString("activity://main/login/")
        assertTrue(store.toggled.isEmpty())
        assertEquals(listOf("activity://main/login/"), proxy.opened)
        assertNull(statuses["guest_video_favorite_applied"])
    }

    @Test fun loggedInUsersKeepTheHostBehavior() {
        val store = FakeStore()
        val registrar = PlayerPortTestRegistrar()
        hooks(store, guest = { false }).install(environment(registrar))
        registrar.openString("activity://main/login/")
        assertTrue(store.toggled.isEmpty())
        assertEquals(listOf("activity://main/login/"), proxy.opened)
    }

    @Test fun aFailedLocalWriteIsReportedSeparatelyAndTheGuestIsToldSo() {
        val store = FakeStore(succeed = false)
        val registrar = PlayerPortTestRegistrar()
        hooks(store).install(environment(registrar))
        registrar.openString("activity://main/login/")
        assertTrue(proxy.opened.isEmpty())                 // taken over, then reported as failed, not silently dropped
        assertEquals(listOf(false), notified)
        assertEquals("seen", statuses["guest_video_favorite_failed"])
        assertNull(statuses["guest_video_favorite_saved"])
        assertEquals("seen", statuses["guest_video_favorite_applied"])
    }

    @Test fun installingAloneClaimsNothingWasTakenOver() {
        val registrar = PlayerPortTestRegistrar()
        hooks(FakeStore(), guest = { false }).install(environment(registrar))
        registrar.openString("activity://main/login/")
        assertEquals("installed", statuses["guest_video_favorite"])
        assertNull(statuses["guest_video_favorite_applied"])
        assertNull(statuses["guest_video_favorite_saved"])
    }

    @Test fun missingProxyClassInstallsNothingAndReportsAbsent() {
        val registrar = PlayerPortTestRegistrar()
        assertEquals(0, hooks(FakeStore(), proxyClass = "com.bilibili.lib.router.NoSuchProxy").install(environment(registrar)))
        assertEquals("absent", statuses["guest_video_favorite"])
        assertTrue(registrar.hooks.isEmpty())
    }

    @Test fun aProxyWithoutTheExpectedMembersInstallsNothing() {
        val registrar = PlayerPortTestRegistrar()
        assertEquals(0, hooks(FakeStore(), proxyClass = "fixtures.privacy.videofavorite.IncompleteProxy").install(environment(registrar)))
        assertEquals("absent", statuses["guest_video_favorite"])
        assertTrue(registrar.hooks.isEmpty())
    }

    @Test fun favoriteAidParsesOnlyAPositiveOidOnTheFavoriteSceneAndALoginRoute() {
        val aid = GuestVideoFavoriteHooks::favoriteAid
        assertEquals(100L, aid("activity://main/login/", FAV_SCENE, """{"oid":"100"}"""))
        assertEquals(7L, aid("bilibili://login", FAV_SCENE, """{"oid":"7","sid":"1"}"""))
        assertNull(aid("activity://main/login/", FAV_SCENE, null))
        assertNull(aid("activity://main/login/", FAV_SCENE, ""))
        assertNull(aid("activity://main/login/", FAV_SCENE, "not json"))
        assertNull(aid("activity://main/login/", FAV_SCENE, """{"sid":"1"}"""))
        assertNull(aid("activity://main/login/", FAV_SCENE, """{"oid":"abc"}"""))
        assertNull(aid("activity://main/login/", FAV_SCENE, """{"oid":"0"}"""))
        assertNull(aid("activity://main/login/", FAV_SCENE, """{"oid":"-5"}"""))
        assertNull(aid("activity://main/login/", "other.scene", """{"oid":"100"}"""))
        assertNull(aid("bilibili://video/100", FAV_SCENE, """{"oid":"100"}"""))
        assertNull(aid(null, FAV_SCENE, """{"oid":"100"}"""))
    }

    private companion object {
        const val FAV_SCENE = "player.ugc-video-detail.user-action.fav.click"
    }
}
