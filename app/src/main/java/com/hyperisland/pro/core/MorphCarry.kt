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
     * seen. Returns true while the pill glyph should be drawn.
     */
    fun showsPillGlyph(t: Float, growing: Boolean, swapAt: Float = 0.5f): Boolean =
        if (growing) t < swapAt else t > 1f - swapAt
}
