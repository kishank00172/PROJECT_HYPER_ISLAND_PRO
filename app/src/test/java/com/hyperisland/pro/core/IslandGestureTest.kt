package com.hyperisland.pro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Touch decisions for the island. These exist because of one report: "swipe karo to kabhi kabhi
 * unintentionally tap register kar leta hai" — a swipe that came back near its start was read as a tap,
 * because the old code looked only at the DOWN-to-UP distance at ACTION_UP.
 *
 * px values below are the kind a 1080p phone produces (slop 16px ~ 8dp, commit 60px ~ 26dp).
 */
class IslandGestureTest {

    private fun machine(
        atFirst: Boolean = false,
        atLast: Boolean = false,
        pages: Boolean = true,
        allowTap: Boolean = true
    ): IslandGesture {
        val g = IslandGesture(touchSlopPx = 16f, minFlingPxPerS = 800f, pageCommitPx = 60f, maxDragPx = 100f)
        g.begin(0f, 0f, 0L, allowTap = allowTap, pagesEnabled = pages, atFirstPage = atFirst, atLastPage = atLast)
        return g
    }

    /** The reported bug, as a test. */
    @Test
    fun dragOutAndBackIsNotATap() {
        val g = machine()
        g.move(-90f, 0f, 20L)
        assertTrue("the card must have been under the finger", g.dragging)
        assertEquals(-90f, g.offsetX, 0.01f)
        g.move(-4f, 0f, 40L)
        assertEquals(IslandGesture.Action.NONE, g.end(40L))
    }

    @Test
    fun aStillFingerWithinSlopIsStillATap() {
        val g = machine()
        g.move(2f, -3f, 10L)
        assertFalse(g.dragging)
        assertEquals(IslandGesture.Action.TAP, g.end(1_000L))
    }

    @Test
    fun pastTheSlopATapIsNoLongerReachableHoweverSlow() {
        val g = machine()
        g.move(20f, 0f, 100L)
        g.move(22f, 1f, 4_000L) // user rested on the card, then lifted
        assertEquals(IslandGesture.Action.NONE, g.end(4_000L))
    }

    @Test
    fun shortFastFlickChangesThePage() {
        val g = machine()
        g.move(-25f, 0f, 15L) // 1666 px/s, but only 25px of travel: distance alone would not commit
        assertEquals(IslandGesture.Action.PAGE_OLDER, g.end(20L))
    }

    @Test
    fun aFlickThatRestedIsTreatedAsADrag() {
        val g = machine()
        g.move(-25f, 0f, 15L)
        assertEquals(IslandGesture.Action.NONE, g.end(200L))
    }

    /** Left is "older" - the direction the ring already used before this rewrite. */
    @Test
    fun longDragCommitsEvenWithoutSpeed() {
        val g = machine()
        g.move(-40f, 0f, 300L)
        g.move(-75f, 2f, 900L)
        assertEquals(IslandGesture.Action.PAGE_OLDER, g.end(905L))
        val right = machine()
        right.move(40f, 0f, 300L)
        right.move(75f, 2f, 900L)
        assertEquals(IslandGesture.Action.PAGE_NEWER, right.end(905L))
    }

    @Test
    fun draggingPastTheEndOfTheRingIsResistedNotCommitted() {
        val g = machine(atLast = true)
        g.move(-200f, 0f, 40L)
        assertEquals("edge damping, not a full move", -80f, g.offsetX, 0.01f)
        assertEquals(IslandGesture.Action.NONE, g.end(45L))
    }

    @Test
    fun theCardNeverLeavesTheIsland() {
        val g = machine()
        g.move(-5_000f, 0f, 40L)
        assertEquals(-100f, g.offsetX, 0.01f)
    }

    @Test
    fun horizontalDragOnThePillIsAcknowledgeButNotAPageChange() {
        val g = machine(pages = false)
        g.move(-300f, 0f, 40L)
        assertEquals(-60f, g.offsetX, 0.01f)
        assertEquals(IslandGesture.Action.NONE, g.end(45L))
    }

    @Test
    fun axisIsLockedInAtTheMomentItCrossesTheSlop() {
        val g = machine()
        g.move(0f, -40f, 10L)   // clearly vertical first
        g.move(200f, -45f, 20L) // then a lot of horizontal travel
        assertEquals("the horizontal leg must not steal the gesture", 0f, g.offsetX, 0.01f)
        assertEquals(IslandGesture.Action.SWIPE_UP, g.end(25L))
    }

    @Test
    fun swipeUpNeedsDistanceOrSpeed() {
        val near = machine()
        near.move(0f, -30f, 40L)
        assertEquals(IslandGesture.Action.NONE, near.end(45L))
        val far = machine()
        far.move(0f, -120f, 40L)
        assertEquals(IslandGesture.Action.SWIPE_UP, far.end(45L))
    }

    @Test
    fun downwardDragIsDampedBecauseNothingLivesThere() {
        val g = machine()
        g.move(0f, 200f, 40L)
        assertEquals(50f, g.offsetY, 0.01f)
        assertEquals(IslandGesture.Action.NONE, g.end(45L))
    }

    @Test
    fun cancelAbandonsWithoutAnAction() {
        val g = machine()
        g.move(-90f, 0f, 20L)
        g.cancel()
        assertEquals(0f, g.offsetX, 0.01f)
        assertEquals(IslandGesture.Action.NONE, g.end(40L))
    }

    /**
     * A press that began on a button belongs to the button - but the drag still belongs to the island.
     * b1333 shipped the opposite rule (any child-consumed DOWN threw the whole gesture away), and because
     * the Like/Reply tiles are clickable and span the bottom of a short card, a swipe started there did
     * nothing at all: "left right kuchh work nahi kiya".
     */
    @Test
    fun aPressAChildTookCannotTapButStillPages() {
        val tapped = machine(allowTap = false)
        assertEquals(IslandGesture.Action.NONE, tapped.end(30L))
        val dragged = machine(allowTap = false)
        dragged.move(-90f, 0f, 20L)
        assertEquals(IslandGesture.Action.PAGE_OLDER, dragged.end(30L))
        val lifted = machine(allowTap = false)
        lifted.move(0f, -120f, 20L)
        assertEquals(IslandGesture.Action.SWIPE_UP, lifted.end(30L))
    }
}
