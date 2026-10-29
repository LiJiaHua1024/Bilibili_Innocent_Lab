package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.privacy

import android.content.Context
import android.graphics.Rect
import android.view.View
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HookEnvironment
import com.Bilibili_Innocent_Lab.xposedmodule.runtime.KavaMemberLookup as Lookup
import java.lang.ref.WeakReference
import java.lang.reflect.Method

/**
 * Where the vertical Story player's favorite star gets its video from: the star widget itself.
 *
 * Story's login request carries no video, and the Story player does not feed the playback hooks that record the metadata of a
 * video page. But every `StoryFavoriteWidget` holds a controller whose `getData()` returns the `StoryDetail` it is showing, and
 * `StoryDetail` has plain getters (aid, cid, title, cover, duration, owner, request user) that are the same on all 38 builds
 * (hostlab q46). The controller field and its interface are obfuscated and renamed between builds, so the field is found by
 * type: the one field of the widget whose type has `getData()` returning `StoryDetail`. Each new widget registers itself weakly
 * at construction, and the tapped one is the widget with the largest visible area; a tie means it cannot be told, and the
 * caller keeps the login screen.
 *
 * Status keys: `guest_video_favorite_story_source` is `installed` or `absent`; `guest_video_favorite_story_why` carries the reason
 * the tapped video could not be told (`no-widget`, `no-data`, `ambiguous`, ...) as counts only, never content.
 */
