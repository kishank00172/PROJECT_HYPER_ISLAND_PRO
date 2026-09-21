package com.hyperisland.pro.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The anchors of the drawn morph box. These few numbers are the difference between "the card grows" and "the
 * card grows and everything inside it visibly crawls on some frames", so they are pinned here rather than
 * eyeballed on a device: the island is horizontally centered but hangs from its own top edge, and the content
 * is kept centered in what you *see* by translating the view and compensating the box by the same amount.
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

    /**
     * The invariant this round exists for: the view is translated up by [MorphFrame.viewTranslationY], so the
     * box has to be pushed down by exactly the same amount or the island visibly jumps sideways/vertically by
     * half the height difference on every frame.
     */
    @Test
    fun theBoxStaysPinnedToTheWindowAnchorDespiteTheViewShift() {
        for (h in listOf(40, 41, 55, 109, 110)) {
            val f = IslandMorphFrame.compute(360, 110, 200, h)
            assertEquals("box top on screen", 0, f.top + f.viewTranslationY)
            assertEquals("box bottom on screen", h, f.bottom + f.viewTranslationY)
        }
    }

    /** The content, centered in the final height, lands centered in the drawn box. */
    @Test
    fun contentEndsUpCenteredInsideTheDrawnBox() {
        val f = IslandMorphFrame.compute(360, 110, 200, 40)
        val contentCentreOnScreen = 110 / 2 + f.viewTranslationY
        assertEquals(20, contentCentreOnScreen)
        assertEquals((0 + 40) / 2, contentCentreOnScreen)
    }

    /** A mid-morph value can never exceed the final bounds (a slider change or a stale animator can do it). */
    @Test
    fun aSizeBiggerThanTheFinalViewIsClamped() {
        val f = IslandMorphFrame.compute(360, 110, 500, 400)
        assertEquals(MorphFrame(0, 0, 360, 110, 0), f)
    }

    /** Odd sizes must not round the box narrower: 1 px per frame is the bug this whole file exists for. */
    @Test
    fun oddNumbersKeepTheExactSize() {
        val f = IslandMorphFrame.compute(361, 111, 101, 41)
        assertEquals(101, f.width)
        assertEquals(41, f.height)
        assertEquals(130, f.left)
    }

    @Test
    fun theDirtyAreaIsTheUnionOfBothBoxes() {
        val from = IslandMorphFrame.compute(360, 110, 200, 40)
        val to = IslandMorphFrame.compute(360, 110, 360, 110)
        assertArrayEquals(intArrayOf(0, 0, 360, 110), IslandMorphFrame.dirtyBounds(from, to))
        assertArrayEquals(intArrayOf(0, 0, 360, 110), IslandMorphFrame.dirtyBounds(null, to))
        assertArrayEquals(intArrayOf(80, 35, 280, 75), IslandMorphFrame.dirtyBounds(from, null))
    }
}
