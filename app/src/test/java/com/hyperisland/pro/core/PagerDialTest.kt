package com.hyperisland.pro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ROUND H pinned vectors - every number is the owner's spec §5/§11, asserted as emitted. */
class PagerDialTest {
    private fun approx(a: Float, b: Float, msg: String) =
        assertTrue("$msg: got=$a want=$b", kotlin.math.abs(a - b) < 0.06f)

    private fun itemMap(spec: PagerDial.Spec) = spec.items.associateBy { it.page }

    @Test fun plainRow_n2() {
        val s = PagerDial.spec(1, 2, 0)
        assertEquals(PagerDial.Mode.ROW, s.mode); approx(18.5f, s.widthDp, "W")
        val m = itemMap(s)
        approx(m[0]!!.xDp, 2.0f, "0.x"); approx(m[0]!!.wDp, 4f, "0.w")
        approx(m[1]!!.xDp, 12.5f, "1.x"); approx(m[1]!!.wDp, 12f, "1.w"); assertTrue(m[1]!!.dash)
    }

    @Test fun plainRow_n5_idx2() {
        val s = PagerDial.spec(2, 5, 0)
        approx(38.0f, s.widthDp, "W")
        val want = mapOf(0 to 2.0f, 1 to 8.5f, 2 to 19.0f, 3 to 29.5f, 4 to 36.0f)
        for ((pg, x) in want) {
            val it = itemMap(s)[pg]!!; approx(it.xDp, x, "x($pg)")
            approx(it.wDp, if (pg == 2) 12f else 4f, "w($pg)")
        }
    }

    @Test fun dial_n40_idx0() {
        val s = PagerDial.spec(0, 40, 0)
        assertEquals(PagerDial.Mode.DIAL, s.mode); approx(60.8f, s.widthDp, "W")
        val want = mapOf(0 to 17.4f, 1 to 27.9f, 2 to 34.4f, 3 to 40.9f, 4 to 47.4f, 5 to 53.9f, 6 to 60.4f)
        val m = itemMap(s); assertEquals(7, s.items.size)
        for ((pg, x) in want) approx(m[pg]!!.xDp, x, "x($pg)")
        assertTrue(m[0]!!.dash); approx(m[5]!!.wDp, 2.3f, "small"); approx(m[6]!!.wDp, 0.8f, "tiny")
        approx(m[6]!!.alpha, 0.18f, "tinyAlpha")
    }

    @Test fun dial_n40_idx5_dashPinned() {
        val s = PagerDial.spec(5, 40, 0)
        assertEquals(1, s.a)
        val m = itemMap(s)
        approx(m[5]!!.xDp, 43.4f, "dash stays 43.4 when slot=6")
        val want = mapOf(0 to 6.9f, 1 to 13.4f, 2 to 19.9f, 3 to 26.4f, 4 to 32.9f, 6 to 53.9f, 7 to 60.4f)
        for ((pg, x) in want) approx(m[pg]!!.xDp, x, "x($pg)")
    }

    @Test fun dial_n40_idx6_andLast() {
        var s = PagerDial.spec(6, 40, 1)
        assertEquals(2, s.a)
        approx(itemMap(s)[6]!!.xDp, 43.4f, "dash pinned")
        s = PagerDial.spec(39, 40, 35)
        assertEquals(35, s.a)
        val m = itemMap(s)
        approx(m[33]!!.xDp, 0.4f, "tiny.left"); approx(m[39]!!.xDp, 43.4f, "dash.last")
    }

    @Test fun window_hysteresis_vector() {
        // forward idx 0..7 -> a = 0,0,0,0,0,1,2,3
        var a = 0
        val fwd = IntArray(8)
        for (idx in 0..7) { a = PagerDial.windowA(idx, 40, a); fwd[idx] = a }
        assertTrue(fwd.contentEquals(intArrayOf(0, 0, 0, 0, 0, 1, 2, 3)))
        // backward from idx7 (a=3): idx 6,5,4,3 keep a=3; idx2->2; idx1->1
        a = 3
        for (idx in intArrayOf(6, 5, 4, 3)) { a = PagerDial.windowA(idx, 40, a); assertEquals(3, a) }
        a = PagerDial.windowA(2, 40, a); assertEquals(2, a)
        a = PagerDial.windowA(1, 40, a); assertEquals(1, a)
        // jumps
        assertEquals(16, PagerDial.windowA(20, 40, 0))
        assertEquals(35, PagerDial.windowA(39, 40, 0))
    }

    @Test fun properties_over_full_range() {
        for (n in 2..40) {
            var a = 0
            for (idx in 0 until n) {
                val s = PagerDial.spec(idx, n, a); a = s.a
                val dashes = s.items.filter { it.dash }
                assertEquals("N=$n idx=$idx: one dash", 1, dashes.size)
                assertTrue("N=$n idx=$idx: dash slot 2..6", dashes[0].slot in 2..6)
                assertTrue("N=$n idx=$idx: <=9 items", s.items.size <= 9)
                if (s.mode == PagerDial.Mode.DIAL && idx >= 4) approx(dashes[0].xDp, 43.4f, "N=$n idx=$idx pinned-x")
                val byX = s.items.sortedBy { it.xDp }
                for (k in 0 until byX.size - 1) {
                    val gap = (byX[k + 1].xDp - byX[k + 1].wDp / 2f) - (byX[k].xDp + byX[k].wDp / 2f)
                    assertTrue("N=$n idx=$idx pair=$k gap=$gap", gap >= 2.49f)
                }
            }
        }
    }

    @Test fun widths_constant_per_mode_and_N() {
        assertTrue(kotlin.math.abs(PagerDial.spec(0, 40, 0).widthDp - 60.8f) < 0.01f)
        assertTrue(kotlin.math.abs(PagerDial.spec(20, 40, 16).widthDp - 60.8f) < 0.01f)
        assertTrue(kotlin.math.abs(PagerDial.spec(2, 5, 0).widthDp - 38.0f) < 0.01f)
        assertTrue(kotlin.math.abs(PagerDial.spec(1, 2, 0).widthDp - 18.5f) < 0.01f)
    }
}
