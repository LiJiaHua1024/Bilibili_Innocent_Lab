package com.Bilibili_Innocent_Lab.xposedmodule.ui.activity

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 比较真实 Drawable 输出；不打开业务面板、不修改遥测设置。 */
@RunWith(AndroidJUnit4::class)
class CoveredPanelSurfaceInstrumentedTest {
    private class Fixture(withCardBackground: Boolean = false) {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        val layer = IconAnchoredMotionLayer(
            context,
            GradientDrawable().apply {
                setColor(Color.rgb(24, 24, 24))
                cornerRadius = 20f
                setStroke(2, Color.rgb(220, 80, 80))
            },
            Color.rgb(24, 24, 24),
            20f
        )
        val card = View(context).apply {
            if (withCardBackground) background = ColorDrawable(Color.RED)
        }
        val controller: IconAnchoredMotionController

        init {
            layer.addView(card, FrameLayout.LayoutParams(200, 140).apply {
                leftMargin = 40
                topMargin = 50
            })
            layer.measure(
                View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(260, View.MeasureSpec.EXACTLY)
            )
            layer.layout(0, 0, 300, 260)
            controller = IconAnchoredMotionController(
                layer, card, ColorDrawable(Color.MAGENTA),
                resolveGeometry = {
                    IconAnchoredMotionGeometry(
                        SettingsBackupMotionRect(180f, 150f, 210f, 180f),
                        SettingsBackupMotionRect(40f, 50f, 240f, 190f),
                        15f, 20f, 14f
                    )
                },
                onClosed = {}
            )
        }

        fun pixels(): IntArray {
            val bitmap = Bitmap.createBitmap(300, 260, Bitmap.Config.ARGB_8888)
            return try {
                layer.draw(Canvas(bitmap))
                IntArray(300 * 260).also { bitmap.getPixels(it, 0, 300, 0, 0, 300, 260) }
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test fun expandedEndpointKeepsExactlyTheSameSurfacePixels() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val f = Fixture()
            f.layer.applyFrame(40f, 50f, 240f, 190f, 20f)
            val lastFrame = f.pixels()
            f.controller.snapToExpanded()
            assertArrayEquals(lastFrame, f.pixels())
            assertFalse(f.layer.clipToOutline)
            assertEquals(0, Color.alpha(lastFrame[100 * 300 + 39]))
            assertTrue(Color.red(lastFrame[100 * 300 + 41]) > 100)
        }
    }

    @Test fun persistentSurfaceOwnsTheFillDuringEntryRestAndPredictiveBack() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val f = Fixture(withCardBackground = true)
            assertTrue(f.controller.prepareFirstFrame())
            assertEquals(0, f.card.background.alpha)
            f.controller.snapToExpanded()
            assertEquals(0, f.card.background.alpha)
            val persistentOnly = f.pixels()
            assertTrue(f.controller.beginPredictiveBack())
            assertEquals(0, f.card.background.alpha)
            assertArrayEquals(persistentOnly, f.pixels())
            f.controller.progressPredictiveBack(.5f)
            assertEquals(0, f.card.background.alpha)
            f.controller.cancelMotion()
        }
    }

    @Test fun startingAndCancellingBackDoesNotReplaceTheBorderOrFillTheWindow() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val f = Fixture()
            f.controller.snapToExpanded()
            val resting = f.pixels()
            assertTrue(f.controller.beginPredictiveBack())
            assertArrayEquals(resting, f.pixels())
            f.controller.cancelPredictiveBack()
            assertArrayEquals(resting, f.pixels())
            f.controller.cancelMotion()
        }
    }

    @Test fun borderMovesWithTheSurfaceInsteadOfClippingTheFinalCardBorder() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val f = Fixture()
            f.layer.applyFrame(70f, 70f, 170f, 150f, 15f)
            val moving = f.pixels()
            assertEquals(0, Color.alpha(moving[100 * 300 + 41]))
            assertEquals(0, Color.alpha(moving[100 * 300 + 69]))
            assertTrue(Color.red(moving[100 * 300 + 71]) > 100)
            assertEquals(Color.rgb(24, 24, 24), moving[100 * 300 + 110])
        }
    }
}
