package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostVideoCardPlayerScopeInstrumentedTest {
    private class Card(private val context: Context, endPage: Boolean) {
        private fun id(name: String): Int = context.resources.getIdentifier(name, "id", "tv.danmaku.bili")
            .also { assertTrue("Missing host resource: $name", it != 0) }

        val root = FrameLayout(context)
        val cover = ImageView(context).apply { id = id("cover") }
        val info = FrameLayout(context).apply { id = id("cover_bottom_info_container") }
        val background = ColorDrawable(Color.TRANSPARENT)

        init {
            root.background = background
            val coverRoot = FrameLayout(context)
            coverRoot.addView(cover, FrameLayout.LayoutParams(200, 100))
            if (endPage) coverRoot.addView(View(context).apply { id = id("endpage_progess_root") })
            root.addView(coverRoot, FrameLayout.LayoutParams(200, 100))
            root.addView(TextView(context).apply { id = id("title"); text = "Video" })
            root.addView(info, FrameLayout.LayoutParams(200, 20))
        }

        fun attach(player: Boolean) {
            val parent = FrameLayout(root.context)
            if (player) parent.id = id("video_container")
            val list = RecyclerView(root.context)
            list.layoutManager = LinearLayoutManager(root.context)
            parent.addView(list)
            list.layout(0, 0, 1080, 1800)
            list.addView(root, RecyclerView.LayoutParams(400, 200))
            root.layout(0, 0, 400, 200)
            cover.layout(0, 0, 200, 100)
        }

        fun assertUnmodified() {
            assertSame(background, root.background)
            assertSame(ViewOutlineProvider.BACKGROUND, cover.outlineProvider)
            assertFalse(cover.clipToOutline)
            assertFalse(root.clipToOutline)
            val params = info.layoutParams as ViewGroup.MarginLayoutParams
            assertEquals(0, params.leftMargin)
            assertEquals(0, params.rightMargin)
            assertEquals(0, params.bottomMargin)
        }
    }

    private fun check(block: (Context, HostVideoCardStyle) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = runCatching {
            instrumentation.targetContext.createPackageContext("tv.danmaku.bili", 0)
        }.getOrNull()
        assumeTrue("Requires the installed host's card resources", context != null)
        instrumentation.runOnMainSync {
            val style = HostVideoCardStyle(null, HostVideoCardHostAccess(javaClass.classLoader!!), -1,
                onApplied = {}, onError = { throw AssertionError(it) })
            block(requireNotNull(context), style)
        }
    }

    @Test fun endPageCardIsExcludedBeforeAttachmentAndAfterLayout() = check { context, style ->
        val card = Card(context, endPage = true)
        style.bind(card.root)
        card.assertUnmodified()
        card.attach(player = false)
        style.bind(card.root)
        card.assertUnmodified()
    }

    @Test fun playerAncestorExcludesCardsWithoutEndPageMarker() = check { context, style ->
        val card = Card(context, endPage = false)
        card.attach(player = true)
        style.bind(card.root)
        card.assertUnmodified()
    }

    @Test fun latePlayerAttachmentDoesNotDecorateCover() = check { context, style ->
        val card = Card(context, endPage = false)
        style.bind(card.root)
        card.attach(player = true)
        assertSame(card.background, card.root.background)
        assertSame(ViewOutlineProvider.BACKGROUND, card.cover.outlineProvider)
        assertFalse(card.cover.clipToOutline)
        assertFalse(card.root.clipToOutline)
    }

    @Test fun ordinaryVideoListStillStylesCoversAndInfo() = check { context, style ->
        val card = Card(context, endPage = false)
        card.attach(player = false)
        style.bind(card.root)
        assertTrue(card.cover.clipToOutline)
        val params = card.info.layoutParams as ViewGroup.MarginLayoutParams
        assertTrue(params.leftMargin > 0)
        assertTrue(params.rightMargin > 0)
        assertTrue(params.bottomMargin > 0)
    }
}
