package com.Bilibili_Innocent_Lab.xposedmodule.ui.skin

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.ui.activity.MainActivity
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.background.LiquidBackgroundImportResult
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.background.LiquidBackgroundStore
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.engine.GlowEngine
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.engine.GlowEngineCallbacks
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.liquid.LiquidActivityRenderer
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.material.FrostedMaterialRenderer
import com.Bilibili_Innocent_Lab.xposedmodule.ui.theme.MonetColors
import com.lumen.coacervation.engine.host.LumenBackdropReadiness
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Exercise the module's actual renderer adapters, including fresh palette instances across owners. */
@RunWith(AndroidJUnit4::class)
class SkinBackdropContinuityInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun palette(dark: Boolean) = MonetColors(0xff5577aa.toInt(), Color.WHITE,
        0xff8877aa.toInt(), 0xffaa7788.toInt(),
        if (dark) 0xff24252a.toInt() else 0xfffcfbfe.toInt(),
        if (dark) 0xff101114.toInt() else 0xfff3f2f6.toInt(),
        if (dark) 0xff292a30.toInt() else 0xfff8f7fb.toInt())

    private fun pixels(root: View): IntArray {
        val image = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
        return try {
            val canvas = Canvas(image)
            canvas.scale(20f / root.width, 20f / root.height)
            root.background.setBounds(0, 0, root.width, root.height)
            root.background.draw(canvas)
            intArrayOf(image.getPixel(5, 8), image.getPixel(10, 10), image.getPixel(15, 15))
        } finally { image.recycle() }
    }

    @Test fun publishedCustomPixelsSurviveResumeAndNewPaletteOwnersInBothMaterialsAndThemes() {
        val context = instrumentation.targetContext
        val input = File(context.cacheDir, "skin-continuity-fixture.png")
        val image = Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888)
        try {
            image.eraseColor(Color.rgb(18, 205, 230))
            input.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { image.recycle() }
        assertTrue(LiquidBackgroundStore.importFromUri(context, Uri.fromFile(input)) is LiquidBackgroundImportResult.Success)
        try {
            val materials = if (Build.VERSION.SDK_INT >= 31) listOf(false, true) else listOf(false)
            for (liquid in materials) for (dark in listOf(false, true)) {
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    lateinit var renderer: GlowEngine
                    lateinit var root: FrameLayout
                    fun bind(activity: MainActivity, width: Int = 0, height: Int = 0) {
                        val colors = palette(dark) // A new object with exactly the same values.
                        renderer = if (liquid) LiquidActivityRenderer(activity, colors)
                            else FrostedMaterialRenderer(colors, activity.resources.displayMetrics.density,
                                activity.applicationContext)
                        root = FrameLayout(activity)
                        activity.setContentView(root)
                        if (width > 0 && height > 0) {
                            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                            root.layout(0, 0, width, height)
                        }
                        assertTrue(renderer.bindRoot(root, GlowEngineCallbacks({}, {})))
                        renderer.onStart()
                        (root.background as LumenBackdropReadiness).prepareForMotion()
                    }
                    scenario.onActivity { bind(it) }
                    try {
                        val deadline = SystemClock.uptimeMillis() + 5_000
                        var ready = false
                        while (!ready && SystemClock.uptimeMillis() < deadline) {
                            scenario.onActivity {
                                if (root.width > 0 && root.height > 0 &&
                                    (root.background as LumenBackdropReadiness).isReadyForMotion) {
                                    val color = pixels(root)[1]
                                    assertTrue("liquid=$liquid dark=$dark ready without skin",
                                        kotlin.math.abs(Color.green(color) - Color.red(color)) > 60)
                                    ready = true
                                }
                            }
                            if (!ready) SystemClock.sleep(16)
                        }
                        assertTrue("Custom skin preparation timed out", ready)
                        scenario.onActivity { activity ->
                            val expected = pixels(root)
                            renderer.onStop(); renderer.onStart()
                            assertArrayEquals("Resume showed theme-only pixels", expected, pixels(root))
                            val width = root.width; val height = root.height
                            renderer.close()
                            bind(activity, width, height)
                            assertArrayEquals("A new owner with equal palette values missed the pixels", expected, pixels(root))
                        }
                    } finally { scenario.onActivity { renderer.close() } }
                }
            }
        } finally { LiquidBackgroundStore.restoreAutomatic(context) }
    }
}
