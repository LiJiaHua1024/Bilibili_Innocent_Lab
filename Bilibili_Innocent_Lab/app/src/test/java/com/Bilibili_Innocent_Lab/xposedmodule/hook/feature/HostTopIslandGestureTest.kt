package com.Bilibili_Innocent_Lab.xposedmodule.hook.feature

import com.Bilibili_Innocent_Lab.xposedmodule.hook.feature.HostTopIslandGesture.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class HostTopIslandGestureTest {
    private fun gesture(x: Float, left: Boolean = false, right: Boolean = false, action: Boolean = false) =
        HostTopIslandGesture(x, 400f, 96f, 8f, 24f, action, left, right)

    @Test fun centerAlwaysBelongsToTabsEvenAtTheBoundary() {
        assertEquals(Decision.NATIVE, gesture(200f).move(120f, 0f))
        assertEquals(Decision.NATIVE, gesture(200f).move(-120f, 0f))
    }

    @Test fun inwardSwipesCollapseAtBothBoundaries() {
        assertEquals(Decision.COLLAPSE, gesture(20f, right = true).move(30f, 1f))
        assertEquals(Decision.COLLAPSE, gesture(350f, left = true).move(-30f, 1f))
    }

    @Test fun inwardSwipesScrollTabsWhenThereIsRoomInThatDirection() {
        assertEquals(Decision.NATIVE, gesture(20f, left = true).move(30f, 0f))
        assertEquals(Decision.NATIVE, gesture(350f, right = true).move(-30f, 0f))
    }

    @Test fun actionButtonCollapsesEvenWhenTabsCanScroll() {
        assertEquals(Decision.COLLAPSE, gesture(380f, right = true, action = true).move(-30f, 0f))
    }

    @Test fun scrollGestureCannotBecomeCollapseWhenItReachesTheEndOrReverses() {
        val gesture = gesture(350f, right = true)
        assertEquals(Decision.NATIVE, gesture.move(-12f, 0f))
        assertEquals(Decision.NATIVE, gesture.move(-180f, 0f))
        assertEquals(Decision.NATIVE, gesture.move(40f, 0f))
    }

    @Test fun outwardAndVerticalSwipesStayNative() {
        assertEquals(Decision.NATIVE, gesture(20f).move(-30f, 0f))
        assertEquals(Decision.NATIVE, gesture(350f).move(30f, 0f))
        val vertical = gesture(20f)
        assertEquals(Decision.NATIVE, vertical.move(4f, 20f))
        assertEquals(Decision.NATIVE, vertical.move(60f, 20f))
    }

    @Test fun tapsAndSmallJitterDoNotCollapse() {
        val gesture = gesture(380f, action = true)
        assertEquals(Decision.PENDING, gesture.move(-3f, 2f))
        assertEquals(Decision.PENDING, gesture.move(-15f, 2f))
        assertEquals(Decision.COLLAPSE, gesture.move(-30f, 2f))
    }

    @Test fun multiTouchDisablesCollapseForTheRemainderOfTheGesture() {
        val gesture = gesture(20f)
        gesture.keepNative()
        assertEquals(Decision.NATIVE, gesture.move(100f, 0f))
    }
}
