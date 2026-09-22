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
