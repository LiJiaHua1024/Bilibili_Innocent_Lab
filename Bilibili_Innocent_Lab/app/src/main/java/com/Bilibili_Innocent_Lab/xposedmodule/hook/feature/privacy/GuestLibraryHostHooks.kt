package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import com.highcapable.kavaref.extension.isSubclassOf
import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.TextView
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import com.highcapable.kavaref.extension.classOf
import com.highcapable.kavaref.extension.isStatic

internal class GuestLibraryHostHooks(
    private val library: GuestLibraryRuntime,
    private val isGuest: () -> Boolean
) {
    fun install(env: HookEnvironment): Int {
        val loader = env.classLoader ?: return 0
        var installed = 0
        fun attempt(action: () -> Unit) {
            runCatching(action).onSuccess { installed++ }.onFailure {
                env.logError("guest_library_entry", "[BIL] Local library entry unavailable")
            }
        }
        attempt {
            val manager = requireNotNull(Lookup.classOrNull(loader, MINE_MANAGER))
            val item = requireNotNull(Lookup.classOrNull(loader, MINE_ITEM))
            val uri = requireNotNull(Lookup.fieldOrNull(item, "uri"))
            require(uri.type == classOf<String>())
            val needsLogin = requireNotNull(Lookup.fieldOrNull(item, "needLogin")).also {
                require(it.type == classOf<Int>())
            }
            // main2.a.l(Context) marks the local-capable favorites/watch-later menu items as
            // needLogin=1 before their final routes are selected by MinePageManager providers.
            env.registrar.exact("guest.library.mine_register", manager, "c", item) {
                before {
                    if (!isGuest()) return@before
                    val target = argOrNull(0) ?: return@before
                    if (sectionForUri(uri.get(target) as? String) != null) needsLogin.setInt(target, 0)
                }
            }
            val activity = requireNotNull(Lookup.declaredFields(manager, true) {
                !it.isStatic && it.type isSubclassOf classOf<Activity>()
            }.singleOrNull())
            env.registrar.exact("guest.library.mine", manager, "h", item) {
                before {
                    if (!isGuest()) return@before
                    val target = argOrNull(0) ?: return@before
                    val section = sectionForUri(uri.get(target) as? String) ?: return@before
                    result = null
                    val host = instance?.let(activity::get) as? Activity ?: return@before
                    library.show(host, section)
                }
            }
        }
        // These verified providers perform the login check inside MinePageManager's redirect
        // coroutine. Intercept the providers too, including host paths that bypass manager.h().
        for ((providerName, section) in listOf("b" to "favorites", "r" to "watchLater")) attempt {
            val provider = requireNotNull(Lookup.classOrNull(loader,
                "tv.danmaku.bili.ui.main2.mine.provider.$providerName\$a"))
            val item = requireNotNull(Lookup.classOrNull(loader, MINE_ITEM))
            val observed = java.util.concurrent.atomic.AtomicBoolean(false)
            env.registrar.exact("guest.library.provider.$section.login", provider, "a", item) {
                before { if (isGuest()) result = false }
            }
            env.registrar.exact("guest.library.provider.$section.open", provider, "b", classOf<Activity>(), item) {
                before {
                    if (!isGuest()) return@before
                    result = null
                    if (observed.compareAndSet(false, true)) runCatching {
                        env.reportStatus("guest_library_$section", "applied")
                    }
                    (argOrNull(0) as? Activity)?.let { library.show(it, section) }
                }
            }
        }
        val fragment = Lookup.classOrNull(loader, MINE_FRAGMENT)
        if (fragment != null) {
            attempt {
                val getActivity = requireNotNull(Lookup.inheritedMethodOrNull(fragment, "getActivity"))
                env.registrar.exact("guest.library.follows", fragment, "Ur") {
                    before {
                        if (!isGuest()) return@before
                        result = null
                        val host = instance?.let(getActivity::invoke) as? Activity ?: return@before
                        library.show(host, "follows")
                    }
                }
            }
            attempt {
                env.registrar.exact("guest.library.mine_header", fragment, "onViewCreated", classOf<View>(), classOf<Bundle>()) {
                    after {
                        if (!isGuest() || hasThrowable) return@after
                        val root = argOrNull(0) as? View ?: return@after
                        bindLocalHeader(root)
                    }
                }
            }
        }
        if (fragment != null) attempt {
            val account = requireNotNull(Lookup.classOrNull(loader, "tv.danmaku.bili.ui.main2.api.AccountMine"))
            val getView = requireNotNull(Lookup.inheritedMethodOrNull(fragment, "getView"))
            // lr(AccountMine) reassigns the avatar/header listeners after account updates.
            env.registrar.exact("guest.library.mine_header_update", fragment, "lr", account) {
                after {
                    if (!isGuest() || hasThrowable) return@after
                    (instance?.let(getView::invoke) as? View)?.let(::bindLocalHeader)
                }
            }
        }
        attempt {
            val king = requireNotNull(Lookup.classOrNull(loader, KING_POSITION))
            val activity = requireNotNull(Lookup.declaredFields(king, true) {
                !it.isStatic && it.type isSubclassOf classOf<Activity>()
            }.singleOrNull())
            for (name in listOf("B0", "C0")) {
                val method = requireNotNull(Lookup.methodOrNull(king, name))
                require(method.returnType == Void.TYPE)
                env.registrar.exact("guest.library.favorite.$name", king, name) {
                    before {
                        if (!isGuest()) return@before
                        result = null
                        val host = instance?.let(activity::get) as? Activity ?: return@before
                        library.action(host, "favorite")
                    }
                }
            }
        }

        attempt {
            val access = PrivacyHostAccess(loader)
            val component = access.type("$KING_COMPONENT\$FavoriteComponent")
            val binding = access.type("cy3.t0")
            val continuation = access.type("kotlin.coroutines.Continuation")
            val favorite = requireNotNull(Lookup.fieldOrNull(binding, "e")).also {
                require(it.type isSubclassOf classOf<View>())
            }
            // 8.90.2 uses a touch-flow listener here; replacing onClick alone leaves that listener
            // consuming every gesture. Bind after the initial coroutine turn, before user interaction.
            env.registrar.exact("guest.library.favorite_view", component, "h", binding, continuation) {
                after {
                    if (!isGuest() || hasThrowable) return@after
                    val view = argOrNull(0)?.let(favorite::get) as? View ?: return@after
                    val componentReference = java.lang.ref.WeakReference(instance ?: return@after)
                    fun selectFavorite(target: View) {
                        runCatching {
                            val componentValue = componentReference.get() ?: return
                            val contract = requireNotNull(Lookup.fieldOrNull(component, "b")?.get(componentValue))
                            require(contract.javaClass.name == "$KING_POSITION\$create\$contract\$1")
                            val service = requireNotNull(Lookup.fieldOrNull(contract.javaClass, "h")?.get(contract))
                            val repository = requireNotNull(Lookup.fieldOrNull(service.javaClass, "h")?.get(service))
                            val reply = requireNotNull(Lookup.fieldOrNull(repository.javaClass, "b")?.get(repository))
                            val video = PrivacyVideoMetadata.read(access, reply).copy(
                                aid = (access.call(repository, "a") as Number).toLong(),
                                cid = (access.call(repository, "d") as Number).toLong())
                            library.action(target.context, "favorite", video)
                        }.onFailure { env.logError("guest_library_favorite", "[BIL] Local favorite metadata unavailable") }
                    }
                    view.post {
                        if (isGuest() && view.isAttachedToWindow) {
                            view.setOnTouchListener(null)
                            view.setOnClickListener { if (isGuest()) selectFavorite(it) }
                            view.setOnLongClickListener {
                                if (!isGuest()) false else { selectFavorite(it); true }
                            }
                        }
                    }
                }
            }
        }

        attempt {
            val access = PrivacyHostAccess(loader)
            val helper = access.type("com.bilibili.relation.utils.FollowFlowHelper")
            val mid = requireNotNull(Lookup.fieldOrNull(helper, "c")).also { require(it.type == classOf<Long>()) }
            val callback = requireNotNull(Lookup.fieldOrNull(helper, "i"))
            for (types in listOf(arrayOf(classOf<android.content.Context>(), classOf<String>()),
                arrayOf(classOf<android.content.Context>(), classOf<String>(), classOf<android.graphics.Rect>()))) {
                env.registrar.exact("guest.library.follow.${types.size}", helper, "onButtonClick", *types) {
                    before {
                        if (!isGuest()) return@before
                        result = null
                        val current = instance ?: return@before
                        val uid = mid.getLong(current)
                        if (uid <= 0) return@before
                        val weak = java.lang.ref.WeakReference(current)
                        library.toggleFollow(uid) { following ->
                            weak.get()?.let { target ->
                                if (mid.getLong(target) == uid) {
                                    access.call(target, "setIsAttention", following)
                                    callback.get(target)?.let { cb ->
                                        access.call(cb, if (following) "onFollowSuccess" else "onUnFollowSuccess")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        attempt {
            val access = PrivacyHostAccess(loader)
            val menu = access.type("com.bilibili.ship.theseus.united.page.toolbar.MenuService")
            val activity = requireNotNull(Lookup.fieldOrNull(menu, "b")).also {
                require(it.type isSubclassOf classOf<Activity>())
            }
            val unit = requireNotNull(Lookup.fieldOrNull(access.type("kotlin.Unit"), "INSTANCE")).get(null)
            env.registrar.exact("guest.library.watch_later", menu, "P1", menu) {
                before {
                    if (!isGuest()) return@before
                    result = unit
                    val target = argOrNull(0) ?: return@before
                    val host = activity.get(target) as? Activity ?: return@before
                    library.action(host, "watchLater")
                }
            }
        }
        return installed
    }

    private fun bindLocalHeader(root: View) {
        // These are verified host resource names; module R cannot reference the host's resource IDs.
        fun view(name: String): View? = root.findViewById(root.resources.getIdentifier(name, "id", "tv.danmaku.bili"))
        val strings = GuestLibraryStrings.resolve(root.context)
        view("mine_user_info_layout")?.setOnLongClickListener {
            if (!isGuest()) return@setOnLongClickListener false
            library.show(it.context)
            true
        }
        view("avatar_layout")?.apply {
            contentDescription = strings.title
            setOnClickListener { if (isGuest()) library.show(it.context) }
        }
        (view("tv_login") as? TextView)?.apply {
            text = "${if (library.incognito) strings.incognitoTitle else strings.title} · ${strings.signIn}"
            contentDescription = text
            if (library.incognito) setOnClickListener {
                if (isGuest()) android.app.AlertDialog.Builder(it.context)
                    .setMessage(strings.exitToSignIn).setPositiveButton(android.R.string.ok, null).show()
            }
        }
    }

    companion object {
        private const val MINE_MANAGER = "tv.danmaku.bili.ui.main2.mine.MinePageManager"
        private const val MINE_ITEM = "com.bilibili.lib.homepage.mine.MenuGroup\$Item"
        private const val MINE_FRAGMENT = "tv.danmaku.bili.ui.main2.mine.HomeUserCenterFragment"
        private const val KING_COMPONENT = "com.bilibili.ship.theseus.united.page.intro.module.kingposition.KingPositionComponent2"
        private const val KING_POSITION = "com.bilibili.ship.theseus.united.page.intro.module.kingposition.KingPositionService"

        fun sectionForUri(uri: String?): String? = when (uri?.substringBefore('?')?.trimEnd('/')) {
            "bilibili://main/drawer/history" -> "history"
            "bilibili://main/drawer/favorites", "bilibili://main/favorite",
            "bilibili://user_center/favourite", "bilibili://user_center/favourite2",
            "bilibili://user_center/favorite_v2/favorite",
            "bilibili://user_center/favorite_v2/favorite/folders" -> "favorites"
            "bilibili://main/drawer/watch-later", "bilibili://user_center/watch_later_v2",
            "bilibili://compose/watch_later/kntr" -> "watchLater"
            else -> null
        }
    }
}
