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
        assertEquals(0.88f, MorphCarry.contentScale(progress = 0f, from = 0.88f, towardCard = true), 1e-6f)
        assertEquals(0.94f, MorphCarry.contentScale(progress = 0.5f, from = 0.88f, towardCard = true), 1e-6f)
        assertEquals(1f, MorphCarry.contentScale(progress = 1f, from = 0.88f, towardCard = true), 1e-6f)
        // A collapse reads the same progress the other way round: it STARTS at the card's resting size. Reading
        // it forwards was the bug - the row snapped to 88% on the first frame of every collapse, a pop with no
        // animation in it.
        assertEquals(1f, MorphCarry.contentScale(progress = 0f, from = 0.88f, towardCard = false), 1e-6f)
        assertEquals(0.94f, MorphCarry.contentScale(progress = 0.5f, from = 0.88f, towardCard = false), 1e-6f)
        assertEquals(0.88f, MorphCarry.contentScale(progress = 1f, from = 0.88f, towardCard = false), 1e-6f)
        // from = 1 is the switch being off: the size must not move at all, in either direction.
        assertEquals(1f, MorphCarry.contentScale(progress = 0f, from = 1f, towardCard = true), 1e-6f)
        assertEquals(1f, MorphCarry.contentScale(progress = 0.3f, from = 1f, towardCard = false), 1e-6f)
    }

    @Test fun `opacity has one ramp per direction and never overshoots`() {
        // Expanding with the shape: invisible at the pill, solid at the card.
        assertEquals(0f, MorphCarry.contentAlpha(t = 0f, progress = 0f, towardCard = true, followShape = true), 1e-6f)
        assertEquals(1f, MorphCarry.contentAlpha(t = 1f, progress = 1f, towardCard = true, followShape = true), 1e-6f)
        // Expanding on the clock is the b1378 style: the ramp ignores the box entirely.
        assertEquals(0.3f, MorphCarry.contentAlpha(t = 0.3f, progress = 0.9f, towardCard = true, followShape = false), 1e-6f)
        // Collapsing on the clock: gone by 40% of the fade, and exactly zero there, not merely small, because a
        // half-faded row hanging under the pill is a bug this file has already been written about. Note the
        // progress argument is irrelevant here - which is the whole point of the clock style.
        assertEquals(1f, MorphCarry.contentAlpha(t = 0f, progress = 0f, towardCard = false, followShape = false, outBy = 0.4f), 1e-6f)
        assertEquals(0.5f, MorphCarry.contentAlpha(t = 0.2f, progress = 0f, towardCard = false, followShape = false, outBy = 0.4f), 1e-6f)
        assertEquals(0f, MorphCarry.contentAlpha(t = 0.4f, progress = 0f, towardCard = false, followShape = false, outBy = 0.4f), 1e-6f)
        assertEquals(0f, MorphCarry.contentAlpha(t = 0.9f, progress = 0f, towardCard = false, followShape = false, outBy = 0.4f), 1e-6f)
        // The two ends of the rule his report is about. Collapsing WITH the shape, the row must still be solid
        // while the card is a card, and must be GONE by the time the box is a pill. b1382 had this exactly
        // backwards (faded at the start, back to full opacity at the end), and being visible at the end is what
        // drew the card's icon on top of the pill's own: "icon duplicate hoke thoda right shift hoke original
        // wale pe draw ho jata hai".
        assertEquals(1f, MorphCarry.contentAlpha(t = 0f, progress = 0f, towardCard = false, followShape = true, outBy = 0.4f), 1e-6f)
        assertEquals(0f, MorphCarry.contentAlpha(t = 0f, progress = 0.4f, towardCard = false, followShape = true, outBy = 0.4f), 1e-6f)
        assertEquals(0f, MorphCarry.contentAlpha(t = 0f, progress = 1f, towardCard = false, followShape = true, outBy = 0.4f), 1e-6f)
        // And it must not come back at the end: every frame after the fade-out is empty.
        for (i in 40..100) assertEquals(0f, MorphCarry.contentAlpha(t = i / 100f, i / 100f, false, true), 1e-6f)
        for (i in 0..100) {
            val p = i / 100f
            for (toward in booleanArrayOf(true, false)) {
                for (follow in booleanArrayOf(true, false)) {
                    val a = MorphCarry.contentAlpha(p, p, toward, follow)
                    assertTrue("alpha out of range: $a", a in 0f..1f)
                    // Nothing is ever shown while the shape is a pill and the morph is not aimed at the card.
                    if (!toward && follow && p >= 0.4f) assertEquals(0f, a, 1e-6f)
                }
            }
        }
    }

    @Test fun `swap point is honoured in both directions`() {
        // He can move the swap in TestLab; the rule stays one glyph on the way in and its mirror on the way
        // out, which is the direction bug this file exists to remember.
        assertTrue(MorphCarry.showsPillGlyph(t = 0.7f, growing = true, swapAt = 0.8f))
        assertFalse(MorphCarry.showsPillGlyph(t = 0.9f, growing = true, swapAt = 0.8f))
        // Off the knife-edge on purpose: 1f - 0.8f is 0.19999999 in binary, so a t of exactly 0.2f would
        // pass by rounding rather than by the rule.
        assertTrue(MorphCarry.showsPillGlyph(t = 0.3f, growing = false, swapAt = 0.8f))
        assertFalse(MorphCarry.showsPillGlyph(t = 0.05f, growing = false, swapAt = 0.8f))
        // An early swap (20%) is the collapse's own 80%: the card keeps the app badge almost all the way in.
        assertFalse(MorphCarry.showsPillGlyph(t = 0.7f, growing = false, swapAt = 0.2f))
        assertTrue(MorphCarry.showsPillGlyph(t = 0.9f, growing = false, swapAt = 0.2f))
    }

    // Two things he asked for after feeling the styles: the row comes OUT of the pill's edge instead of being
    // switched on inside it, and the riding icon never shares the screen with the pill's own copy.
    @Test fun `the row is pulled out of the pill and lands exactly where the shape puts it`() {
        assertEquals(-14f, MorphCarry.contentLeadOffset(progress = 0f, leadPx = 14f), 1e-4f)
        assertEquals(-7f, MorphCarry.contentLeadOffset(progress = 0.5f, leadPx = 14f), 1e-4f)
        // Zero at the end is the whole point: no residual offset to look like a second, shifted copy.
        assertEquals(0f, MorphCarry.contentLeadOffset(progress = 1f, leadPx = 14f), 1e-6f)
        assertEquals(0f, MorphCarry.contentLeadOffset(progress = 0f, leadPx = 0f), 1e-6f)
    }

    @Test fun `only one icon is ever drawn while the ride is in flight`() {
        assertTrue(MorphCarry.pillIconHidden(rowAlpha = 1f, rowShown = true))
        assertFalse(MorphCarry.pillIconHidden(rowAlpha = 0f, rowShown = true))
        assertFalse(MorphCarry.pillIconHidden(rowAlpha = 1f, rowShown = false))
        // The threshold is not decoration: a row at 1% alpha still leaves a visible ghost of the app icon over
        // the pill's own, which is the "glitchy last frames" he described twice.
        assertTrue(MorphCarry.pillIconHidden(rowAlpha = 0.05f, rowShown = true))
        assertFalse(MorphCarry.pillIconHidden(rowAlpha = 0.02f, rowShown = true))
        for (i in 0..100) {
            val a = i / 100f
            val hidden = MorphCarry.pillIconHidden(a, true)
            val drawn = a > 0.02f
            assertTrue("two icons at once at alpha $a", hidden == drawn)
        }
    }
}
