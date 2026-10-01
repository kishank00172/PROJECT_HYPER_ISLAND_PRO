package com.hyperisland.pro.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min

/** round-G ITEM 4 (sonnet 5.5's absorb v10): the silhouette-breath absorb pulse, as PURE math.
  *
  * The v2 era ends on a collapse: the pill "gulps" once. The old variety scaled the WRAPPER
  * (kept, selectable via the absorb_style debug cmd) - v10 never touches view scale for the
  * silhouette; the numbers here are rendered by the service through IslandMorphFrame
  * (top edge fixed, centre fixed, radius = capsule rule).
  *
  * Curve atoms (verbatim his spec):
  *   bump(x,c,h) = 0.5*(1+cos(pi*min(1,|x-c|/h)))   inside |x-c|<h, else 0
  *   breath      = bump(a,.30,.30) - .30*bump(a,.72,.22)
  *   w = restW*(1+wAmp*breath), h = restH*(1+hAmp*breath)      (defaults .09 / .18)
  *   badgePop    = 1 + badgeAmp*bump(a,.34,.30) - .10*bump(a,.75,.22)   (default .38, a=1 => 1.000)
  *
  * Canon check for a 366x104 pill: a=.15 -> 382.5x113.4 b1.113 / .30 -> 398.9x122.7 b1.363 /
  * .45 -> 382.5x113.4 b1.267 / .60 -> 361.8x101.6 b0.993 / .72 -> 356.1x98.4 b0.905 / 1.0 -> 366x104 b1.0
  * (asserted literally in AbsorbV10Test).
  */
object AbsorbV10 {
    const val DEFAULT_W_AMP = 0.09f
    const val DEFAULT_H_AMP = 0.18f
    const val DEFAULT_BADGE_AMP = 0.38f
    const val DEFAULT_DUR_MS = 300L

    fun bump(x: Float, c: Float, h: Float): Float {
        val d = abs(x - c)
        return if (d < h) 0.5f * (1f + cos(PI.toFloat() * min(1f, d / h))) else 0f
    }

    /** signed breath factor: +1 at the full-breath peak (a=0.30), -0.30 at the deepest dip (a=0.72), 0 at rest. */
    fun breath(a: Float): Float = bump(a, 0.30f, 0.30f) - 0.30f * bump(a, 0.72f, 0.22f)

    fun widthF(restW: Float, a: Float, wAmp: Float = DEFAULT_W_AMP): Float = restW * (1f + wAmp * breath(a))
    fun heightF(restH: Float, a: Float, hAmp: Float = DEFAULT_H_AMP): Float = restH * (1f + hAmp * breath(a))

    /** badge pop around 1.0; EXACTLY 1.000 at a=1 (both bumps are outside their windows there). */
    fun badgePop(a: Float, badgeAmp: Float = DEFAULT_BADGE_AMP): Float =
        1f + badgeAmp * bump(a, 0.34f, 0.30f) - 0.10f * bump(a, 0.75f, 0.22f)

    /** peak positive breath over the sweep (>=0) - the headroom the wrapper needs so a peak frame never clamps. */
    fun peakBreath(): Float {
        var m = 0f
        var probe = 0.01f
        while (probe <= 1.001f) { if (breath(probe) > m) m = breath(probe); probe += 0.01f }
        return m
    }
}
