package com.hyperisland.pro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val total = TraceLog.PERSISTED_LINES + 40
        repeat(total) { TraceLog.line("STAGE", "s$it") }
        val saved = TraceLog.persisted()
        assertEquals(TraceLog.PERSISTED_LINES, saved.lines().size)
        // The boundary, not the absence of a substring: this used to assert the tail did not contain "s39",
        // which passed only while the numbers stayed under three digits - once the persisted window grew,
        // the tail legitimately holds s390 through s399 and the substring is there. Saying "the first kept
        // line is the 41st written" states the actual rule and cannot rot when the constants change.
        assertEquals("s40", saved.lines().first().substringAfter("[STAGE] "))
        assertTrue(saved, saved.endsWith("s${total - 1}"))
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


    @Test fun `a run of identical lines reads as one line with a count`() {
        val lines = listOf(
            "12:00:01 #10 [STALL] 30ms main thread in getRoot",
            "12:00:02 #11 [STALL] 30ms main thread in getRoot",
            "12:00:03 #12 [STALL] 30ms main thread in getRoot",
            "12:00:04 #13 [MORPH] start pill->card",
            "12:00:05 #14 [MORPH] start pill->card"
        )
        val collapsed = TraceLog.collapseRuns(lines)
        assertEquals(2, collapsed.size)
        // The FIRST line of a run keeps its own stamp and sequence number: time order survives the merge, so a
        // collapsed log can still be lined up against a morph that started at #13.
        assertTrue(collapsed[0], collapsed[0].startsWith("12:00:01 #10"))
        assertTrue(collapsed[0], collapsed[0].endsWith("(x3)"))
        assertTrue(collapsed[1], collapsed[1].endsWith("(x2)"))
        assertEquals(1, TraceLog.collapseRuns(listOf(lines[0])).size)
        assertEquals(0, TraceLog.collapseRuns(emptyList()).size)
    }

    @Test fun `hiding noise is a tag decision and can never swallow an event`() {
        assertTrue(TraceLog.isChatter("12:00:01 #1 [STALL] 30ms in getRoot"))
        assertTrue(TraceLog.isChatter("12:00:01 #1 [FRAME] window=120fps max=8ms"))
        assertFalse(TraceLog.isChatter("12:00:01 #1 [MORPH] start pill->card"))
        assertFalse(TraceLog.isChatter("12:00:01 #1 [INGEST] show com.instagram.android 'You' hiii"))
        // A line that merely mentions the word is an event. Hiding an event is exactly the thing a log viewer
        // is not allowed to do, which is why this is a tag test and not a text filter.
        assertFalse(TraceLog.isChatter("12:00:01 #1 [ACTION] the FRAME was dropped during the morph"))
        assertFalse(TraceLog.isChatter("a line with no brackets at all"))
        assertEquals("STALL", TraceLog.tagOf("12:00:01 #1 [STALL] x"))
        assertEquals("", TraceLog.tagOf("no tag"))
    }

    @Test fun `reading the tail does not consume it`() {
        repeat(25) { TraceLog.line("TOUCH", "line$it") }
        val last = TraceLog.tailLines(5)
        assertEquals(5, last.size)
        assertEquals("line21", last.first().substringAfter("] "))
        assertEquals("line25", last.last().substringAfter("] "))
        assertEquals(25, TraceLog.size())
        assertEquals(25, TraceLog.tailLines(100).size)
    }
}
