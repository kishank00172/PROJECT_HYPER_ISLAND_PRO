package com.hyperisland.pro.core

/**
 * The math that lets the *content* ride the shape instead of waiting inside it to be uncovered.
 *
 * His words, twice now: "saara content draw ho ja rha hai but invisible, and island me visible kar deta hai".
 * That is the pinned-view design taken literally: the view is laid out once at its final size, so the row sits
 * at the left edge of the final box while the drawn box starts pill-sized and in the middle. Everything before
 * the box's left edge is clipped away, so the first frames show an empty pill and then the text is *uncovered*.
 * A reveal is what a mask does, and a mask is what he keeps rejecting.
 *
 * The carry is one number per frame, with two properties that make it free of side effects: it is exactly the
 * box's own left edge, so it disappears by itself as the box reaches full width (no reset at the end of an
 * expand, no snap when the settle lands), and it never touches layout - a translation is a render transform,
 * so the accepted "no reflow, no text jitter" rule from the width lock stays intact.
 *
 * Round 20 asked for more than the translation: "Island ke andar jo content hota hai use bhi morph karo -
 * scale and opacity morph". So the same progress number also drives how big the row is and how visible it is,
 * and the three are one policy with one owner, because two writers on one property is a bug this file has
 * already been written about. Which of them are active is his choice in TestLab, not mine per build.
 */
object MorphCarry {

    /** Horizontal shift for the row: pin its left edge to the box, not to the view. */
    fun translationX(boxLeft: Int): Float = boxLeft.toFloat()

    /**
     * The icon's visual scale. [from] is the ratio between the two icon boxes (the pill draws the small glyph,
     * the card a 38 dp launcher badge), so growing means 0.84 -> 1 and shrinking means the reverse.
     */
    fun iconScale(t: Float, from: Float): Float = from + (1f - from) * t

    /**
     * When the travelling element stops wearing the pill's glyph and puts on the launcher icon. On the way out
     * it is the first half (so the card never shows a mismatched badge while it settles); on the way in it is
     * the second half, because the content is faded away by ~45% of a collapse and a late swap would never be
     * seen. Returns true while the pill glyph should be drawn. [swapAt] is his TestLab knob.
     */
    fun showsPillGlyph(t: Float, growing: Boolean, swapAt: Float = 0.5f): Boolean =
        if (growing) t < swapAt else t > 1f - swapAt

    /**
     * How far the shape itself has travelled: 0 at the size it started at, 1 at the size it is going to.
     * Derived from the box's own left edge, so it reads what is actually drawn instead of trusting the clock -
     * and a shrink needs no special case, because the span is negative and 0 still means "at the start".
     */
    fun shapeProgress(boxLeft: Int, fromW: Int, toW: Int, boundW: Int): Float {
        val span = (toW - fromW).toFloat()
        if (span == 0f) return 1f
        val boxW = boundW - 2 * boxLeft
        return ((boxW - fromW) / span).coerceIn(0f, 1f)
    }

    /**
     * How far the row is still pulled back into the pill's mouth, in pixels. Apple animates the island's
     * content with `move(edge:)`/`slide`/`push` rather than scaling it - the content comes OUT of the edge the
     * shape opened from - and Material's enter pattern is "fade + slide from edge" with the exit faster than the
     * enter. This is that idea on our shape clock: 0 at the pill's edge, gone at full width, so the row reads
     * as being pulled out rather than being switched on.
     */
    fun contentLeadOffset(progress: Float, leadPx: Float): Float = -leadPx * (1f - progress)

    /**
     * While the rider is in flight, only ONE of the two icons may be drawn. `matchedGeometryEffect` guarantees
     * this by interpolating a single snapshot instead of cross-fading two views; we have two views, so the
     * exclusion is stated here and pinned by a test. This is the duplicate he reported - "icon duplicate hoke
     * thoda right shift hoke original wale pe draw ho jata hai", and later the whole pill too.
     */
    fun pillIconHidden(rowAlpha: Float, rowShown: Boolean): Boolean = rowShown && rowAlpha > 0.02f

    /**
     * The card content's own scale: it opens with the island instead of standing still inside it.
     *
     * [progress] is 0 at the size the morph STARTED at and 1 at the size it is ENDING at, in both directions -
     * that is what makes it the shape's own clock. Which means a collapse has to read it the other way round:
     * the row is at its resting size while the card is open and only shrinks toward [from] as the box closes on
     * the pill. Reading it forwards on a collapse was the bug: the content snapped to 88% on the first frame of
     * every collapse and then grew while it vanished.
     */
    fun contentScale(progress: Float, from: Float, towardCard: Boolean): Float =
        if (towardCard) from + (1f - from) * progress else 1f - (1f - from) * progress

    /**
     * One owner of the card content's opacity, for both directions.
     *
     * Expanding, the content comes up with the shape. Collapsing, it has to be gone before the shrinking box
     * could cut a glyph in half - the "out by ~40%" rule, measured on hardware twice, kept as the ramp. With
     * [followShape] the ramp is tied to how much of the island has actually opened rather than to the animation
     * clock, which is the difference between a shape revealing a still picture and content travelling with a
     * shape. [outBy] stays a parameter because the two collapse targets differ slightly (0.45 to the ping pill,
     * 0.40 to idle) and that was tuned on a device, not here.
     *
     * [progress] already runs 0 -> 1 in BOTH directions (it is measured from the morph's start size to its end
     * size), so it is used directly here and NOT mirrored. Mirroring it was the bug he saw: the card content
     * faded out at the START of a collapse and came BACK to full opacity at the end, which leaves the whole card
     * row - and the app icon riding inside it - drawn at full strength over the pill's own icon in the last
     * frames. "icon duplicate hoke thoda right shift hoke original wale pe draw ho jata hai".
     */
    fun contentAlpha(t: Float, progress: Float, towardCard: Boolean, followShape: Boolean, outBy: Float = 0.4f): Float {
        val ramp = if (followShape) progress else t
        return if (towardCard) ramp.coerceIn(0f, 1f) else (1f - ramp / outBy).coerceIn(0f, 1f)
    }
}