internal class GuestStoryStar(
    private val widgetName: String = WIDGET,
    private val detailName: String = DETAIL
) : GuestVideoFavoriteHooks.StoryStarSource {
    private class Reader(
        val controllerField: java.lang.reflect.Field,
        /** The star icon: the widget's one ImageView field. Its selected state is what the host paints as favorited. */
        val iconField: java.lang.reflect.Field?,
        val getData: Method,
        val aid: Method, val cid: Method, val title: Method, val cover: Method, val duration: Method,
        val owner: Method, val ownerMid: Method, val ownerName: Method, val ownerFace: Method,
        val requestUser: Method, val setFavorite: Method
    )

    private val widgets = ArrayList<WeakReference<View>>()
    @Volatile private var reader: Reader? = null
    @Volatile private var report: (String) -> Unit = {}

    /** Why [tapped] returned null, as a status key value; it never carries content. */
    private fun why(reason: String): GuestVideoFavoriteHooks.StoryTap? {
        report(reason)
        return null
    }

    override fun install(env: HookEnvironment, loader: ClassLoader): Boolean = runCatching {
        report = { env.reportStatus("guest_video_favorite_story_why", it) }
        val widget = PrivacyStructuralResolver.type(loader, widgetName) ?: return@runCatching false
        val detail = PrivacyStructuralResolver.type(loader, detailName) ?: return@runCatching false
        val owner = PrivacyStructuralResolver.type(loader, "$detailName\$Owner") ?: return@runCatching false
        val user = PrivacyStructuralResolver.type(loader, "$detailName\$RequestUser") ?: return@runCatching false
        val controller = PrivacyStructuralResolver.uniqueField(widget) { field ->
            !field.type.isPrimitive && PrivacyStructuralResolver.method(field.type, "getData")?.returnType == detail
        } ?: return@runCatching false
        val icon = PrivacyStructuralResolver.uniqueField(widget) { it.type == android.widget.ImageView::class.java }
        fun m(owner: Class<*>, name: String, vararg params: Class<*>) = PrivacyStructuralResolver.method(owner, name, *params)
        val read = Reader(
            controller, icon, m(controller.type, "getData") ?: return@runCatching false,
            m(detail, "getAid") ?: return@runCatching false, m(detail, "getCid") ?: return@runCatching false,
            m(detail, "getTitle") ?: return@runCatching false, m(detail, "getCover") ?: return@runCatching false,
            m(detail, "getDuration") ?: return@runCatching false, m(detail, "getOwner") ?: return@runCatching false,
            m(owner, "getMid") ?: return@runCatching false, m(owner, "getName") ?: return@runCatching false,
            m(owner, "getFace") ?: return@runCatching false, m(detail, "getRequestUser") ?: return@runCatching false,
            m(user, "setFavorite", java.lang.Boolean.TYPE) ?: return@runCatching false
        )
        val constructors = Lookup.declaredConstructors(widget) { java.lang.reflect.Modifier.isPublic(it.modifiers) && !it.isSynthetic }
        if (constructors.isEmpty()) return@runCatching false
        constructors.forEachIndexed { index, constructor ->
            env.registrar.constructor("guest.video.favorite.story.widget.$index", constructor) {
                after { if (!hasThrowable) (instance as? View)?.let(::register) }
            }
        }
        reader = read
        true
    }.getOrDefault(false).also { env.reportStatus("guest_video_favorite_story_source", if (it) "installed" else "absent") }

    private fun register(view: View) = synchronized(widgets) {
        widgets.removeAll { it.get() == null }
        widgets += WeakReference(view)
    }

    /** Per registered widget: shown, attached, size, and whether any part of it is on screen. Counts only, no content. */
    private fun describeWidgets(): String = synchronized(widgets) { widgets.mapNotNull { it.get() } }.joinToString(";") { view ->
        val rect = Rect()
        "s${if (view.isShown) 1 else 0}a${if (view.isAttachedToWindow) 1 else 0}v${if (view.getGlobalVisibleRect(rect)) 1 else 0}" +
            "w${view.width}h${view.height}al${view.alpha}"
    }

    /** The widgets that are on screen right now, with their visible area, largest first. */
    private fun visibleWidgets(): List<Pair<View, Long>> = synchronized(widgets) { widgets.mapNotNull { it.get() } }.mapNotNull { view ->
        val rect = Rect()
        if (view.isShown && view.isAttachedToWindow && view.getGlobalVisibleRect(rect)) view to rect.width().toLong() * rect.height() else null
    }.sortedByDescending { it.second }

    private class Candidate(val view: View, val area: Long, val detail: Any, val video: LocalVirtualLibrary.Video)

    /** Reads the video one widget is showing; null when its data is not there yet or not usable. */
    private fun candidate(read: Reader, view: View, area: Long): Candidate? {
        val controller = read.controllerField.apply { isAccessible = true }.get(view) ?: return null
        val detail = read.getData.invoke(controller) ?: return null
        val aid = (read.aid.invoke(detail) as Number).toLong()
        val cid = (read.cid.invoke(detail) as Number).toLong()
        val title = (read.title.invoke(detail) as? String).orEmpty().trim()
        if (aid <= 0 || cid <= 0 || title.isEmpty()) return null
        val owner = read.owner.invoke(detail)
        val video = LocalVirtualLibrary.Video(
            aid, cid, title.take(LocalVirtualLibrary.MAX_TITLE_LENGTH),
            durationMs = (read.duration.invoke(detail) as Number).toLong() * 1000L, positionMs = 0L, updatedAtMs = System.currentTimeMillis(),
            cover = (read.cover.invoke(detail) as? String)?.takeIf { it.isNotBlank() && it.length <= LocalVirtualLibrary.MAX_COVER_LENGTH },
            authorMid = (owner?.let { read.ownerMid.invoke(it) } as? Number)?.toLong() ?: 0L,
            authorName = (owner?.let { read.ownerName.invoke(it) } as? String).orEmpty().take(LocalVirtualLibrary.MAX_NAME_LENGTH),
            authorFace = (owner?.let { read.ownerFace.invoke(it) } as? String).orEmpty().takeIf { it.length <= LocalVirtualLibrary.MAX_COVER_LENGTH }.orEmpty()
        )
        return Candidate(view, area, detail, video)
    }

    /**
     * Several widgets can be on screen at once (the page and a neighbour sliding in), so every visible one is read. When they
     * all show the same video there is nothing to decide; when they differ, the one with the larger visible area wins, and an
     * exact tie means it cannot be told, so the caller keeps the login screen.
     */
    override fun tapped(): GuestVideoFavoriteHooks.StoryTap? {
        val read = reader ?: return why("no-reader")
        val visible = visibleWidgets()
        if (visible.isEmpty()) return why("no-widget:registered=${synchronized(widgets) { widgets.count { it.get() != null } }},${describeWidgets()}")
        return runCatching {
            val candidates = visible.mapNotNull { (view, area) -> candidate(read, view, area) }
            if (candidates.isEmpty()) return why("no-data:visible=${visible.size}")
            val aids = candidates.map { it.video.aid }.distinct()
            val chosen = if (aids.size == 1) candidates.first() else {
                val sorted = candidates.sortedByDescending { it.area }
                if (sorted[0].area == sorted[1].area) return why("ambiguous:visible=${visible.size},aids=${aids.size}")
                sorted.first()
            }
            val same = candidates.filter { it.video.aid == chosen.video.aid }
            GuestVideoFavoriteHooks.StoryTap(chosen.view.context, chosen.video) { favorited ->
                // The Story star paints from the item's own request-user state, so keep every copy of this item in step.
                same.forEach { match ->
                    runCatching { read.requestUser.invoke(match.detail)?.let { read.setFavorite.invoke(it, favorited) } }
                    // The icon is painted from the selected state of the widget's ImageView; set it on the UI thread.
                    match.view.post {
                        runCatching { (read.iconField?.apply { isAccessible = true }?.get(match.view) as? android.widget.ImageView)?.isSelected = favorited }
                    }
                }
            }
        }.getOrElse { why("read-failed:${it.javaClass.simpleName}") }
    }

    companion object {
        const val WIDGET = "com.bilibili.video.story.action.widget.StoryFavoriteWidget"
        const val DETAIL = "com.bilibili.video.story.StoryDetail"
    }
}
