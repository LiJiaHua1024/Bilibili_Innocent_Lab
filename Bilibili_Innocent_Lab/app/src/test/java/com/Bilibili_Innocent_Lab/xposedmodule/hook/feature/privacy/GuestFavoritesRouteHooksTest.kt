package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.Bilibili_Innocent_Lab.xposedmodule.hook.HookPointRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.PlayerPortTestRegistrar
import fixtures.privacy.videofavorite.storyrouter.StoryCode
import fixtures.privacy.videofavorite.storyrouter.StoryRequest
import fixtures.privacy.videofavorite.storyrouter.StoryResponse
import fixtures.privacy.videofavorite.storyrouter.StoryRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GuestFavoritesRouteHooksTest {
    private val statuses = linkedMapOf<String, String>()
    private val opened = mutableListOf<Any?>()
    private var kntr = false
    private var canOpen = true
    private val context = Any()

    @Before fun reset() {
        statuses.clear()
        opened.clear()
        StoryRouter.routed.clear()
        kntr = false
        canOpen = true
    }

    private fun environment(registrar: PlayerPortTestRegistrar, loader: ClassLoader = javaClass.classLoader!!) =
        HookEnvironment("tv.danmaku.bili", loader, HookPointRegistry(loader), registrar,
            { _, _ -> }, { _, _ -> }, { key, value -> statuses[key] = value })

    private fun hooks(guest: () -> Boolean = { true }, names: GuestVideoFavoriteHooks.RouterNames = ROUTER) =
        GuestFavoritesRouteHooks(guest, names, { target -> opened += target; canOpen }, { kntr })

    /** Runs `BLRouter.routeTo(request, context)` through the hook; the fixture router runs only when the hook did not answer. */
    private fun PlayerPortTestRegistrar.routeTo(uri: String, with: Any? = context) = StoryRequest(uri).let { request ->
        invoke("guest.favorites.route", null, arrayOf(request, with)) {
            StoryRouter.routeTo(request, null)
        } as StoryResponse
    }

    @Test fun aGuestTappingFavoritesOpensTheNativePageAndIsAnsweredAsHandled() {
        val registrar = PlayerPortTestRegistrar()
        assertEquals(1, hooks().install(environment(registrar)))
        assertEquals("installed", statuses["guest_favorites_route"])

        for (uri in listOf("bilibili://user_center/favourite", "bilibili://main/favorite",
            "bilibili://user_center/favourite?from_spmid=x", "bilibili://user_center/favorite_v2/favorite/folders/")) {
            opened.clear()
            StoryRouter.routed.clear()
            val response = registrar.routeTo(uri)
            assertEquals(uri, StoryCode.OK, response.code)
            assertEquals(uri, listOf<Any?>(context), opened)
            assertTrue(uri, StoryRouter.routed.isEmpty()) // the router, and so the login rewrite, never ran
        }
        assertEquals("seen", statuses["guest_favorites_route_opened"])
    }

    @Test fun otherRoutesAreLeftToTheHost() {
        val registrar = PlayerPortTestRegistrar()
        hooks().install(environment(registrar))
        for (uri in listOf("bilibili://login", "bilibili://user_center/watch_later_v2", "bilibili://main/drawer/history",
            "bilibili://video/100", "bilibili://user_center/wallet")) {
            StoryRouter.routed.clear()
            assertEquals(uri, StoryCode.FOUND_STUB, registrar.routeTo(uri).code)
            assertEquals(listOf(uri), StoryRouter.routed)
        }
        assertTrue(opened.isEmpty())
        assertNull(statuses["guest_favorites_route_opened"])
    }

    @Test fun aBuildWithTheKntrRouteIsLeftToTheMenuHook() {
        kntr = true
        val registrar = PlayerPortTestRegistrar()
        hooks().install(environment(registrar))
        assertEquals(StoryCode.FOUND_STUB, registrar.routeTo("bilibili://user_center/favourite").code)
        assertTrue(opened.isEmpty())
    }

    @Test fun aLoggedInUserKeepsTheHostRoute() {
        val registrar = PlayerPortTestRegistrar()
        hooks(guest = { false }).install(environment(registrar))
        assertEquals(StoryCode.FOUND_STUB, registrar.routeTo("bilibili://user_center/favourite").code)
        assertTrue(opened.isEmpty())
        assertNull(statuses["guest_favorites_route_opened"])
    }

    @Test fun whenThePageCannotBeOpenedTheHostRouteStillRuns() {
        canOpen = false
        val registrar = PlayerPortTestRegistrar()
        hooks().install(environment(registrar))
        assertEquals(StoryCode.FOUND_STUB, registrar.routeTo("bilibili://user_center/favourite").code)
        assertEquals(listOf(context), opened)
        assertNull(statuses["guest_favorites_route_opened"])
    }

    @Test fun aNullContextIsPassedOnSoTheProductionOpenerCanRefuseIt() {
        val registrar = PlayerPortTestRegistrar()
        hooks().install(environment(registrar))
        registrar.routeTo("bilibili://user_center/favourite", with = null)
        assertEquals(listOf<Any?>(null), opened)
        assertFalse(GuestFavoritesRouteHooks.openFavorites(null))
    }

    @Test fun anUnknownRouterInstallsNothingAndReportsAbsent() {
        val registrar = PlayerPortTestRegistrar()
        val none = GuestVideoFavoriteHooks.RouterNames("no.Such.Router", "no.Such.Request", "no.Such.Response", "no.Such.Code")
        assertEquals(0, hooks(names = none).install(environment(registrar)))
        assertEquals("absent", statuses["guest_favorites_route"])
        assertTrue(registrar.hooks.isEmpty())
    }

    private companion object {
        const val PKG = "fixtures.privacy.videofavorite.storyrouter"
        val ROUTER = GuestVideoFavoriteHooks.RouterNames("$PKG.StoryRouter", "$PKG.StoryRequest", "$PKG.StoryResponse", "$PKG.StoryCode")
    }
}
