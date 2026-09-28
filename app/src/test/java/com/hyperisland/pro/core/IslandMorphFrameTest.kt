package com.hyperisland.pro.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    @Test
    fun verticalHeadroomIsRoomToDrawInAndNotALayout() {
        // The clamp that flattened the horizontal bounce also sits on the vertical axis, so the headroom has to
        // let the box out *without* being felt by the content. The b1422 failure was the second kind: centring
        // the content against the card while the view was card + headroom tall left a sign-flipping offset at
        // the curve's frequency - "spring content pe jerky feel deta hai". The property that fixes it: at the
        // spring's peak the box and the surface are one size, the offset is zero, and the overshoot is nothing
        // but empty surface below the card.
        // Below the natural height the offset tracks the box one to one - the reveal - and once the box passes
        // the natural height the offset is pinned at the rest value: the spring's whole elastic life is drawn
        // as surface below a content that does not move. -19 is the layout's own centring leftover with the
        // sign flipped, so at rest and at the peak the content is drawn at exactly the same pixel.
        val below = IslandMorphFrame.compute(boundW = 1080, boundH = 421, w = 1067, h = 418, headH = 39)
        assertEquals(418, below.height)
        assertEquals(-21, below.contentOffsetY)  // (418 - 421 - 39) / 2: still tracking the box
        val peak = IslandMorphFrame.compute(boundW = 1080, boundH = 421, w = 1067, h = 460, headH = 39)
        assertEquals(460, peak.height)
        assertEquals("past the natural height the offset does not move again", -19, peak.contentOffsetY)
        val mid = IslandMorphFrame.compute(boundW = 1080, boundH = 421, w = 1067, h = 447, headH = 39)
        assertEquals(447, mid.height)
        assertEquals(-19, mid.contentOffsetY)
        val atRest = IslandMorphFrame.compute(boundW = 1080, boundH = 421, w = 1067, h = 421, headH = 39)
        assertEquals(-19, atRest.contentOffsetY)
        assertEquals(421, atRest.height)
        // Without the headroom the box is eaten, which is the regression this exists to prevent.
        val eaten = IslandMorphFrame.compute(1080, 421, 1067, 447)
        assertEquals(421, eaten.height)
        // A collapse still keeps its own centring: the box shrinks inside the card-sized surface and the content
        // rides up to stay in it.
        val pill = IslandMorphFrame.compute(boundW = 1067, boundH = 421, w = 366, h = 104, headH = 0)
        assertEquals(-158, pill.contentOffsetY)
        assertEquals((1067 - 366) / 2, pill.left)
        // And `naturalBoundH` is how a caller recovers that number from a view it had to grow.
        assertEquals(421, IslandMorphFrame.naturalBoundH(460, 39))
    }

    @Test
    fun theHeadroomIsInvisibleToTheContentForEveryBoxHeight() {
        // The claim behind the fix, as arithmetic the CI can keep - stated in the two halves it is true in.
        // Below the natural height, where the headroom-free world could express the same motion: a child laid
        // out in the widened surface and shifted by the frame's offset is drawn at the very same pixel as in
        // the unpadded view (the spring's padding is invisible). At and past the natural height, where the
        // unpadded world would clamp and there IS no comparable frame: the offset does not move at all - the
        // curve's overshoot is empty surface below content standing at its natural rest.
        for (contentH in listOf(104, 200, 421)) {
            for (boxH in listOf(104, 262, 421)) {
                val withHead = IslandMorphFrame.compute(1080, 421, 1067, boxH, headH = 39)
                val without = IslandMorphFrame.compute(1080, 421, 1067, boxH, headH = 0)
                val drawnWith = (421 + 39 - contentH) / 2 + withHead.contentOffsetY
                val drawnWithout = (421 - contentH) / 2 + without.contentOffsetY
                assertTrue(
                    "box=$boxH content=$contentH: headroom moves content ($drawnWith vs $drawnWithout)",
                    kotlin.math.abs(drawnWith - drawnWithout) <= 1,
                )
            }
            for (boxH in listOf(421, 447, 460)) {
                val withHead = IslandMorphFrame.compute(1080, 421, 1067, boxH, headH = 39)
                assertEquals(
                    "box=$boxH content=$contentH: the elastic excursion must not move the content",
                    -19, withHead.contentOffsetY,
                )
            }
        }
    }


    @Test
    fun neckProfile_hisFormula_peaksAt61PercentAndZerosAtBothEnds() {
        val n = 200
        var peakAt = -1; var peakV = -1f
        for (i in 0..n) {
            val v = IslandMorphFrame.neckInset(i.toFloat() / n, 100f)
            if (v > peakV) { peakV = v; peakAt = i }
        }
        assertEquals(0f, IslandMorphFrame.neckInset(0f, 100f), 0.6f)         // the icon edge never insets
        assertEquals(0f, IslandMorphFrame.neckInset(1f, 100f), 0.6f)         // eases back out at the bottom
        assertEquals(100f, peakV, 0.6f)                                      // maxInset respected exactly
        assertTrue("peak around 55-65% down, not linear: got ${peakAt * 100 / n}%", peakAt * 100 / n in 58..64)
        // and decidedly NOT a linear taper: halfway-down must read well under the peak
        assertTrue(IslandMorphFrame.neckInset(0.5f, 100f) < 97f)
    }


    @Test
    fun topAnchored_contentNeverOffsets_evenWithSpringHeadroom() {
        // Round A2 issue-1: content position = final layout position, always. The old centring formula
        // (asserted below with its own number) is what put the screenshots' content ~100px up.
        for (h in intArrayOf(104, 200, 421, 448)) {
            val f = IslandMorphFrame.compute(1067, 421, 1067, h, headH = 27, topAnchored = true)
            assertEquals(0, f.contentOffsetY)
        }
        val legacy = IslandMorphFrame.compute(1067, 421, 1067, 104, headH = 27)
        assertTrue(legacy.contentOffsetY < -100)
    }

}