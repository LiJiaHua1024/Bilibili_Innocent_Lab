package com.Bilibili_Innocent_Lab.xposedmodule.lan

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import androidx.core.graphics.createBitmap
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.floor

/** Rasterize a text tile once, then move textured quads. No full-video bitmap or per-frame texture upload. */
internal class CastDanmakuRenderer(comments: List<CastDanmaku>, private val width: Int,
    private val height: Int, style: CastDanmakuStyle, private val metrics: CastPreparationMetrics,
    maxAtlasSide: Int = 2048) : AutoCloseable {
    private data class TextKey(val text: String, val color: Int)
    private data class TileKey(val text: TextKey, val x: Int, val y: Int)
    private data class Bounds(val left: Int, val top: Int, val width: Int, val height: Int)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 25f * height / 720 * style.scale
        typeface = Typeface.DEFAULT_BOLD
        strokeWidth = textSize * .1f
    }
    private val alpha = (style.opacity * 255).toInt()
    private val layout = CastDanmakuLayout(comments, width, height, style, paint::measureText)
    private val bounds = object : LinkedHashMap<TextKey, Bounds>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TextKey, Bounds>?) = size > 512
    }
    private val scratchBounds = Rect()
    private var texture = 0
    private var program: CastGlProgram? = null
    private val identity = FloatArray(16).apply { android.opengl.Matrix.setIdentityM(this, 0) }
    private val vertices = ByteBuffer.allocateDirect(64 * 6 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var vertexCount = 0
    private val atlas: CastAtlasCache<TileKey>
    private val tileSize: Int

    init {
        val limit = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, limit, 0)
        val side = minOf(maxAtlasSide, if (Runtime.getRuntime().maxMemory() < 256L * 1024 * 1024) 1024 else 2048,
            limit[0], if (CastGlProgram.hasHighPrecision()) 2048 else 1024)
        require(side >= 256) { "texture-atlas-unavailable" }
        atlas = CastAtlasCache(side, ::flush)
        tileSize = minOf(1022, side - 2)
        try {
            program = CastGlProgram(false)
            val names = IntArray(1); GLES20.glGenTextures(1, names, 0); texture = names[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, side, side, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "texture-atlas-unavailable" }
            metrics.atlasBytes = side.toLong() * side * 4
        } catch (failure: Throwable) { close(); throw failure }
    }

    fun draw(timeMs: Long) {
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        layout.visit(timeMs) { entry, x, baseline ->
            val key = TextKey(entry.value.text, entry.value.color)
            val box = bounds.getOrPut(key) {
                paint.style = Paint.Style.FILL
                paint.getTextBounds(key.text, 0, key.text.length, scratchBounds)
                val font = paint.fontMetrics
                val padding = ceil(paint.strokeWidth / 2).toInt() + 2
                val left = floor(minOf(0f, scratchBounds.left.toFloat())).toInt() - padding
                val top = floor(minOf(font.top, scratchBounds.top.toFloat())).toInt() - padding
                Bounds(left, top, ceil(maxOf(entry.width, scratchBounds.right.toFloat())).toInt() + padding - left,
                    ceil(maxOf(font.bottom, scratchBounds.bottom.toFloat())).toInt() + padding - top)
            }
            val left = x + box.left; val top = baseline + box.top
            // Tile long text without splitting Unicode strings/shaping, and upload only visible tiles.
            var ty = 0
            while (ty < box.height) {
                val h = minOf(tileSize, box.height - ty)
                if (top + ty < height && top + ty + h > 0) {
                    var tx = 0
                    while (tx < box.width) {
                        val w = minOf(tileSize, box.width - tx)
                        if (left + tx < width && left + tx + w > 0) {
                            val tileX = tx; val tileY = ty
                            val region = atlas.obtain(TileKey(key, tx, ty), w + 2, h + 2) { region ->
                                // One-pixel overlap around each tile preserves bilinear filtering at tile seams.
                                val bitmap = createBitmap(w + 2, h + 2)
                                try {
                                    val canvas = Canvas(bitmap)
                                    val bx = (1 - box.left - tileX).toFloat(); val by = (1 - box.top - tileY).toFloat()
                                    paint.style = Paint.Style.STROKE; paint.color = Color.BLACK; paint.alpha = alpha
                                    canvas.drawText(key.text, bx, by, paint)
                                    paint.style = Paint.Style.FILL; paint.color = key.color; paint.alpha = alpha
                                    canvas.drawText(key.text, bx, by, paint)
                                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
                                    GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, region.x, region.y, bitmap)
                                    metrics.textUploads++
                                    metrics.uploadedBytes += bitmap.width.toLong() * bitmap.height * 4
                                } finally { bitmap.recycle() }
                            }
                            quad(left + tx, top + ty, w, h, region)
                        }
                        tx += w
                    }
                }
                ty += h
            }
        }
        flush()
        metrics.atlasResets = atlas.resets
    }

    private fun quad(x: Float, y: Float, w: Int, h: Int, region: CastAtlasCache.Region) {
        if (vertices.remaining() < 24) flush()
        val left = x * 2 / width - 1; val right = (x + w) * 2 / width - 1
        val top = 1 - y * 2 / height; val bottom = 1 - (y + h) * 2 / height
        val u0 = (region.x + 1f) / atlas.side; val u1 = (region.x + 1f + w) / atlas.side
        val v0 = (region.y + 1f) / atlas.side; val v1 = (region.y + 1f + h) / atlas.side
        fun vertex(px: Float, py: Float, u: Float, v: Float) { vertices.put(px).put(py).put(u).put(v) }
        vertex(left, top, u0, v0); vertex(left, bottom, u0, v1); vertex(right, top, u1, v0)
        vertex(right, top, u1, v0); vertex(left, bottom, u0, v1); vertex(right, bottom, u1, v1)
        vertexCount += 6
    }

    private fun flush() {
        if (vertexCount == 0) return
        val shader = checkNotNull(program)
        shader.bind(texture, identity)
        vertices.position(0)
        GLES20.glEnableVertexAttribArray(shader.position)
        GLES20.glVertexAttribPointer(shader.position, 2, GLES20.GL_FLOAT, false, 16, vertices)
        vertices.position(2)
        GLES20.glEnableVertexAttribArray(shader.coordinate)
        GLES20.glVertexAttribPointer(shader.coordinate, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertexCount)
        metrics.overlayDraws++
        vertices.clear(); vertexCount = 0
    }

    override fun close() {
        if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
        texture = 0; program?.close(); program = null
    }
}

