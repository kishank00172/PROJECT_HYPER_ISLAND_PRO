package com.hyperisland.pro.core

/**
 * Where the *visible* box is, while the pill morphs into the card and back.
 *
 * The old morph resized the card view on every frame (`islandView.layoutParams = …` 60 times a second),
 * which on a screen-sized overlay window means a layout traversal of the whole tree per frame - the frame
 * drops and the "text finally sets" snap the tester reported on b1343. b1350 pinned the content columns so
 * their measured size stopped depending on the box; that is what makes this possible: the view can be laid
 * out **once** at the final size and the growth can be *drawn* instead. This object is that drawing math,
 * kept Android-free and tested, because a wrong anchor here is a card that visibly slides by a pixel or two
 * on every frame - exactly the class of bug that cost four rebuilds earlier.
 *
 * The anchors come from how the window is placed, and only one axis is centered (measured off
 * `updateOutlineForIsland`: `left = (screenWidth - w) / 2 + islandXDp`, `top = islandYDp`): the island hangs
 * from its own Y and grows downward, while it stays horizontally centered. So in the view's own coordinates
 * the box's top is always 0 and only the left edge moves.
 */
data class MorphFrame(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    /**
     * How far the content has to shift so it stays centered inside the *drawn* box instead of inside the
     * pinned view (bound plus headroom). Negative while the box is shorter than that surface, 0 when they
     * match - which at the spring's peak is the whole point: the curve's maximum moves the content not at all.
     */
    val contentOffsetY: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

object IslandMorphFrame {

    /**
     * Box for a card drawn inside a view of [boundW] x [boundH] (+ [headH] of vertical headroom),
     * currently [w] x [h]. The clamp keeps the box
     * inside that view - outside it there is no surface, and the tester would see a black edge over the
     * wallpaper - but the bound is the **view**, which during a morph is the larger of start and target.
     *
     * That distinction is the whole of "collapse first step se final step pe ja raha hai". Clamping to the
     * target is harmless while growing, because every intermediate is smaller than the target, and it destroys
     * a shrink completely: with the pill as the bound, a card-sized frame at t=0 clamps down to the pill, and
     * so does every frame after it. His b1373 export shows the consequence in full - `frames=39 avg=7ms
     * hz=120`, i.e. thirty-nine perfectly delivered frames drawing the *same* box. The animation was not
     * slow; the shape was never asked for.
     */
    fun compute(boundW: Int, boundH: Int, w: Int, h: Int, headH: Int = 0): MorphFrame {
        val boxW = w.coerceIn(0, boundW.coerceAtLeast(0))
        val boxH = h.coerceIn(0, (boundH + headH).coerceAtLeast(0))
        val left = (boundW - boxW) / 2
        // The content is centred against the WHOLE pinned surface - bound plus headroom - not against the
        // card's natural height. His b1422 verdict, verbatim: "spring effect content pe jerky feel deta hai".
        // Centring against [boundH] while the view is [boundH] + [headH] tall leaves the content standing half
        // the headroom out of place, and every pixel the spring adds or removes around [boundH] then flips the
        // offset's sign: a content bounce at the curve's own frequency plus a 1-2 px rounding jitter between
        // frames - motion ON the content instead of UNDER it. Centring against the full surface instead has the
        // property the whole design wants: at the spring's peak the box and the surface are the same size, the
        // offset is 0, the content stands still, and the overshoot is nothing but empty surface below the card.
        // At rest the offset is -headH/2, which added to the layout's own centring leftover (+headH/2) puts the
        // content exactly where a headroom-free view would - the hand-off needs no correction either way, and
        // for the four classic styles headH is 0 so every number here is identical to the old arithmetic.
        return MorphFrame(left, 0, left + boxW, boxH, (boxH - boundH - headH) / 2)
    }

    /**
     * Why [headH] exists. The same clamp that silently flattened a horizontal overshoot - the tester's
     * "39 frames drawing the same box", and later his "spring sirf icon pe hai" - also sits on the vertical
     * axis, and the two spring styles now spend all of their elastic travel there. A view whose height is the
     * card's cannot draw a card that is 40 px taller than itself. So the view gets exactly as much extra
     * height as the curve is expected to need, and this argument says how much the box may use of it. Default
     * zero: the four classic styles, and every other caller, get the arithmetic they have always had.
     */
    fun naturalBoundH(pinH: Int, headH: Int): Int = (pinH - headH).coerceAtLeast(0)

    /** The dirty area a frame change needs: the union of the two boxes, not the whole screen. */
    fun dirtyBounds(a: MorphFrame?, b: MorphFrame?): IntArray {
        if (a == null) return intArrayOf(b?.left ?: 0, b?.top ?: 0, b?.right ?: 0, b?.bottom ?: 0)
        if (b == null) return intArrayOf(a.left, a.top, a.right, a.bottom)
        return intArrayOf(minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))
    }
}

/**
 * Implemented by the card view. The service hands it a frame per animation frame instead of resizing it,
 * and takes the frames back when the morph lands. Deliberately two methods only: whatever the view does to
 * suppress its own background, clip its children and translate its content is its business - the morph code
 * must not have to know, because that is how the two animators racing on one alpha got written in the first
 * place.
 */
interface MorphFrameHost {
    fun applyMorphFrame(frame: MorphFrame, cornerRadius: Float)
    fun clearMorphFrame()
}
