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

    /** The card content's own scale: it opens with the island instead of standing still inside it. */
    fun contentScale(progress: Float, from: Float): Float = from + (1f - from) * progress

    /**
     * One owner of the card content's opacity, for both directions.
     *
     * Expanding, the content comes up with the shape. Collapsing, it has to be gone before the shrinking box
     * could cut a glyph in half - the "out by ~40%" rule, measured on hardware twice, kept as the ramp. With
     * [followShape] the ramp is tied to how much of the island has actually opened rather than to the animation
     * clock, which is the difference between a shape revealing a still picture and content travelling with a
     * shape. [outBy] stays a parameter because the two collapse targets differ slightly (0.45 to the ping pill,
     * 0.40 to idle) and that was tuned on a device, not here.
     */
    fun contentAlpha(t: Float, progress: Float, growing: Boolean, followShape: Boolean, outBy: Float = 0.4f): Float {
        val ramp = if (followShape) (if (growing) progress else 1f - progress) else t
        return if (growing) ramp.coerceIn(0f, 1f) else (1f - ramp / outBy).coerceIn(0f, 1f)
    }
}
