package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import org.junit.Assert.*
import org.junit.Test

class HostTopIslandCompactGestureTest {
    @Test fun tapJitterRemainsAClick() {
        val gesture = HostTopIslandCompactGesture(8f, 12f)
        gesture.move(3f, 2f)
        assertTrue(gesture.canClick())
    }

    @Test fun bothHorizontalDirectionsExpandWithoutClicking() {
        for (dx in listOf(-14f, 14f)) {
            val gesture = HostTopIslandCompactGesture(8f, 12f)
            assertEquals(HostTopIslandCompactGesture.Decision.EXPAND, gesture.move(dx, 4f))
            assertFalse(gesture.canClick())
        }
    }

    @Test fun draggingBackToTheStartDoesNotRefresh() {
        val gesture = HostTopIslandCompactGesture(8f, 12f)
        gesture.move(10f, 0f)
        gesture.move(0f, 0f)
        assertFalse(gesture.canClick())
    }

    @Test fun verticalDragAndMultitouchCannotExpandOrClick() {
        for (vertical in listOf(true, false)) {
            val gesture = HostTopIslandCompactGesture(8f, 12f)
            if (vertical) gesture.move(2f, 20f) else gesture.cancel()
            assertEquals(HostTopIslandCompactGesture.Decision.CANCEL, gesture.move(40f, 0f))
            assertFalse(gesture.canClick())
        }
    }
}
