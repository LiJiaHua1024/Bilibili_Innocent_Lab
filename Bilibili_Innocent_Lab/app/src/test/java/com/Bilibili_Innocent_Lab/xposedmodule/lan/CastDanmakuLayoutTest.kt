package com.Bilibili_Innocent_Lab.xposedmodule.lan

import org.junit.Assert.*
import org.junit.Test

class CastDanmakuLayoutTest {
    private val style = CastDanmakuStyle()
    private fun comment(at: Long, text: String, mode: Int = 1) = CastDanmaku(at, mode, -1, text)
    private data class Visible(val text: String, val x: Float, val y: Float)
    private fun frame(layout: CastDanmakuLayout, time: Long) = ArrayList<Visible>().also { result ->
        layout.visit(time) { entry, x, y -> result += Visible(entry.value.text, x, y) }
    }

    @Test fun `all modes preserve media-time motion and fixed positioning`() {
        val layout = CastDanmakuLayout(listOf(comment(0, "right"), comment(0, "left", 6),
            comment(0, "top", 5), comment(0, "bottom", 4)), 1280, 720, style) { 200f }
        val frame = frame(layout, 4000).associateBy { it.text }
        assertEquals(640f, frame.getValue("right").x, .001f)
        assertEquals(440f, frame.getValue("left").x, .001f)
        assertEquals(540f, frame.getValue("top").x, .001f)
        assertEquals(33f, frame.getValue("top").y, .001f)
        assertEquals(710f, frame.getValue("bottom").y, .001f)
        assertEquals(setOf("left", "right"), frame(layout, 4001).map { it.text }.toSet())
    }

    @Test fun `same-time entries survive lower bound and rewinding gives identical frames`() {
        val comments = (0..9).map { comment(0, "first-$it") } + (1..100).map { comment(it * 1000L, "later-$it") }
        val layout = CastDanmakuLayout(comments, 1280, 720, style) { 100f }
        val first = frame(layout, 0)
        assertEquals(10, first.size)
        frame(layout, 100000)
        assertEquals(first, frame(layout, 0))
        assertEquals(frame(CastDanmakuLayout(comments, 1280, 720, style) { 100f }, 5000), frame(layout, 5000))
    }

    @Test fun `long comments remain visible after sixty seconds and expired comments disappear`() {
        val layout = CastDanmakuLayout(listOf(comment(0, "long")), 100, 720, style) { 1000f }
        assertEquals(-712.5f, frame(layout, 65000).single().x, .001f)
        assertTrue(frame(layout, 88001).isEmpty())
    }

    @Test fun `lane admission never lets following text catch the preceding text`() {
        val oneLane = CastDanmakuStyle(scale = 2f, coverage = .25f)
        // Two lanes at these dimensions, all occupied by long comments for > 8 seconds.
        val first = (0..1).map { comment(0, "long-$it") }
        val layout = CastDanmakuLayout(first + comment(1, "drop") + comment(20000, "next"),
            100, 100, oneLane) { 100f }
        assertFalse(frame(layout, 1).any { it.text == "drop" })
        assertEquals(listOf("next"), frame(layout, 20000).map { it.text })
    }

    @Test fun `measurement is performed at layout construction and never per frame`() {
        var measured = 0
        val layout = CastDanmakuLayout((0..100).map { comment(it * 1000L, "text-$it") }, 1280, 720, style) {
            measured++; 100f
        }
        val before = measured
        repeat(3000) { frame(layout, it * 1000L / 30) }
        assertEquals(before, measured)
    }

    @Test fun `fixed baselines are pixel aligned while horizontal scrolling remains continuous`() {
        val layout = CastDanmakuLayout(listOf(comment(0, "moving"), comment(0, "bottom", 4)),
            1280, 532, style) { 200f }
        val frame = frame(layout, 2347).associateBy { it.text }
        assertEquals(904.48f, frame.getValue("moving").x, .001f)
        assertEquals(33f, frame.getValue("moving").y, .001f)
        assertEquals(522f, frame.getValue("bottom").y, .001f)
    }
}

class CastDanmakuTypographyTest {
    @Test fun `cropped widescreen frames retain the type size of equally wide 16 to 9 frames`() {
        assertEquals(25f, CastDanmakuTypography.textSize(1280, 532, 1f), .001f)
        assertEquals(25f, CastDanmakuTypography.textSize(1280, 720, 1f), .001f)
        assertEquals(37.5f, CastDanmakuTypography.textSize(1920, 800, 1f), .001f)
        assertEquals(75f, CastDanmakuTypography.textSize(3840, 1600, 1f), .001f)
    }

