package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class FrameSceneChangeTest {
    private val format = FrameBufferLedger.Format(128, 72, 128 * 3 + 7)
    private fun frame(pixel: (Int, Int, Int) -> Int) = ByteBuffer.allocate(format.bytes).also { b ->
        for (y in 0 until format.height) for (x in 0 until format.width) for (c in 0..2)
            b.put(y * format.stride + x * 3 + c, pixel(x, y, c).toByte())
    }

    @Test fun `hard cuts and full flashes hold the source instead of morphing`() {
        assertTrue(FrameSceneChange.isCut(frame { _, _, _ -> 10 }, frame { _, _, _ -> 240 }, format))
        assertTrue(FrameSceneChange.isCut(frame { _, _, c -> if (c == 0) 255 else 0 },
            frame { _, _, c -> if (c == 2) 255 else 0 }, format))
    }

    @Test fun `large pan preserving color distribution is not a cut`() {
        val a = frame { x, y, _ -> if ((x / 8 + y / 8) % 2 == 0) 10 else 240 }
        val b = frame { x, y, _ -> if (((x + 8) / 8 + y / 8) % 2 == 0) 10 else 240 }
        assertFalse(FrameSceneChange.isCut(a, b, format))
    }

    @Test fun `subtitle replacement and gradual fades do not disable the whole frame`() {
        val a = frame { x, y, _ -> (x + y) % 128 }
        val subtitle = frame { x, y, _ -> if (y in 60..65 && x in 10..100) 255 else (x + y) % 128 }
        assertFalse(FrameSceneChange.isCut(a, subtitle, format))
        assertFalse(FrameSceneChange.isCut(a, frame { x, y, _ -> (x + y) % 128 + 12 }, format))
        assertFalse(FrameSceneChange.isCut(a, a, format))
        assertEquals(0, a.position())
    }

    @Test fun `tiny dimensions and row padding remain valid`() {
        assertTrue(FrameSceneChange.isCut(ByteBuffer.wrap(byteArrayOf(0, 0, 0)),
            ByteBuffer.wrap(byteArrayOf(-1, -1, -1)), FrameBufferLedger.Format(1, 1, 3)))
    }
}
