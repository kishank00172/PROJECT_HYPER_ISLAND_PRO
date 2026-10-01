package com.hyperisland.pro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PagerDotsTest {

    @Test fun hidden_whenTotalIsOneOrZero() {
        assertEquals(PagerMode.HIDDEN, PagerDots.specFor(0, 1).mode)
        assertEquals(PagerMode.HIDDEN, PagerDots.specFor(0, 0).mode)
    }

    @Test fun dotsMode_hasExactlyOneDashAtIdx() {
        for ((idx, total) in listOf(0 to 2, 1 to 2, 0 to 5, 4 to 5, 2 to 5)) {
            val s = PagerDots.specFor(idx, total)
            assertEquals(PagerMode.DOTS, s.mode)
            assertEquals(total, s.slots.size)
            assertEquals(1, s.slots.count { it == PagerDots.DASH_W_DP })
            assertEquals(PagerDots.DASH_W_DP, s.slots[idx])
        }
    }

    @Test fun dotsTotalWidth_neverExceeds44dp() {
        assertEquals(44, PagerDots.maxWidthDp(5))
        assertTrue(PagerDots.maxWidthDp(2) <= 44)
    }

    @Test fun trackMode_thumbFormulaAndBounds() {
        val t0 = PagerDots.specFor(0, 6)
        assertEquals(PagerMode.TRACK, t0.mode)
        assertEquals(0f, t0.thumbLeftDp, 0.001f)
        val tMax = PagerDots.specFor(22, 23)
        assertEquals(PagerMode.TRACK, tMax.mode)
        assertEquals((PagerDots.TRACK_W_DP - PagerDots.THUMB_W_DP).toFloat(), tMax.thumbLeftDp, 0.001f)
        val tMid = PagerDots.specFor(11, 23)
        assertEquals((11f / 22f) * 28f, tMid.thumbLeftDp, 0.001f)
        assertEquals(40, tMax.trackWidthDp)
        assertEquals(4, tMax.trackHeightDp)
        assertEquals(12, tMax.thumbWidthDp)
    }

    @Test fun thumbIsMonotonicInIndex() {
        var prev = -1f
        for (i in 0..22) {
            val l = PagerDots.specFor(i, 23).thumbLeftDp
            assertTrue("idx=" + i, l >= prev)
            prev = l
        }
    }
}
