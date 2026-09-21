package com.hyperisland.pro.core

/**
 * Where the *visible* box is, while the pill morphs into the card and back.
 *
 * The old morph resized the card view on every frame (`islandView.layoutParams = …` 60 times a second),
 * which on a screen-sized overlay window means a layout traversal of the whole tree per frame - the frame
 * drops and the "text finally sets" snap the tester reported on b1343. b1350 pinned the content columns so
 * their measured size stopped depending on the box; that is what made this possible: the view can be laid out
 * **once** at the final size and the growth can be *drawn* instead. This object is that drawing math, kept
 * Android-free and tested, because a wrong anchor here is a card whose content visibly crawls by a pixel on
 * some frames and not others - which is how it read on the device, "glitchy type, fir set hote hai".
 *
 * Two anchors, deliberately not symmetric (measured off `updateOutlineForIsland`:
 * `left = (screenWidth - w) / 2 + islandXDp`, `top = islandYDp`): the island is horizontally centered but
 * hangs from its own top edge, so the box grows *downward*.
 *
 * The vertical trick: the content is laid out for the final height, so it sits centered in the final box.
 * To put it back at the center of the *drawn* (smaller) box, the whole view is translated up by half the
 * height difference - `viewTranslationY` - and the box rect is pushed down by the same amount, so on screen
 * the box stays exactly where the window places it. That indirection is the point: an earlier version wrote
 * `translationY` onto the content children instead, and three other functions own those properties
 * (`clearDragVisuals()` zeroes them on any touch event, `endRingSwap()` reads them to decide whether a drag
 * is still pending), so a tap landing mid-morph reset the offset for a frame and the text snapped down then
 * back up. Nobody outside the morph touches the card view's own translation.
 */
data class MorphFrame(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    /** How far the card view itself shifts for this frame; 0 when the box is the full height. */
    val viewTranslationY: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

object IslandMorphFrame {

    /**
     * Frame for a card that will end up [finalW] x [finalH] but is currently drawing [w] x [h]. Sizes are
     * clamped into the final bounds: a box outside them would paint black over the wallpaper next to the card
     * the tester can see growing.
     */
    fun compute(finalW: Int, finalH: Int, w: Int, h: Int): MorphFrame {
        val boxW = w.coerceIn(0, finalW.coerceAtLeast(0))
        val boxH = h.coerceIn(0, finalH.coerceAtLeast(0))
        val shift = (boxH - finalH) / 2
        val left = (finalW - boxW) / 2
        return MorphFrame(left, -shift, left + boxW, boxH - shift, shift)
    }

    /** The dirty area a frame change needs: the union of the two boxes, not the whole screen. */
    fun dirtyBounds(a: MorphFrame?, b: MorphFrame?): IntArray {
        if (a == null) return intArrayOf(b?.left ?: 0, b?.top ?: 0, b?.right ?: 0, b?.bottom ?: 0)
        if (b == null) return intArrayOf(a.left, a.top, a.right, a.bottom)
        return intArrayOf(
            minOf(a.left, b.left), minOf(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom)
        )
    }
}

/**
 * Implemented by the card view. The service hands it a frame per animation frame instead of resizing it, and
 * takes the frames back when the morph lands. Deliberately two methods only: suppressing the background,
 * clipping the children and shifting the view is the view's business - the morph code must not have to know,
 * because "several owners for one property" is exactly how both the mid-morph pop (b1350) and the content
 * snap (this round) happened.
 */
interface MorphFrameHost {
    fun applyMorphFrame(frame: MorphFrame, cornerRadius: Float)
    fun clearMorphFrame()
}