    @Test fun `four to three and portrait videos use their fitted screen height`() {
        assertEquals(25f, CastDanmakuTypography.textSize(960, 720, 1f), .001f)
        assertEquals(37.5f, CastDanmakuTypography.textSize(1440, 1080, 1f), .001f)
        assertEquals(25f, CastDanmakuTypography.textSize(405, 720, 1f), .001f)
        assertEquals(50f, CastDanmakuTypography.textSize(810, 1440, 1f), .001f)
    }

    @Test fun `user scale is respected without enlarging the video raster`() {
        assertEquals(12.5f, CastDanmakuTypography.textSize(1280, 532, .5f), .001f)
        assertEquals(50f, CastDanmakuTypography.textSize(1280, 532, 2f), .001f)
        assertEquals(8.333333f, CastDanmakuTypography.textSize(426, 240, 1f), .001f)
        assertTrue(runCatching { CastDanmakuTypography.textSize(0, 720, 1f) }.isFailure)
        assertTrue(runCatching { CastDanmakuTypography.textSize(1280, 720, Float.NaN) }.isFailure)
    }
}

class CastAtlasCacheTest {
    @Test fun `cached text is uploaded once across hundreds of frames`() {
        var uploads = 0; var flushes = 0
        val cache = CastAtlasCache<String>(256) { flushes++ }
        repeat(300) { repeat(10) { key -> cache.obtain("text-$key", 60, 20) { uploads++ } } }
        assertEquals(10, uploads)
        assertEquals(0, flushes)
        assertEquals(10, cache.size)
    }

    @Test fun `overflow flushes old draw references before pixels are overwritten`() {
        val events = ArrayList<String>()
        val cache = CastAtlasCache<String>(8) { events += "flush" }
        repeat(4) { cache.obtain("$it", 4, 4) { region -> events += "upload-${region.x}-${region.y}" } }
        cache.obtain("new", 4, 4) { events += "reuse-${it.x}-${it.y}" }
        assertEquals(listOf("upload-0-0", "upload-4-0", "upload-0-4", "upload-4-4", "flush", "reuse-0-0"), events)
        assertEquals(1, cache.resets)
        assertEquals(1, cache.size)
    }

    @Test fun `failed uploads cannot poison cache and invalid tile dimensions cannot allocate`() {
        val cache = CastAtlasCache<String>(8) {}
        assertTrue(runCatching { cache.obtain("failed", 3, 3) { error("upload-failed") } }.isFailure)
        var uploads = 0
        cache.obtain("failed", 3, 3) { uploads++ }
        assertEquals(1, uploads)
        assertTrue(runCatching { cache.obtain("oversized", 9, 1) {} }.isFailure)
        assertTrue(runCatching { cache.obtain("empty", 1, 0) {} }.isFailure)
    }

    @Test fun `variable shelf packing never overlaps live regions or exceeds atlas bounds`() {
        val live = ArrayList<CastAtlasCache.Region>()
        val cache = CastAtlasCache<Int>(128) { live.clear() }
        repeat(10000) { key ->
            cache.obtain(key, 2 + key % 71, 2 + key % 37) { region ->
                assertTrue(region.x >= 0 && region.y >= 0 && region.x + region.width <= 128 && region.y + region.height <= 128)
                assertFalse(live.any { a -> a.x < region.x + region.width && region.x < a.x + a.width &&
                    a.y < region.y + region.height && region.y < a.y + a.height })
                live += region
            }
        }
        assertTrue(cache.resets > 0)
    }

    @Test fun `sixteen-minute synthetic schedule drastically reduces upload work without dropping admitted comments`() {
        val width = 1280; val height = 532; val seconds = 952; val fps = 30
        val comments = (0 until seconds * 10).map { CastDanmaku(it * 100L, 1, -1, "unique-$it") }
        val layout = CastDanmakuLayout(comments, width, height, CastDanmakuStyle()) { 200f }
        val cache = CastAtlasCache<String>(2048) {}
        var uploads = 0L; var visibleDraws = 0L
        repeat(seconds * fps) { frame ->
            layout.visit(frame * 1000L / fps) { entry, x, _ ->
                if (x < width && x + entry.width > 0) {
                    visibleDraws++
                    cache.obtain(entry.value.text, 210, 36) { uploads++ }
                }
            }
        }
        val oldBytes = width.toLong() * height * 4 * seconds * fps
        val newBytes = uploads * 210 * 36 * 4
        assertTrue(visibleDraws > 100000)
        assertTrue("work reduction, not a device-time benchmark", oldBytes > newBytes * 100)
        println("cast-upload-model frames=${seconds * fps} draws=$visibleDraws uploads=$uploads oldBytes=$oldBytes newBytes=$newBytes resets=${cache.resets}")
    }
}
