package com.hyperisland.pro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The carry is what turns "the island uncovers content that was already sitting there" into "the content came
 * with the island". It is a handful of numbers, so all of them are pinned here: the tester's complaint is visible
 * only on a device, but the arithmetic that makes it right or wrong is not.
 *
 * Two rules this file enforces after the fact, both learned the expensive way:
 *  - a curve test states what he SEES at both ends, not what the code computes. An earlier version of this file
 *    asserted the collapse's opacity as the implementation had it (`progress == 1 -> alpha == 1`) and called that
 *    a pass, so 101 green tests shipped a card drawn over a closed pill.
 *  - the direction is decided once, by [MorphCarry.openProgress]. Every consumer that re-derived "is this an
 *    expand?" got it wrong in its own way (size delta, the animation clock, a mirror that should not have been
 *    there), so none of them is allowed to any more.
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
    // scale and opacity morph". These are the functions the service reads per frame, so the whole policy is
    // testable here instead of inside a listener callback CI cannot reach.
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

    /**
     * The single direction rule. He gets to feel the difference: expanding, everything runs 0 to 1 with the
     * shape; collapsing, it runs the other way, and it does that by flipping this one number and nothing else.
     */
    @Test fun `open progress is the only direction decision`() {
        for (i in 0..100) {
            val p = i / 100f
            assertEquals(p, MorphCarry.openProgress(p, towardCard = true), 1e-6f)
            assertEquals(1f - p, MorphCarry.openProgress(p, towardCard = false), 1e-6f)
            val open = MorphCarry.openProgress(p, i % 2 == 0)
            assertTrue("open out of range: $open", open in 0f..1f)
        }
        assertEquals(0f, MorphCarry.openProgress(-3f, true), 1e-6f)
        assertEquals(1f, MorphCarry.openProgress(4f, true), 1e-6f)
        assertEquals(1f, MorphCarry.openProgress(-3f, false), 1e-6f)
        // The endpoints in his words: at the size it started at the card is closed as far as the content is
        // concerned, and at the size it is heading for it is open - whichever way the morph travelled.
        assertEquals(0f, MorphCarry.openProgress(0f, true), 1e-6f)
        assertEquals(1f, MorphCarry.openProgress(1f, true), 1e-6f)
        assertEquals(1f, MorphCarry.openProgress(0f, false), 1e-6f)
        assertEquals(0f, MorphCarry.openProgress(1f, false), 1e-6f)
    }

    @Test fun `content grows into the card and shrinks back into the pill`() {
        // Expanding: the row opens with the island, from the slider's floor to its resting size.
        assertEquals(0.88f, MorphCarry.contentScale(open = 0f, from = 0.88f), 1e-6f)
        assertEquals(0.94f, MorphCarry.contentScale(open = 0.5f, from = 0.88f), 1e-6f)
        assertEquals(1f, MorphCarry.contentScale(open = 1f, from = 0.88f), 1e-6f)
        // Collapsing, the caller passes the flipped clock, so the row STARTS at its resting size and only then
        // shrinks. Reading it forwards was the bug: 88% on the first frame of every collapse, a pop with no
        // animation in it.
        assertEquals(1f, MorphCarry.contentScale(MorphCarry.openProgress(0f, false), 0.88f), 1e-6f)
        assertEquals(0.94f, MorphCarry.contentScale(MorphCarry.openProgress(0.5f, false), 0.88f), 1e-6f)
        assertEquals(0.88f, MorphCarry.contentScale(MorphCarry.openProgress(1f, false), 0.88f), 1e-6f)
        // from = 1 is the switch being off: the size must not move at all, in either direction.
        assertEquals(1f, MorphCarry.contentScale(0f, 1f), 1e-6f)
        assertEquals(1f, MorphCarry.contentScale(0.3f, 1f), 1e-6f)
    }

    @Test fun `opacity has one ramp per direction and never overshoots`() {
        // Expanding: invisible while the box is a pill, solid when it is a card.
        assertEquals(0f, MorphCarry.contentAlpha(0f, towardCard = true), 1e-6f)
        assertEquals(0.5f, MorphCarry.contentAlpha(0.5f, towardCard = true), 1e-6f)
        assertEquals(1f, MorphCarry.contentAlpha(1f, towardCard = true), 1e-6f)
        // Collapsing: the two ends of the rule his report is about. The row must still be solid while the card
        // is a card, and must be GONE by [goneBy] of the shape's travel - b1382 had this backwards (faded at the
        // start, back to full opacity at the end), and being visible at the end is what drew the card's row and
        // its icon on top of the closed pill: "icon duplicate hoke thoda right shift hoke original wale pe draw
        // ho jata hai", "pill bhi duplicate".
        assertEquals(1f, MorphCarry.contentAlpha(1f, towardCard = false, goneBy = 0.4f), 1e-6f)
        assertEquals(0.5f, MorphCarry.contentAlpha(0.8f, towardCard = false, goneBy = 0.4f), 1e-6f)
        assertEquals(0f, MorphCarry.contentAlpha(0.6f, towardCard = false, goneBy = 0.4f), 1e-6f)
        // Exactly zero past the deadline, not merely small: a half-faded row hanging inside a pill is the bug.
        for (i in 40..100) assertEquals(0f, MorphCarry.contentAlpha(1f - i / 100f, false, 0.4f), 1e-6f)
        for (i in 0..100) {
            val p = i / 100f
            for (toward in booleanArrayOf(true, false)) {
                val a = MorphCarry.contentAlpha(MorphCarry.openProgress(p, toward), toward, 0.45f)
                assertTrue("alpha out of range: $a", a in 0f..1f)
                if (!toward && p >= 0.45f) assertEquals("drawn over a closed pill at $p", 0f, a, 1e-6f)
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

    /**
     * The axis the content enters on. His words: "pill center mei hai and niche hoke dono side (left, right)
     * uniformly expand hota hai, but uska content ye feel nahi deta - aisa lagta hai upper right side se niche
     * center ki aur aa rha hai", and for the style without the sideways carry: "3rd option upper left side". So
     * the row comes down from the edge the pill is on, and it must land on exactly its rest position: any
     * residual offset would be the same visible snap the re-centring produced.
     */
    @Test fun `content comes down from the pill and lands on its rest position`() {
        // blockTop 78 = the row's own centring leftover inside the pinned view; drop = the slider on top of it.
        assertEquals(-98f, MorphCarry.contentEntryOffset(0f, 78f, 20f), 1e-4f)
        assertEquals(-49f, MorphCarry.contentEntryOffset(0.5f, 78f, 20f), 1e-4f)
        assertEquals(0f, MorphCarry.contentEntryOffset(1f, 78f, 20f), 1e-6f)
        // The slider at 0 still moves, because the movement is the geometry's, not a decoration's.
        assertEquals(-78f, MorphCarry.contentEntryOffset(0f, 78f, 0f), 1e-4f)
        assertEquals(0f, MorphCarry.contentEntryOffset(1f, 78f, 0f), 1e-6f)
        // A view with no centring leftover (the block already at the top) does not move on the geometry alone.
        assertEquals(0f, MorphCarry.contentEntryOffset(1f, 0f, 0f), 1e-6f)
        var prev = MorphCarry.contentEntryOffset(0f, 78f, 20f)
        for (i in 1..100) {
            // Opening, every frame is downward and nothing ever sits below where it will rest.
            val y = MorphCarry.contentEntryOffset(i / 100f, 78f, 20f)
            assertTrue("frame $i moved up: $y after $prev", y >= prev)
            assertTrue("row dropped below its rest place: $y", y <= 0f)
            prev = y
        }
        assertEquals(0f, prev, 1e-6f)
    }

    @Test fun `staggered children lead in reading order and finish with the shape`() {
        // Stagger off: the whole row is one element, as before.
        assertEquals(0.4f, MorphCarry.childOpen(2, 4, 0.4f, 0f), 1e-6f)
        assertEquals(0.4f, MorphCarry.childOpen(0, 1, 0.4f, 0.5f), 1e-6f)
        val n = 4
        val stagger = 0.5f
        val open = 0.3f
        val each = (0 until n).map { MorphCarry.childOpen(it, n, open, stagger) }
        // The header is ahead of the actions, which is what makes it read as arriving in order.
        for (i in 1 until n) assertTrue("child $i ahead of ${i - 1}: $each", each[i] <= each[i - 1])
        // Nothing negative, nothing past the end, and the last one still has room to arrive: no tail waiting
        // after the shape has finished, which is why the delay is shared out instead of added on.
        for (o in 0..100) {
            val openAll = o / 100f
            for (i in 0 until n) {
                val c = MorphCarry.childOpen(i, n, openAll, stagger)
                assertTrue("child $i out of range: $c", c in 0f..1f)
            }
            assertTrue(MorphCarry.childOpen(n - 1, n, openAll, stagger) <= openAll + 1e-5f)
        }
        assertEquals(1f, MorphCarry.childOpen(0, n, 1f, stagger), 1e-6f)
        assertEquals(1f, MorphCarry.childOpen(n - 1, n, 1f, stagger), 1e-6f)
    }
}
