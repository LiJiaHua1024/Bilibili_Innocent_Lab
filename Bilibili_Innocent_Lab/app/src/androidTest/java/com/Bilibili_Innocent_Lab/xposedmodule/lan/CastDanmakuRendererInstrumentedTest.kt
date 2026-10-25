package com.Bilibili_Innocent_Lab.xposedmodule.lan

import android.graphics.Color
import android.graphics.Canvas
import android.opengl.EGL14
import android.opengl.GLES20
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation.FrameGl
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Offscreen GLES/Canvas regression, no network, host navigation, TV or media credentials. */
@RunWith(AndroidJUnit4::class)
class CastDanmakuRendererInstrumentedTest {
    private val width = 1280
    private val height = 532
    private val background = Color.rgb(32, 48, 64)
    private val style = CastDanmakuStyle()

    private fun withGl(action: () -> Unit) {
        val egl = checkNotNull(FrameGl.create(EGL14.EGL_PBUFFER_BIT))
        val surface = EGL14.eglCreatePbufferSurface(egl.display, egl.config,
            intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE), 0)
        try {
            check(surface != EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(egl.display, surface, surface, egl.context))
            GLES20.glViewport(0, 0, width, height)
            action()
        } finally {
            EGL14.eglMakeCurrent(egl.display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(egl.display, surface)
            FrameGl.release(egl)
            EGL14.eglTerminate(egl.display)
        }
    }

    private fun clear() {
        GLES20.glClearColor(32f / 255, 48f / 255, 64f / 255, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
    }

    private fun compare(reference: CastDanmakuReferencePainter, time: Long) {
        val expected = createBitmap(width, height)
        try {
            expected.eraseColor(background)
            reference.draw(Canvas(expected), time)
            val pixels = ByteBuffer.allocateDirect(width * height * 4)
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
            assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError())
            val baseline = IntArray(width * height)
            expected.getPixels(baseline, 0, width, 0, 0, width, height)
            var totalError = 0L; var severe = 0; var ink = 0
            repeat(height) { y -> repeat(width) { x ->
                val color = baseline[y * width + x]
                val offset = ((height - 1 - y) * width + x) * 4
                val error = abs((pixels.get(offset).toInt() and 255) - Color.red(color)) +
                    abs((pixels.get(offset + 1).toInt() and 255) - Color.green(color)) +
                    abs((pixels.get(offset + 2).toInt() and 255) - Color.blue(color))
                totalError += error
                if (error > 96) severe++
                if (color != background) ink++
            } }
            assertTrue("reference contains visible text", ink > 100)
            // Canvas subpixel rasterization and GLES bilinear translation differ at antialiased edges.
            assertTrue("mean channel error ${totalError.toDouble() / (width * height * 3)}", totalError < width.toLong() * height * 6)
            assertTrue("severe pixels $severe", severe < width * height / 50)
        } finally { expected.recycle() }
    }

    @Test fun cachedSpritesMatchCanvasForChineseColorOpacityModesAndRewind() = withGl {
        val comments = listOf(
            CastDanmaku(0, 1, Color.WHITE, "滚动弹幕：中文、English 与数字 123"),
            CastDanmaku(0, 6, Color.CYAN, "反向弹幕 👋 café"),
            CastDanmaku(0, 4, Color.YELLOW, "底部弹幕"),
            CastDanmaku(0, 5, Color.RED, "顶部弹幕"))
        val metrics = CastPreparationMetrics()
        val reference = CastDanmakuReferencePainter(comments, width, height, style)
        CastDanmakuRenderer(comments, width, height, style, metrics).use { renderer ->
            for (time in listOf(2000L, 3000L, 2000L)) { clear(); renderer.draw(time); compare(reference, time) }
            val uploads = metrics.textUploads
            repeat(240) { clear(); renderer.draw(2000) }
            assertEquals("same text must not be re-uploaded each frame", uploads, metrics.textUploads)
            assertTrue(metrics.uploadedBytes < width.toLong() * height * 4)
        }
    }

    @Test fun longUnicodeTextAndAtlasReuseDoNotCorruptQueuedDraws() = withGl {
        val comments = (0..20).map { i ->
            CastDanmaku(i * 5000L, 5, Color.rgb(60 + i * 7, 210 - i * 5, 160), "跨纹理边界中文👋é${i} ".repeat(35))
        }
        val metrics = CastPreparationMetrics()
        val reference = CastDanmakuReferencePainter(comments, width, height, style)
        CastDanmakuRenderer(comments, width, height, style, metrics, maxAtlasSide = 256).use { renderer ->
            for (i in 0..20) {
                val time = i * 5000L + 2000
                clear(); renderer.draw(time); compare(reference, time)
            }
            assertTrue("exercise atlas reuse", metrics.atlasResets > 0)
            assertTrue(metrics.atlasBytes <= 256L * 256 * 4)
        }
    }
}