/** All handles are resolved once. Native GLES is required for the MediaCodec Surface pipeline. */
internal class CastGlProgram(external: Boolean) : AutoCloseable {
    private val target = if (external) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D
    private var handle = 0
    val position: Int
    val coordinate: Int
    private val matrix: Int
    private val sampler: Int
    init {
        var vertex = 0; var fragment = 0
        try {
            val precision = if (hasHighPrecision()) "highp" else "mediump"
            vertex = shader(GLES20.GL_VERTEX_SHADER,
                "attribute vec4 aPosition; attribute vec4 aTexCoord; uniform mat4 uMatrix; varying $precision vec2 vTex; void main(){gl_Position=aPosition;vTex=(uMatrix*aTexCoord).xy;}")
            fragment = shader(GLES20.GL_FRAGMENT_SHADER,
                (if (external) "#extension GL_OES_EGL_image_external : require\n" else "") +
                    "precision mediump float; varying $precision vec2 vTex; uniform ${if (external) "samplerExternalOES" else "sampler2D"} sTexture; void main(){gl_FragColor=texture2D(sTexture,vTex);}")
            handle = GLES20.glCreateProgram()
            GLES20.glAttachShader(handle, vertex); GLES20.glAttachShader(handle, fragment); GLES20.glLinkProgram(handle)
            val status = IntArray(1); GLES20.glGetProgramiv(handle, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] != 0) { "shader-link-failed" }
            position = GLES20.glGetAttribLocation(handle, "aPosition")
            coordinate = GLES20.glGetAttribLocation(handle, "aTexCoord")
            matrix = GLES20.glGetUniformLocation(handle, "uMatrix")
            sampler = GLES20.glGetUniformLocation(handle, "sTexture")
        } catch (failure: Throwable) { close(); throw failure }
        finally { if (vertex != 0) GLES20.glDeleteShader(vertex); if (fragment != 0) GLES20.glDeleteShader(fragment) }
    }
    private fun shader(type: Int, text: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, text); GLES20.glCompileShader(shader)
        val status = IntArray(1); GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) { GLES20.glDeleteShader(shader); error("shader-compile-failed") }
        return shader
    }
    fun bind(texture: Int, transform: FloatArray) {
        GLES20.glUseProgram(handle)
        GLES20.glUniformMatrix4fv(matrix, 1, false, transform, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(target, texture)
        GLES20.glUniform1i(sampler, 0)
    }
    override fun close() { if (handle != 0) GLES20.glDeleteProgram(handle); handle = 0 }
    companion object {
        fun hasHighPrecision(): Boolean {
            val range = IntArray(2); val precision = IntArray(1)
            GLES20.glGetShaderPrecisionFormat(GLES20.GL_FRAGMENT_SHADER, GLES20.GL_HIGH_FLOAT, range, 0, precision, 0)
            return precision[0] > 0
        }
    }
}
