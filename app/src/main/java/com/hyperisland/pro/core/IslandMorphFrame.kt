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
     * final-sized view. Negative while the box is shorter than the view, 0 when they match.
     */
    val contentOffsetY: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

object IslandMorphFrame {

    /**
     * Box for a card that will end up [finalW] x [finalH] but is currently [w] x [h]. Sizes are clamped into
     * the final bounds: a frame outside them would make the drawn box spill past the view that is supposed
     * to contain it, and the tester would see a black edge over the wallpaper.
     */
    fun compute(finalW: Int, finalH: Int, w: Int, h: Int): MorphFrame {
        val boxW = w.coerceIn(0, finalW.coerceAtLeast(0))
        val boxH = h.coerceIn(0, finalH.coerceAtLeast(0))
        val left = (finalW - boxW) / 2
        return MorphFrame(left, 0, left + boxW, boxH, (boxH - finalH) / 2)
    }

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
