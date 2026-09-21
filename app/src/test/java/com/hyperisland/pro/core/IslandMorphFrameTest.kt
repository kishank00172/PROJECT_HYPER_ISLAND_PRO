package com.hyperisland.pro.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The anchors of the drawn morph box. These four numbers are the difference between "the card grows" and
 * "the card grows and everything inside it visibly crawls", so they are pinned here rather than eyeballed on
 * a device: the island is horizontally centered but hangs from its own top edge, and the content has to be
 * pulled up by half the height difference to stay centered in what you actually see.
 */
class IslandMorphFrameTest {

    @Test
    fun atFullSizeTheBoxIsTheWholeViewAndNothingShifts() {
        val f = IslandMorphFrame.compute(finalW = 360, finalH = 110, w = 360, h = 110)
        assertEquals(MorphFrame(0, 0, 360, 110, 0), f)
    }

    @Test
    fun theBoxStaysHorizontallyCentered() {
        val f = IslandMorphFrame.compute(360, 110, 200, 40)
        assertEquals(80, f.left)
        assertEquals(280, f.right)
        assertEquals(180, (f.left + f.right) / 2)
    }

    /** The island's Y is a setting, not a center: growth is downward, so the box top never moves. */
    @Test
    fun theBoxHangsFromTheTopEdge() {
        assertEquals(0, IslandMorphFrame.compute(360, 110, 200, 40).top)
        assertEquals(40, IslandMorphFrame.compute(360, 110, 200, 40).bottom)
        assertEquals(0, IslandMorphFrame.compute(360, 110, 360, 110).top)
    }

    @Test
    fun contentShiftsUpToStayCenteredInsideTheDrawnBox() {
        // 110 tall view drawing a 40 tall box: the content must sit 35 px above where a full-height
        // centering would put it, which is exactly half the difference.
        assertEquals(-35, IslandMorphFrame.compute(360, 110, 200, 40).contentOffsetY)
    }

    /** A mid-morph value can never exceed the final bounds (a slider change or a stale animator can do it). */
    @Test
    fun aSizeBiggerThanTheFinalViewIsClamped() {
        val f = IslandMorphFrame.compute(360, 110, 500, 400)
        assertEquals(0, f.left)
        assertEquals(360, f.right)
        assertEquals(110, f.bottom)
        assertEquals(0, f.contentOffsetY)
    }

    /** Odd widths must not round the box narrower: 1 px of jitter per frame is the bug this whole file is. */
    @Test
    fun oddNumbersKeepTheExactWidth() {
        val f = IslandMorphFrame.compute(361, 111, 101, 41)
        assertEquals(101, f.width)
        assertEquals(130, f.left)
        assertEquals(41, f.height)
    }

    @Test
    fun theDirtyAreaIsTheUnionOfBothBoxes() {
        val from = IslandMorphFrame.compute(360, 110, 200, 40)
        val to = IslandMorphFrame.compute(360, 110, 360, 110)
        assertArrayEquals(intArrayOf(0, 0, 360, 110), IslandMorphFrame.dirtyBounds(from, to))
        assertArrayEquals(intArrayOf(0, 0, 360, 110), IslandMorphFrame.dirtyBounds(null, to))
        assertArrayEquals(intArrayOf(80, 0, 280, 40), IslandMorphFrame.dirtyBounds(from, null))
    }
}
