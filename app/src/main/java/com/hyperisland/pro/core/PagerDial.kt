package com.hyperisland.pro.core

/**
 * ROUND H / "pager v3" (owner work.md spec): Instagram-faithful windowed dial. Active page = white
 * DASH; inactive = dots; NO blue, NO TRACK/lining (the owner: "wo lining hata do"). Materialised
 * from his 8-frame measurements (centre pitch constant, sizes depend on SLOT not distance), plus
 * his pinned unit vectors. Pure Kotlin, no Android types - fully JVM-testable.
 */
object PagerDial {
    // spec §5 absolute geometry (dp). Sizes shrink, centres never drift.
    const val FULL_D = 4f     // dots in slots 2..6
    const val SMALL_D = 2.3f  // slots 1, 7  (0.571 * full)
    const val TINY_D = 0.8f   // slots 0, 8  (0.143 * full)
    const val DASH_W = 12f    // active (1.286 * full)
    const val DASH_H = 4f
    const val P_DEFAULT = 6.5f
    const val P_MIN = 5.5f
    const val DELTA = (DASH_W - FULL_D) / 2f           // = 4: neighbours sit past the dash's extra half
    const val VIEW_H_DP = 6f
    const val ALPHA_TINY = 0.18f

    enum class Mode { HIDDEN, ROW, DIAL }

    data class Item(val page: Int, val slot: Int, val xDp: Float, val wDp: Float, val hDp: Float, val alpha: Float, val dash: Boolean)
    data class Spec(val mode: Mode, val a: Int, val widthDp: Float, val items: List<Item>)

    /** §4 hysteresis: moving INSIDE the 5-full window does NOT scroll the strip. */
    fun windowA(idx: Int, n: Int, aPrev: Int): Int {
        var a = aPrev
        if (idx < a) a = idx
        else if (idx > a + 4) a = idx - 4
        return a.coerceIn(0, maxOf(0, n - 5))
    }

    fun slotOf(page: Int, a: Int): Int = page - a + 2

    /** Size belongs to the SLOT CLASS (spec §3 corrects Sonnet-5's distance-rule): never distance-to-active. */
    fun sizeForSlot(slot: Int, dash: Boolean): Triple<Float, Float, Float> = when {
        dash -> Triple(DASH_W, DASH_H, 1f)
        slot == 0 || slot == 8 -> Triple(TINY_D, TINY_D, ALPHA_TINY)
        slot == 1 || slot == 7 -> Triple(SMALL_D, SMALL_D, 1f)
        else -> Triple(FULL_D, FULL_D, 1f)
    }

    fun centreX(slot: Int, firstSlot: Int, x0: Float, activeSlot: Int, pitch: Float = P_DEFAULT): Float =
        x0 + (slot - firstSlot) * pitch + DELTA * (if (slot == activeSlot) 1 else if (slot > activeSlot) 2 else 0)

    /** Rest-state spec for (idx, N): what the dial must look like once the FLIP settles. */
    fun spec(idx: Int, n: Int, aPrev: Int, pitch: Float = P_DEFAULT): Spec {
        if (n <= 1) return Spec(Mode.HIDDEN, aPrev.coerceIn(0, maxOf(0, n)), 0f, emptyList())
        val mode = if (n <= 5) Mode.ROW else Mode.DIAL
        val a = if (mode == Mode.ROW) 0 else windowA(idx, n, aPrev)
        val firstSlot = if (mode == Mode.ROW) 2 else 0
        val x0 = if (mode == Mode.ROW) 2f else TINY_D / 2f
        val activeSlot = slotOf(idx.coerceIn(0, n - 1), a)
        val items = ArrayList<Item>(minOf(n, 9))
        for (i in 0 until n) {
            val slot = slotOf(i, a)
            if (mode == Mode.DIAL && (slot < 0 || slot > 8)) continue  // outside the 9-slot viewport: un-drawn
            val (w, h, alpha) = sizeForSlot(slot, i == idx)
            items += Item(i, slot, centreX(slot, firstSlot, x0, activeSlot, pitch), w, h, alpha, i == idx)
        }
        val wDp = if (mode == Mode.ROW) (n - 1) * pitch + 2 * DELTA + 4f else 8 * pitch + 2 * DELTA + TINY_D
        return Spec(mode, a, wDp, items)
    }

    /** FLIP edge slots, direction-aware (forward: leave -1 / enter 9; backward: mirrored). */
    fun edgeSlot(direction: Int, leaving: Boolean): Int =
        if (direction >= 0) (if (leaving) -1 else 9) else (if (leaving) 9 else -1)
}
