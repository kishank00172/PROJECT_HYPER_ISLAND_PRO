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
        val f = IslandMorphFrame.compute(boundW = 360, boundH = 110, w = 360, h = 110)
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

    /** A mid-morph value can never exceed the surface it is drawn on (a slider change or a stale animator can do it). */
    @Test
    fun aSizeBiggerThanTheBoundIsClamped() {
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

    /**
     * The bug his words named, in one assertion. A collapse goes card -> pill, so every intermediate is
     * LARGER than the target; clamping against the target instead of the view collapses all of them onto the
     * pill and the shape never animates - thirty-nine delivered frames at `hz=120` drawing the same box.
     * The bound therefore has to be the size the view was actually laid out at, which the service pins to
     * max(start, target).
     */
    @Test
    fun aShrinkOnlyMovesIfTheBoundIsTheViewAndNotTheTarget() {
        val card = 1067; val cardH = 421; val pill = 366; val pillH = 104
        // The wrong bound, kept here so the trap stays documented and the fix stays obvious.
        val wrong = (0..3).map { IslandMorphFrame.compute(pill, pillH, card - it * 200, cardH - it * 70) }
        assertEquals("every frame clamped onto the pill", listOf(pill), wrong.map { it.width }.distinct())
        // The pinned view is card-sized for a shrink, so the frames are the frames the curve produced.
        val right = (0..3).map { IslandMorphFrame.compute(card, cardH, card - it * 200, cardH - it * 70) }
        assertEquals(listOf(1067, 867, 667, 467), right.map { it.width })
        assertEquals(listOf(421, 351, 281, 211), right.map { it.height })
        // ...and the content rides along, centered in what is actually visible.
        assertEquals(listOf(0, -35, -70, -105), right.map { it.contentOffsetY })
    }

    @Test
    fun aGrowthIsUnaffectedByTheBoundChoice() {
        // For an expand, max(start, target) is the target, so the fix cannot change that direction's numbers.
        assertEquals(366, IslandMorphFrame.compute(366, 104, 366, 104).width)
        assertEquals(716, IslandMorphFrame.compute(1067, 421, 716, 264).width)
        assertEquals(264, IslandMorphFrame.compute(1067, 421, 716, 264).height)
    }
}
