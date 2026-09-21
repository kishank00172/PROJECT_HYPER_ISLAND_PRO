package com.hyperisland.pro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MorphJankTest {

    @Test
    fun firstCallOnlyArmsTheClock() {
        val m = MorphJankMeter()
        assertFalse(m.frame(1_000_000L, 0f))
        assertEquals("frames=0", m.summary())
    }

    @Test
    fun aSmoothFrameIsNotCountedAsSlow() {
        val m = MorphJankMeter()
        m.frame(0L, 0f)
        assertFalse(m.frame(16_000_000L, 0.1f))
        assertFalse(m.frame(32_000_000L, 0.2f))
        assertEquals(2L, m.frameCount)
        assertEquals(0L, m.slowCount)
        assertEquals(16L, m.worstMs)
    }

    @Test
    fun aDoubledFrameIsASlowOneAndRecordsWhenItHappened() {
        val m = MorphJankMeter()
        m.frame(0L, 0f)
        m.frame(16_000_000L, 0.3f)
        assertTrue(m.frame(16_000_000L + 51_000_000L, 0.92f))
        assertEquals(1L, m.slowCount)
        assertEquals(51L, m.worstMs)
        assertEquals(0.92f, m.worstAtProgress, 0.0001f)
        assertTrue(m.summary().contains("max=51ms@t=0.92"))
    }

    @Test
    fun aPausedAppIsNotAThreeSecondFrameDrop() {
        val m = MorphJankMeter()
        m.frame(0L, 0f)
        assertFalse(m.frame(5_000_000_000L, 1f))
        assertEquals(0L, m.frameCount)
    }

    @Test
    fun summaryAveragesOverRecordedFramesOnly() {
        val m = MorphJankMeter()
        m.frame(0L, 0f)
        m.frame(16_000_000L, 0.5f)
        m.frame(16_000_000L + 48_000_000L, 1f)
        assertEquals("frames=2 avg=32ms max=48ms@t=1.00 slow=1", m.summary())
    }

    /** 16 ms budget: the 24 ms cutoff must not fire at 24 exactly, or every routine frame counts as dropped. */
    @Test
    fun theSlowCutoffIsOneAndAHalfBudgets() {
        val m = MorphJankMeter()
        m.frame(0L, 0f)
        assertFalse(m.frame(24_000_000L, 0.1f))
        m.frame(24_000_000L, 0.2f)
        assertTrue(m.frame(24_000_000L + 25_000_000L, 0.3f))
    }
}
