package com.hyperisland.pro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The carry is what turns "the island uncovers content that was already sitting there" into "the content came
 * with the island". It is four numbers, so all four are pinned here: the tester's complaint is visible only on
 * a device, but the arithmetic that makes it right or wrong is not.
 */
class MorphCarryTest {

    @Test
    fun theRowIsPinnedToTheBoxAndVanishesWhenTheBoxIsFullWidth() {
        assertEquals(350f, MorphCarry.translationX(350), 0f)
        assertEquals(0f, MorphCarry.translationX(0), 0f)
    }

    /** A monotone, per-frame sequence: no plateau, no jump, and it lands exactly on the final layout. */
    @Test
    fun theCarryMovesOnEveryFrameOfAnOpeningBox() {
        val lefts = listOf(350, 300, 250, 200, 150, 100, 50, 0)
        val xs = lefts.map { MorphCarry.translationX(it) }
        assertEquals(lefts.size, xs.distinct().size)
        for (i in 1 until xs.size) assertTrue(xs[i] < xs[i - 1])
        assertEquals(0f, xs.last(), 0f)
    }

    @Test
    fun theIconGrowsFromThePillSizeAndShrinksBackToIt() {
        val pillOverCard = 32f / 38f
        assertEquals(pillOverCard, MorphCarry.iconScale(0f, pillOverCard), 0f)
        assertEquals(1f, MorphCarry.iconScale(1f, pillOverCard), 0f)
        assertEquals(0.92f, MorphCarry.iconScale(0.5f, pillOverCard), 0.01f)
        // A collapse is the same function read backwards, so it must end at the pill size, not start at it.
        assertEquals(1f, MorphCarry.iconScale(1f - 0f, pillOverCard), 0f)
        assertEquals(pillOverCard, MorphCarry.iconScale(1f - 1f, pillOverCard), 0f)
    }

