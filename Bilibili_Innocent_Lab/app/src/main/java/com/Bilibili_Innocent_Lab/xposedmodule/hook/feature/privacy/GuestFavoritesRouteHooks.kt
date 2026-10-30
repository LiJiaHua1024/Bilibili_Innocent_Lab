package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import android.content.Context
import android.content.Intent
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.hook.modern.ReflectAccess
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Open the host's own favorites page for a guest on builds that have no `compose/favorites/kntr` route (8.97 to 9.11).
 *
 * A guest tapping 我的收藏 passes the menu's `needLogin` gate ([GuestMineMenuHooks]) and then reaches
 * `BLRouter.routeTo(request, context)` with `bilibili://user_center/favourite`. The router's authenticator rewrites that to
 * the login route, which opens as a translucent overlay, so the screen looks unchanged. Where `kntr` is registered the menu
 * hook already swaps the target and this hook stays out of the way; where it is not, `FavoritesActivityV2` is started
 * directly (it lists the local library through the HTTP short circuit, checked on 9.0.0) and the router call is answered
 * with `RouteResponse(OK, request)` so the caller sees a handled route.
 *
 * Only guests, and only the favorites URIs of [GuestLibraryHostHooks.sectionForUri]. Everything else, and every failure to
 * answer or to start the page, leaves the host's own behavior in place.
 */
internal class GuestFavoritesRouteHooks(
    private val isGuest: () -> Boolean,
    private val routerNames: GuestVideoFavoriteHooks.RouterNames = GuestVideoFavoriteHooks.RouterNames(),
    private val open: (Any?) -> Boolean = { openFavorites(it) },
    private val kntrRegistered: (ClassLoader) -> Boolean = { loader ->
        GuestMineMenuHooks.RouteTable.resolve(loader)?.isRegistered(GuestMineMenuHooks.FAVORITES_TARGET) == true
    }
) {
    fun install(env: HookEnvironment): Int = runCatching {
        val loader = requireNotNull(env.classLoader)
        val router = requireNotNull(PrivacyStructuralResolver.type(loader, routerNames.router))
        val request = requireNotNull(PrivacyStructuralResolver.type(loader, routerNames.request))
        val response = requireNotNull(PrivacyStructuralResolver.type(loader, routerNames.response))
        val code = requireNotNull(PrivacyStructuralResolver.type(loader, routerNames.code))
        val routeTo = requireNotNull(PrivacyStructuralResolver.method(router, "routeTo", request, Context::class.java))
        val getTargetUri = requireNotNull(PrivacyStructuralResolver.method(request, "getTargetUri"))
        val ok = requireNotNull(code.enumConstants?.firstOrNull { (it as? Enum<*>)?.name == "OK" })
        val newResponse = requireNotNull(Lookup.constructorOrNull(response, code, request))
        val answered = AtomicBoolean(false)
        env.registrar.exact("guest.favorites.route", router, routeTo.name, *routeTo.parameterTypes) {
            before {
                if (!runCatching { isGuest() }.getOrDefault(false)) return@before
                val routeRequest = argOrNull(0) ?: return@before
                val uri = runCatching { getTargetUri.invoke(routeRequest)?.toString() }.getOrNull()
                if (GuestLibraryHostHooks.sectionForUri(uri) != "favorites") return@before
                if (kntrRegistered(loader)) return@before
                val context = argOrNull(1)
                val handled = runCatching { newResponse.newInstance(ok, routeRequest) }.getOrNull() ?: return@before
                if (!runCatching { open(context) }.getOrDefault(false)) return@before
                result = handled
                if (answered.compareAndSet(false, true)) env.reportStatus("guest_favorites_route_opened", "seen")
            }
        }
        env.reportStatus("guest_favorites_route", "installed")
        1
    }.getOrElse {
        env.logError("guest_favorites_route", "[BIL] Guest favorites route unavailable: ${it.javaClass.simpleName}")
        env.reportStatus("guest_favorites_route", "absent")
        0
    }

    internal companion object {
        const val ACTIVITY = "tv.danmaku.bili.ui.favorites.FavoritesActivityV2"

        /**
         * Start the host's favorites page. The menu calls `BLRouter.routeTo$default`, which passes no context (seen as null on
         * 9.0.0), so the host application is the fallback. False when neither is available.
         */
        fun openFavorites(target: Any?): Boolean {
            val context = target as? Context ?: ReflectAccess.currentApplication() ?: return false
            val intent = Intent().setClassName(context.packageName, ACTIVITY)
            if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return true
        }
    }
}
