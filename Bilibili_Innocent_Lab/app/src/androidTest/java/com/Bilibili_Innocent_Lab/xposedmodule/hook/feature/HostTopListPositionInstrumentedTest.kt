package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.material.ModernMaterialPolicy
import com.Bilibili_Innocent_Lab.xposedmodule.ui.skin.model.SurfaceRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostTopListPositionInstrumentedTest {
    @Test fun visualOccupancyDoesNotFeedBackIntoTheNativeScrollDistance() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val list = RecyclerView(instrumentation.targetContext)
            list.layoutManager = LinearLayoutManager(list.context)
            list.setPadding(0, 56, 0, 0)
            list.clipToPadding = false
            list.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun getItemCount() = 12
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                    object : RecyclerView.ViewHolder(View(parent.context).apply {
                        layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 240)
                    }) {}
                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) = Unit
            }
            list.measure(View.MeasureSpec.makeMeasureSpec(393, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
            list.layout(0, 0, 393, 800)
            val access = HostTopListPosition(list)
            assertEquals(0f, checkNotNull(access.edge()).distance, 0f)
            list.scrollBy(0, 100)
            assertEquals(100f, checkNotNull(access.edge()).distance, 0f)
            list.translationY = 244f
            assertEquals(100f, checkNotNull(access.edge()).distance, 0f)
            list.scrollBy(0, 1000)
            assertEquals(Float.POSITIVE_INFINITY, checkNotNull(access.edge()).distance, 0f)
            list.scrollBy(0, -10000)
            assertEquals(0f, checkNotNull(access.edge()).distance, 0f)
        }
    }

    @Test fun dockedSearchRegionIsOpaqueWhiteAndFirstCardHasNoScrim() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val colors = HostChromeColors(false, 0xFFFF6699.toInt())
            val band = HostTopStatusFusionView(instrumentation.targetContext, 1f, colors.palette(),
                ModernMaterialPolicy.surface(SurfaceRole.TOP_BAR, false), null, 24, 146)
            band.updateFade(144)
            band.updateDocking(1f, 88)
            band.alpha = 1f
            band.layout(0, 0, 393, 146)
            val bitmap = Bitmap.createBitmap(393, 146, Bitmap.Config.ARGB_8888)
            try {
                band.draw(Canvas(bitmap))
                assertEquals(0xFFFFFFFF.toInt(), bitmap.getPixel(100, 60))
                assertEquals(0, bitmap.getPixel(100, 144) ushr 24)
                assertEquals(0f, band.translationZ, 0f)
                band.updateDocking(0f, 88)
                bitmap.eraseColor(0)
                band.draw(Canvas(bitmap))
                assertTrue(bitmap.getPixel(100, 60) ushr 24 < 255)
                assertTrue(band.translationZ > 0f)
                val dark = HostChromeColors(true, colors.accent)
                band.updateMaterial(dark.palette(), ModernMaterialPolicy.surface(SurfaceRole.TOP_BAR, true))
                band.updateDocking(1f, 88)
                bitmap.eraseColor(0)
                band.draw(Canvas(bitmap))
                assertEquals(dark.palette().surface, bitmap.getPixel(100, 60))
                band.updateMaterial(colors.palette(), ModernMaterialPolicy.surface(SurfaceRole.TOP_BAR, false))
                bitmap.eraseColor(0)
                band.draw(Canvas(bitmap))
                assertEquals(0xFFFFFFFF.toInt(), bitmap.getPixel(100, 60))
            } finally {
                bitmap.recycle()
            }
        }
    }
}
