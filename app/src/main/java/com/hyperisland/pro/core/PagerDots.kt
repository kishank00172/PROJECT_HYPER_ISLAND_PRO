package com.hyperisland.pro.core

/** round-G ITEM 1 (sonnet 5.5's stateless redesign): the pager is a pure function of (idx, total).
  *  No sliding capsule, no window state => no possible stuck state. Single source of truth for the
  *  geometry, unit-tested, then the service only RENDERS this spec (with slot morphing between values). */
enum class PagerMode { HIDDEN, DOTS, TRACK }

data class PagerSpecResult(
    val mode: PagerMode,
    /** DOTS mode only: per-slot chip width in dp (DOT_W_DP dot / DASH_W_DP dash), in page order. */
    val slots: List<Int>,
    val gapDp: Int,
    /** TRACK mode only: full track width/height and the thumb's left position, all in dp. */
    val trackWidthDp: Int,
    val trackHeightDp: Int,
    val thumbWidthDp: Int,
    val thumbLeftDp: Float,
)

object PagerDots {
    const val DOT_W_DP = 4
    const val DASH_W_DP = 12
    const val GAP_DP = 4
    const val TRACK_W_DP = 40
    const val THUMB_W_DP = 12
    const val CHIP_H_DP = 4

    /** <=1 hidden. 2..5: dots, exactly one dash at idx. >=6: track + thumb (thumb walks 0..(W-w)
      *  with the index, so a 23-page ring is never a railway line). Max DOTS width = 12 + 4*(4+4) = 44dp. */
    fun specFor(idx: Int, total: Int): PagerSpecResult = when {
        total <= 1 -> PagerSpecResult(PagerMode.HIDDEN, emptyList(), GAP_DP, 0, 0, 0, 0f)
        total <= 5 -> {
            val i = idx.coerceIn(0, total - 1)
            PagerSpecResult(PagerMode.DOTS, List(total) { s -> if (s == i) DASH_W_DP else DOT_W_DP }, GAP_DP, 0, 0, 0, 0f)
        }
        else -> {
            val i = idx.coerceIn(0, total - 1)
            val left = (i / (total - 1).toFloat()) * (TRACK_W_DP - THUMB_W_DP)
            PagerSpecResult(PagerMode.TRACK, emptyList(), 0, TRACK_W_DP, CHIP_H_DP, THUMB_W_DP, left)
        }
    }

    /** MAX contents width in dp (dots mode worst case = total 5): enforced sanity bound. */
    fun maxWidthDp(total: Int): Int {
        if (total <= 1) return 0
        if (total <= 5) return DASH_W_DP + (total - 1) * DOT_W_DP + (total - 1) * GAP_DP
        return TRACK_W_DP
    }
}
