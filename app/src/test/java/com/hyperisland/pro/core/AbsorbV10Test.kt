package com.hyperisland.pro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AbsorbV10Test {

    /** bump: inside the window it is a 0..1 cosine hat exactly 1 at c, 0 at the edges and outside. */
    @Test fun bump_isCosineHatOnlyInsideWindow() {
        assertEquals(1f, AbsorbV10.bump(0.30f, 0.30f, 0.30f), 0.001f)
        assertEquals(0f, AbsorbV10.bump(0.60f, 0.30f, 0.30f), 0.001f)   // edge (strict window)
        assertEquals(0f, AbsorbV10.bump(0.72f, 0.30f, 0.30f), 0.001f)   // outside
        assertEquals(0.5f, AbsorbV10.bump(0.15f, 0.30f, 0.30f), 0.001f) // half-cosine point
    }

    /** the canonical pill table from sonnet 5.5: 366x104 rest, default amps. */
    @Test fun breathCurveMatchesSpecPxTable() {
        val restW = 366f; val restH = 104f
        val cases = listOf(
            // a      expectedW expectedH  expectedPop
            Triple(0.15f, 382.5f, 113.4f) to 1.113f,
            Triple(0.30f, 398.9f, 122.7f) to 1.363f,
            Triple(0.45f, 382.5f, 113.4f) to 1.267f,
            Triple(0.60f, 361.8f, 101.6f) to 0.993f,
            Triple(0.72f, 356.1f,  98.4f) to 0.905f,
            Triple(1.00f, 366.0f, 104.0f) to 1.000f,
        )
        for ((geo, pop) in cases) {
            val a = geo.first; val w = geo.second; val h = geo.third
            assertEquals("a=" + a + " w", w, AbsorbV10.widthF(restW, a), 0.2f)
            assertEquals("a=" + a + " h", h, AbsorbV10.heightF(restH, a), 0.2f)
            assertEquals("a=" + a + " pop", pop, AbsorbV10.badgePop(a), 0.002f)
        }
    }

    /** a=1 must be bit-quiet: exact rest rect, badge exactly 1.000 (owner invariant: last frame == rest). */
    @Test fun restIsExactAtA1() {
        assertEquals(0f, AbsorbV10.breath(1f), 0.0001f)
        assertEquals(366f, AbsorbV10.widthF(366f, 1f), 0.0001f)
        assertEquals(104f, AbsorbV10.heightF(104f, 1f), 0.0001f)
        assertEquals(1f, AbsorbV10.badgePop(1f), 0.0001f)
        // and at a=0 the curve starts from rest too
        assertEquals(0f, AbsorbV10.breath(0f), 0.0001f)
        assertEquals(1f, AbsorbV10.badgePop(0f), 0.0001f)
    }

    /** the signature: positive hump near a=0.30, shallower negative dip near a=0.72, peak scan sane. */
    @Test fun shapeHasPeakAndDip() {
        assertTrue(AbsorbV10.breath(0.30f) > 0.98f)
        val dip = AbsorbV10.breath(0.72f)
        assertTrue(dip < -0.29f && dip > -0.31f)
        val peak = AbsorbV10.peakBreath()
        assertTrue(peak > 0.99f && peak <= 1f)
    }
}
