package com.hyperisland.pro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The trace buffer has to be trustworthy, because it is what replaces "probably this happened".
 * Rules that matter: it never grows unbounded, order is chronological, and a restart does not lose the
 * tail (the failures we chase happen in a service an OEM is free to kill).
 */
class TraceLogTest {

    @Before
    fun reset() {
        TraceLog.clear()
        TraceLog.sink = {}
        TraceLog.onLine = {}
        TraceLog.restore(null)
    }

    @Test
    fun linesAreStampedSequencedAndTagged() {
        var seen = 0
        TraceLog.sink = { seen++ }
        TraceLog.line("TOUCH", "swipe dropped: swap in flight")
        val text = TraceLog.snapshot()
        assertEquals(1, TraceLog.size())
        assertEquals(1, seen)
        assertTrue(text, text.contains("[TOUCH] swipe dropped: swap in flight"))
        assertTrue(text, Regex("""^\d\d:\d\d:\d\d\.\d\d\d #1 \[TOUCH] """).containsMatchIn(text))
    }

    @Test
    fun orderIsOldestFirstAndTailIsNewestFirstSlice() {
        repeat(5) { TraceLog.line("RING", "line$it") }
        assertEquals("line0", TraceLog.snapshot().lineSequence().first().substringAfter("[RING] "))
        assertEquals("line4", TraceLog.snapshot().lineSequence().last().substringAfter("[RING] "))
        assertEquals(
            listOf("line3", "line4"),
            TraceLog.tail(2).lines().map { it.substringAfter("[RING] ") }
        )
    }

    @Test
    fun aFloodCannotGrowTheBuffer() {
        repeat(TraceLog.MAX_LINES + 250) { TraceLog.line("INGEST", "flood$it") }
        assertEquals(TraceLog.MAX_LINES, TraceLog.size())
        val first = TraceLog.snapshot().lineSequence().first()
        assertEquals("flood250", first.substringAfter("[INGEST] "))
        assertEquals("flood${TraceLog.MAX_LINES + 249}", TraceLog.snapshot().lineSequence().last().substringAfter("[INGEST] "))
    }

    @Test
    fun persistedTailIsBoundedAndKeepsTheNewest() {
        repeat(TraceLog.PERSISTED_LINES + 40) { TraceLog.line("STAGE", "s$it") }
        val saved = TraceLog.persisted()
        assertEquals(TraceLog.PERSISTED_LINES, saved.lines().size)
        assertTrue(saved, !saved.contains("s39"))
        assertTrue(saved, saved.endsWith("s${TraceLog.PERSISTED_LINES + 39}"))
    }

    @Test
    fun restoreSeedsOnlyAnEmptyBuffer() {
        val saved = buildString {
            repeat(3) { append("00:00:0").append(it).append(" #1 [TOUCH] old").append(it).append("\n") }
        }
        TraceLog.restore(saved)
        assertEquals(3, TraceLog.size())
        TraceLog.restore("00:00:00 #9 [TOUCH] second call should be ignored\n")
        assertEquals(3, TraceLog.size())
        TraceLog.line("TOUCH", "live")
        assertEquals(4, TraceLog.size())
        assertEquals("live", TraceLog.snapshot().lineSequence().last().substringAfter("[TOUCH] "))
    }
}
