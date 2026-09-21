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

    @Test
    fun aStallLineNamesTheCodeTheMainThreadWasCaughtIn() {
        val frames = listOf(
            StackTraceElement("a.b.ViewRootImpl", "performLayout", "ViewRootImpl.java", 2100),
            StackTraceElement("android.os.Handler", "handleMessage", "Handler.java", 100),
        )
        val line = stallLine(448L, "RUNNABLE", frames)
        assertTrue(line, line.startsWith("448ms RUNNABLE :: performLayout@ViewRootImpl.java:2100 <- handleMessage@Handler.java:100"))
    }

    @Test
    fun anEmptyStackStillPrintsSomethingUseful() {
        assertEquals("12ms BLOCKED :: ?", stallLine(12L, "BLOCKED", emptyList()))
    }

    @Test
    fun gcDeltaSeparatesABlockingCollectionFromQuietWork() {
        val a = GcSnapshot(gcCount = 40, gcMs = 900, blockingCount = 1, blockingMs = 12, allocated = 5_000_000)
        val b = GcSnapshot(gcCount = 42, gcMs = 930, blockingCount = 3, blockingMs = 55, allocated = 5_000_000 + 3_145_728)
        assertEquals("+2/30 blocking=+2/43 alloc=3.0MB", b.deltaText(a))
        assertEquals("none", b.deltaText(b))
    }

    @Test
    fun theLineCarriesOurOwnFrameEvenWhenItIsBuriedUnderTheFramework() {
        val frames = listOf(
            StackTraceElement("android.graphics.Paint", "nGetFontMetricsInt", "Paint.java", -2),
            StackTraceElement("android.text.TextLine", "getMetrics", "TextLine.java", 372),
            StackTraceElement("com.hyperisland.pro.services.HyperAccessibilityService", "updateNotificationContent", "HyperAccessibilityService.kt", 1184),
        )
        val line = stallLine(448L, "RUNNABLE", frames)
        assertTrue(line, line.contains("| us: updateNotificationContent@HyperAccessibilityService.kt:1184"))
    }

    @Test
    fun aBlockThatIsOnlyTheLooperWaitingIsCountedAndNotPrinted() {
        val poller = listOf(StackTraceElement("android.os.MessageQueue", "nativePollOnce", "MessageQueue.java", -2))
        assertFalse(stallShouldPrint(40L, poller))
        assertTrue(stallShouldPrint(900L, poller))
        assertTrue(stallShouldPrint(40L, listOf(StackTraceElement("a.b", "layout", "L.java", 3))))
        assertTrue(stallShouldPrint(40L, emptyList()))
    }

    @Test
    fun theSampleReachesSixFramesBecauseThreeNeverReachedTheCaller() {
        val many = (1..9).map { StackTraceElement("a.b", "m$it", "F.java", it) }
        val line = stallLine(30L, "RUNNABLE", many)
        assertTrue(line, line.contains("m6@F.java:6") && !line.contains("m7@F.java:7"))
    }
}