    /**
     * Which badge the travelling element wears, and when. The direction matters: expanding starts on the pill's
     * glyph and ends on the launcher icon, collapsing must do the reverse - a card that briefly became a
     * monochrome notification glyph mid-shrink would look like a flicker.
     */
    @Test
    fun theGlyphSwapsOnceAndInDirectionOrder() {
        assertTrue(MorphCarry.showsPillGlyph(0f, growing = true))
        assertFalse(MorphCarry.showsPillGlyph(0.6f, growing = true))
        assertFalse(MorphCarry.showsPillGlyph(0f, growing = false))
        assertTrue(MorphCarry.showsPillGlyph(0.6f, growing = false))
        // exactly one swap in the sequence, in both directions
        val ts = (0..10).map { it / 10f }
        assertEquals(1, ts.zipWithNext().count { (a, b) ->
            MorphCarry.showsPillGlyph(a, true) != MorphCarry.showsPillGlyph(b, true)
        })
        assertEquals(1, ts.zipWithNext().count { (a, b) ->
            MorphCarry.showsPillGlyph(a, false) != MorphCarry.showsPillGlyph(b, false)
        })
    }
}

    // The content morphs too, not only the shape: "Island ke andar jo content hota hai use bhi morph karo
    // scale and opacity morph". These are the functions the service reads per frame, so the whole policy
    // is testable here instead of inside a listener callback CI cannot reach.
    @Test fun `shape progress reads the box, not the clock`() {
        // bound 1080: a 480 pill sits at left 300, the 1080 card at 0, and halfway is 780 wide.
        assertEquals(0f, MorphCarry.shapeProgress(boxLeft = 300, fromW = 480, toW = 1080, boundW = 1080), 1e-6f)
        assertEquals(1f, MorphCarry.shapeProgress(boxLeft = 0, fromW = 480, toW = 1080, boundW = 1080), 1e-6f)
        assertEquals(0.5f, MorphCarry.shapeProgress(boxLeft = 150, fromW = 480, toW = 1080, boundW = 1080), 1e-6f)
        // A collapse uses the same numbers backwards and still runs 0 to 1.
        assertEquals(0f, MorphCarry.shapeProgress(boxLeft = 0, fromW = 1080, toW = 480, boundW = 1080), 1e-6f)
        assertEquals(1f, MorphCarry.shapeProgress(boxLeft = 300, fromW = 1080, toW = 480, boundW = 1080), 1e-6f)
        // A box clamped wider than its target must not read as past the end.
        assertEquals(1f, MorphCarry.shapeProgress(boxLeft = -20, fromW = 480, toW = 1080, boundW = 1080), 1e-6f)
    }

    @Test fun `content grows into the card and shrinks back into the pill`() {
        assertEquals(0.88f, MorphCarry.contentScale(progress = 0f, from = 0.88f), 1e-6f)
        assertEquals(0.94f, MorphCarry.contentScale(progress = 0.5f, from = 0.88f), 1e-6f)
        assertEquals(1f, MorphCarry.contentScale(progress = 1f, from = 0.88f), 1e-6f)
        // from = 1 is the switch being off: the size must not move at all.
        assertEquals(1f, MorphCarry.contentScale(progress = 0f, from = 1f), 1e-6f)
    }

    @Test fun `opacity has one ramp per direction and never overshoots`() {
        // Expanding with the shape: invisible at the pill, solid at the card.
        assertEquals(0f, MorphCarry.contentAlpha(t = 0f, progress = 0f, growing = true, followShape = true), 1e-6f)
        assertEquals(1f, MorphCarry.contentAlpha(t = 1f, progress = 1f, growing = true, followShape = true), 1e-6f)
        // Expanding on the clock is the b1378 style: the ramp ignores the box entirely.
        assertEquals(0.3f, MorphCarry.contentAlpha(t = 0.3f, progress = 0.9f, growing = true, followShape = false), 1e-6f)
        // Collapsing, gone by 40% of the shrink - and exactly zero there, not merely small, because a
        // half-faded row hanging under the pill is a bug this file has already been written about.
        assertEquals(0.5f, MorphCarry.contentAlpha(t = 0.2f, progress = 1f, growing = false, followShape = false, outBy = 0.4f), 1e-6f)
        assertEquals(0f, MorphCarry.contentAlpha(t = 0.4f, progress = 1f, growing = false, followShape = false, outBy = 0.4f), 1e-6f)
        assertEquals(0f, MorphCarry.contentAlpha(t = 0.9f, progress = 1f, growing = false, followShape = false, outBy = 0.4f), 1e-6f)
        // Collapsing with the shape: progress runs 1 -> 0, so 1 - progress is the fade clock.
        assertEquals(1f, MorphCarry.contentAlpha(t = 0f, progress = 1f, growing = false, followShape = true, outBy = 0.4f), 1e-6f)
        assertEquals(0f, MorphCarry.contentAlpha(t = 0f, progress = 0.5f, growing = false, followShape = true, outBy = 0.4f), 1e-6f)
        for (i in 0..100) {
            val p = i / 100f
            for (growing in booleanArrayOf(true, false)) {
                for (follow in booleanArrayOf(true, false)) {
                    val a = MorphCarry.contentAlpha(p, p, growing, follow)
                    assertTrue("alpha out of range: $a", a in 0f..1f)
                }
            }
        }
    }

    @Test fun `swap point is honoured in both directions`() {
        // He can move the swap in TestLab; the rule stays one glyph on the way in and its mirror on the way
        // out, which is the direction bug this file exists to remember.
        assertTrue(MorphCarry.showsPillGlyph(t = 0.7f, growing = true, swapAt = 0.8f))
        assertFalse(MorphCarry.showsPillGlyph(t = 0.9f, growing = true, swapAt = 0.8f))
        assertTrue(MorphCarry.showsPillGlyph(t = 0.2f, growing = false, swapAt = 0.8f))
        assertFalse(MorphCarry.showsPillGlyph(t = 0.05f, growing = false, swapAt = 0.8f))
    }
