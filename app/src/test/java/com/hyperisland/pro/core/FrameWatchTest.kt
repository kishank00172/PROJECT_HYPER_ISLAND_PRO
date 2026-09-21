package com.hyperisland.pro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arithmetic behind the line that answers "did the whole island get slower", including the bug that
 * made this class necessary: a 60 Hz "slow" threshold says nothing at 120 Hz.
 */
class FrameWatchTest {

    private fun ms(n: Double) = (n * 1_000_000).toLong()

    @Test
    fun activityOpensOneWindowAndLaterActivityOnlyExtendsIt() {
        val w = FrameWatch()
        assertTrue(w.noteActivity(1_000L))
        assertFalse(w.noteActivity(1_100L))
        assertTrue(w.running)
        assertEquals(0L, w.frames)
    }

    @Test
    fun aPerfectSixtyHertzRunIsCleanAndReadsAsSixty() {
        val w = FrameWatch(hintPeriodMs = 16L)
        w.noteActivity(0L)
        for (t in longArrayOf(0L, 16L, 32L, 48L, 64L)) w.frame(t * 1_000_000L, t)
        assertEquals(5L, w.frames)
        assertEquals(0L, w.slowFrames)
        assertEquals(16L, w.periodMs())
        assertEquals(60, FrameWatch.snapHertz(w.periodMs()))
    }

    /**
     * The whole reason the budget has to be measured: a 17 ms gap is one frame of work at 60 Hz and two
     * dropped frames at 120 Hz. Same gap, different verdict.
     */
    @Test
    fun theSlowThresholdFollowsTheFramePeriodNotAHardcodedSixteen() {
        val at120 = FrameWatch(hintPeriodMs = 8L)
        at120.noteActivity(0L)
        at120.frame(0L, 0L)
        assertTrue(at120.frame(ms(16.7), 17L))
        assertEquals(1L, at120.slowFrames)

        val at60 = FrameWatch(hintPeriodMs = 16L)
        at60.noteActivity(0L)
        at60.frame(0L, 0L)
        assertFalse(at60.frame(ms(16.7), 17L))
        assertEquals(0L, at60.slowFrames)
    }

    @Test
    fun aPausedAppIsNotReportedAsADroppedFrame() {
        val w = FrameWatch(hintPeriodMs = 16L)
        w.noteActivity(0L)
        w.frame(0L, 0L)
        w.frame(ms(5_000.0), 5_000L)
        assertEquals(2L, w.frames)
        assertEquals(0L, w.gapSumMs)
        assertEquals(0L, w.worstGapMs)
        assertEquals(0L, w.slowFrames)
        assertEquals(16L, w.periodMs())
        // A window left to itself closes: nobody called end(), and no frame extended it.
        assertTrue(w.expired(5_000L))
    }

    @Test
    fun quietClosesTheWindowAndActivityReopensIt() {
        assertFalse(FrameWatch().expired(10_000L))
        val w = FrameWatch()
        w.noteActivity(1_000L)
        assertFalse(w.expired(1_900L))
        assertTrue(w.expired(1_901L))
        w.noteActivity(1_500L)
        assertFalse(w.expired(2_400L))
        assertTrue(w.expired(2_401L))
    }

    /**
     * Gaps are whole milliseconds, so a 60 Hz panel reports 16-17 and the raw division would shout
     * "62 fps" at a tester. Snapping to a rate the display could actually run is what makes `hz=` a
     * sentence we can act on, and 0/4/6/8/11 are the cases a 120 Hz panel really produces.
     */
    @Test
    fun measuredGapsSnapToRealRefreshRates() {
        assertEquals(0, FrameWatch.snapHertz(0L))
        assertEquals(240, FrameWatch.snapHertz(4L))
        assertEquals(165, FrameWatch.snapHertz(6L))
        assertEquals(144, FrameWatch.snapHertz(7L))
        assertEquals(120, FrameWatch.snapHertz(8L))
        assertEquals(90, FrameWatch.snapHertz(11L))
        assertEquals(72, FrameWatch.snapHertz(15L))
        assertEquals(60, FrameWatch.snapHertz(16L))
        assertEquals(60, FrameWatch.snapHertz(17L))
        assertEquals(60, FrameWatch.snapHertz(23L))
    }

    /** A layout storm has no animation to blame it on, so it is counted on its own. */
    @Test
    fun layoutPassesAndOwnDrawCostLandInTheSummary() {
        val w = FrameWatch()
        w.noteActivity(0L)
        repeat(3) { w.noteLayoutPass() }
        w.noteDrawNanos(0L) // a zero-cost draw is not a sample; counting it would halve the average
        w.noteDrawNanos(2_000_000L)
        w.noteDrawNanos(4_000_000L)
        val line = w.end(10L)
        assertTrue(line, line.contains("layouts=3"))
        assertTrue(line, line.contains("draw=3.0ms/2"))
        assertTrue(line, line.contains("window=10ms"))
        assertFalse(w.running)
        assertFalse(w.measuring)
        assertEquals(16L, w.periodMs())
    }

    /**
     * The hole in the first draft: a frame extended the window *and* re-posted the callback, so the window
     * could never close and the log would either stay silent or fill up. Closing must depend on activity.
     */
    @Test
    fun framesAloneNeverKeepTheWindowOpen() {
        val w = FrameWatch(hintPeriodMs = 16L)
        w.noteActivity(0L)
        for (i in 0..30) w.frame(i * 16_000_000L, (i * 16).toLong())
        assertTrue(w.expired(901L))
        val line = w.end(901L)
        assertTrue(line, line.contains("window=901ms"))
        assertFalse(w.running)
        // The next gesture gets a fresh window rather than a stuck one.
        assertTrue(w.noteActivity(2_000L))
    }

    /**
     * A measured period has to outlive the window it was measured in, or the first morph after a quiet
     * minute gets judged against the assumed 16 ms and every healthy 120 Hz frame reads as clean while a
     * 60 Hz one reads as broken.
     */
    @Test
    fun theBudgetSurvivesQuietWindows() {
        val w = FrameWatch(hintPeriodMs = 16L)
        w.noteActivity(0L)
        for (t in longArrayOf(0L, 8L, 16L, 24L)) w.frame(t * 1_000_000L, t)
        assertEquals(8L, w.periodMs())
        w.end(1_000L)
        assertEquals(8L, w.periodMs())
        w.noteActivity(2_000L)
        w.frame(2_000_000_000L, 2_000L)
        assertTrue(w.frame(2_016_000_000L, 2_016L))
    }
}
