package com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.chrome

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundConfig
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundDrawable
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.background.HostBackgroundPreset
import com.Bilibili_Innocent_Lab.xposedmodule.hook.hostui.common.HostChromeContentSource
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.NativePaintFixtureActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Software scene-input checks on attached coordinates, without screen input or host preferences. */
@RunWith(AndroidJUnit4::class)
class HostComposeBackdropSourceInstrumentedTest {
    private var window: ActivityScenario<NativePaintFixtureActivity>? = null

    @Test fun composeBaseAndWallpaperRetainTheirSceneOwnership() {
        val failures = ArrayList<Throwable>()
        try {
            ActivityScenario.launch(NativePaintFixtureActivity::class.java).use { scenario ->
                window = scenario
                listOf(::hiddenWindowColorCannotReplaceTheVisibleComposeBaseInEitherTheme,
                    ::ordinaryViewSourcesStillCompositeTheirVisibleAncestorBackground,
                    ::hiddenWindowChangesDoNotInvalidateTheComposeScene,
                    ::configuredWallpaperAndItsThemeVersionRemainPartOfTheComposeScene).forEach { check ->
                    runCatching(check).onFailure(failures::add)
                }
            }
        } finally { window = null }
        if (failures.isNotEmpty()) throw AssertionError("Scene ownership checks failed: ${failures.map { it.message }}")
            .also { error -> failures.forEach(error::addSuppressed) }
    }

    private fun scene(block: (FrameLayout, FrameLayout) -> Unit) {
        requireNotNull(window).onActivity { activity ->
            val root = FrameLayout(activity).apply { keepScreenOn = true }
            val owner = FrameLayout(root.context)
            root.addView(owner)
            activity.setContentView(root)
            root.layout(0, 0, 64, 64); owner.layout(0, 0, 64, 64)
            assertTrue("Source coordinates must be attached", owner.isAttachedToWindow)
            block(root, owner)
        }
    }

    private fun composeSource(owner: FrameLayout, base: Int) = HostChromeContentSource(owner, base,
        includeViewContent = false, composeWindowBase = true)

    private fun image(draw: (Canvas) -> Unit): Bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        .also { draw(Canvas(it)) }

    private fun hiddenWindowColorCannotReplaceTheVisibleComposeBaseInEitherTheme() = scene { root, owner ->
        for (base in listOf(Color.WHITE, 0xFF30343A.toInt())) {
            root.setBackgroundColor(if (base == Color.WHITE) Color.BLACK else Color.WHITE)
            val result = image(composeSource(owner, base)::drawContent)
            try { assertEquals("Compose base must cover its hidden Window background", base, result.getPixel(32, 32)) }
            finally { result.recycle() }
        }
    }

    private fun ordinaryViewSourcesStillCompositeTheirVisibleAncestorBackground() = scene { root, owner ->
        root.setBackgroundColor(Color.BLACK)
        val source = HostChromeContentSource(owner, Color.WHITE, includeViewContent = false)
        val result = image(source::drawContent)
        try { assertEquals(Color.BLACK, result.getPixel(32, 32)) } finally { result.recycle() }
    }

    private fun hiddenWindowChangesDoNotInvalidateTheComposeScene() = scene { root, owner ->
        root.setBackgroundColor(Color.BLACK)
        val source = composeSource(owner, Color.WHITE)
        val before = source.contentVersion
        root.setBackgroundColor(Color.MAGENTA)
        assertEquals(before, source.contentVersion)
    }

    private fun configuredWallpaperAndItsThemeVersionRemainPartOfTheComposeScene() = scene { root, owner ->
        val artwork = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.CYAN) }
        try {
            val wallpaper = HostBackgroundDrawable(HostBackgroundConfig(preset = HostBackgroundPreset.CUSTOM,
                blur = 0, saturation = 100, veil = 0), image = artwork, cacheEnabled = false)
            wallpaper.setBounds(0, 0, 64, 64)
            root.background = wallpaper
            val source = composeSource(owner, Color.WHITE)
            for (night in listOf(false, true)) {
                val before = source.contentVersion
                wallpaper.night = night
                if (night) assertNotEquals(before, source.contentVersion)
                val actual = image(source::drawContent)
                val expected = image { canvas ->
                    canvas.drawColor(Color.WHITE)
                    wallpaper.setBounds(0, 0, 64, 64); wallpaper.draw(canvas)
                }
                try { assertEquals("The same wallpaper recipe and coordinates must survive", expected.getPixel(32, 32), actual.getPixel(32, 32)) }
                finally { actual.recycle(); expected.recycle() }
            }
        } finally {
            root.background = null
            artwork.recycle()
        }
    }
}
