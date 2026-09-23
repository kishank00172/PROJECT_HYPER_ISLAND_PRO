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
     * How open the card is, on a scale that means the same thing in both directions: 1 when the content sits
     * where it rests, 0 when the island is a pill. [shapeProgress] already runs 0 -> 1 whichever way the morph
     * goes (0 at the size it started at), which is precisely what tripped the collapse up: every consumer used
     * to re-derive "is this an expand?" and re-derive it wrongly, in three different ways. So the direction is
     * decided ONCE, here, and nothing downstream is allowed a second opinion.
     */
    fun openProgress(progress: Float, towardCard: Boolean): Float =
        if (towardCard) progress.coerceIn(0f, 1f) else 1f - progress.coerceIn(0f, 1f)

    /**
     * The content's entry offset, on the only axis the shape actually grows on. The island hangs from its own Y
     * and the box widens evenly on both sides (IslandMorphFrame), so the row must not move sideways at all - a
     * sideways entry is what read as "upper right se center ki aur" with the carry and "upper left" without it.
     * Instead it starts above the box's top edge - inside the pill, where the content came from, and clipped by
     * the containment clip the host already applies - and settles down into its centred rest place. [blockTop]
     * is the row's own centring leftover, which is what makes it land exactly where the layout wants it instead
     * of snapping there at the end. Apple's island content enters with `move(edge: .top)`/`slide`/`push` and is
     * never scaled as a box; Material's container transform anchors the content to the source's edge rather than
     * re-centring it in the destination. [dropPx] is the TestLab amount on top of the geometry.
     */
    fun contentEntryOffset(open: Float, blockTop: Float, dropPx: Float): Float =
        -(blockTop + dropPx) * (1f - open)

    /**
     * One step of a staggered entry: which slice of the shape's travel belongs to child [index] of [count].
     * Every child gets the same window length, the last one finishes with the shape, so nothing is left waiting
     * on a tail. Apple's island animates the expanded elements one after another and Material's staged entrances
     * keep the trailing delay well under the duration for the same reason.
     */
    fun childOpen(index: Int, count: Int, open: Float, stagger: Float): Float {
        if (count <= 1 || stagger <= 0f) return open
        val start = (index.toFloat() / (count - 1).toFloat()) * stagger
        return ((open - start) / (1f - stagger)).coerceIn(0f, 1f)
    }

    /**
     * The riding icon is THE element, so while it is in flight the pill's own copy is not drawn at all - one
     * copy on screen, never a cross-fade of two, which is what `matchedGeometryEffect` gets by interpolating a
     * single element instead of dissolving two views. It takes the slot back on the frame the row is done with
     * it, and because the rider rides the box's own left edge it lands ON that slot, so the hand-back is a swap
     * of identical pixels rather than a fade.
     *
     * This used to be `rowAlpha > 0.02`, and that is what made the transformation look broken: the row's fade
     * reaches 0 at [goneBy] (25-45% in his own log), so the pill's icon popped in a third of the way through
     * while the rider still had most of its travel to go. The fade is on the content views now, so the row's
     * alpha is no longer a proxy for "the rider is on screen" - its real visibility is.
     */
    fun pillIconVisible(rideOn: Boolean, rowShown: Boolean): Boolean = !(rideOn && rowShown)

    /**
     * The rider's shift, converted into its parent's space so the DRAWN position is the box's own edge however
     * the parent is transformed. Without the compensation the row's own scale shortens the travel - 350 px at
     * 0.88 lands 42 px short of the pill's slot, which is a visible mis-fit at the hand-back - and the icon's
     * size would be scaled twice, once by the row and once by its own curve.
     */
    fun riderShift(shiftPx: Float, parentScale: Float): Float = shiftPx / parentScale.coerceAtLeast(0.05f)

    /**
     * The card content's own scale: it opens with the island instead of standing still inside it. [open] comes
     * from [openProgress], so both directions are already sorted out - this is one line and cannot be inverted.
     */
    fun contentScale(open: Float, from: Float): Float = from + (1f - from) * open

    /** The size the rider is drawn at, with its parent's scale divided back out (see [riderShift]). */
    fun riderScale(scale: Float, parentScale: Float): Float = scale / parentScale.coerceAtLeast(0.05f)

    /**
     * The content's own progress - one number that every content property reads, opacity first. Expanding, it
     * comes up with the shape. Collapsing, it has to be
     * gone before the shrinking box could cut a glyph in half, so it gets out of the way by [goneBy] of the
     * travel - that number was measured on hardware (the two collapse targets sat at 0.40 to idle and 0.45 to
     * the ping pill), which is why it is a TestLab slider and not a constant of mine.
     *
     * This used to take both the clock and the shape progress and pick between them, and it inverted the shape
     * progress on the way in: alpha reached 1 exactly at pill size, so the whole row - with its per-cell rounded
     * card backgrounds, and the icon riding inside it - was painted at full strength over the collapsed pill.
     * "pill bhi duplicate", "aakhri frames mei card ka content overlay dikh raha hai". One direction rule up
     * front, in [openProgress], is what keeps that from being re-introduced.
     */
    fun contentOpen(open: Float, towardCard: Boolean, goneBy: Float = 0.45f): Float =
        if (towardCard) open else (1f - (1f - open) / goneBy).coerceIn(0f, 1f)

    /**
     * How far one element of the card has come, given the content's own [contentOpen]. The per-part form gives
     * every element its own window - the title `[0, .6]`, the buttons `[.45, 1]` - so the elements are in
     * reading order without a second clock to keep in step, and a collapse plays the same windows backwards:
     * whatever arrived last is the first thing to go.
     */
    fun partProgress(open: Float, start: Float, end: Float): Float {
        val span = (end - start).coerceAtLeast(0.01f)
        return ((open - start) / span).coerceIn(0f, 1f)
    }
}
