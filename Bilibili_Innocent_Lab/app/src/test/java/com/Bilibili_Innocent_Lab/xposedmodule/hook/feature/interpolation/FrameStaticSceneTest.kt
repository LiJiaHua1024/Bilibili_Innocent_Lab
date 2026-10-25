package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.interpolation

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class FrameStaticSceneTest {
    private val format = FrameBufferLedger.Format(64, 32, 64 * 3)
    private fun frame(fill: (x: Int, y: Int) -> Int) = ByteBuffer.allocate(format.bytes).also { buffer ->
        for (y in 0 until format.height) for (x in 0 until format.width) for (c in 0 until 3)
            buffer.put(y * format.stride + x * 3 + c, fill(x, y).toByte())
    }

    @Test fun `identical and sensor noise level frames are static`() {
        val base = frame { x, y -> (x * 3 + y) % 200 }
        assertTrue(FrameStaticScene.isStatic(base, frame { x, y -> (x * 3 + y) % 200 }, format))
        assertTrue(FrameStaticScene.isStatic(base, frame { x, y -> (x * 3 + y) % 200 + if ((x + y) % 9 == 0) 1 else 0 }, format))
    }

    @Test fun `global brightness change or a moving block is not static`() {
        val base = frame { x, y -> (x * 3 + y) % 200 }
        assertFalse(FrameStaticScene.isStatic(base, frame { x, y -> (x * 3 + y) % 200 + 2 }, format))
        assertFalse(FrameStaticScene.isStatic(base, frame { x, y -> if (x in 8..23 && y in 8..23) 255 else (x * 3 + y) % 200 }, format))
    }
}
