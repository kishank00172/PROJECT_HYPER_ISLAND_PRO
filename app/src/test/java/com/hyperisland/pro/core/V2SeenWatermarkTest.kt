package com.hyperisland.pro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * b1501 / Round-I P1-10: the seen-watermark, proven on the JVM. The six device chains (A-F) plus the
 * edge cases from the plan; persistence round-trips; the NEVER-touch rules (no resurrection).
 */
class V2SeenWatermarkTest {

    private val NOW = 1_000_000_000_000L + 100_000L

    private fun stamp(ts: Long, text: String, sender: String = "them") = V2SeenWatermark.MsgStamp(ts, text, sender)

    private fun admit(m: V2SeenWatermark, key: String, msgs: List<V2SeenWatermark.MsgStamp>) {
        val newest = msgs.maxBy { it.tsMs }
        val csv = msgs.filter { it.tsMs == newest.tsMs }.joinToString(",") { V2SeenWatermark.fp(it.tsMs, it.text, it.sender) }
        m.markSeen(V2SeenWatermark.SeenStamp(key, "pkg", newest.tsMs, csv, 1, 0L, ""), NOW)
    }

    private fun judge(m: V2SeenWatermark, key: String, msgs: List<V2SeenWatermark.MsgStamp>, post: Long = NOW): V2SeenWatermark.JudgeResult =
        m.judgeMessaging(key, "pkg", msgs, post, NOW + 60_000L)

    // Chain A (17:53): wipe -> identical repost 1.4 s later.
    @Test
    fun chainA_identicalRepostEchoes() {
        val m = V2SeenWatermark()
        admit(m, "k", listOf(stamp(NOW - 5_000, "hi")))
        assertEquals(V2SeenWatermark.Verdict.ECHO, judge(m, "k", listOf(stamp(NOW - 5_000, "hi"))).verdict)
    }

    // Chain B (18:40): peel one layer down (older text, unread 1->2, postTime=NOW) must ECHO;
    // a genuinely new message in a dead chat must appear INSTANTLY (R3).
    @Test
    fun chainB_peelEchoesNewRing() {
        val m = V2SeenWatermark()
        admit(m, "k", listOf(stamp(NOW - 2_000, "about file"), stamp(NOW, "feedback send")))
        val peel = listOf(stamp(NOW - 5_000, "older still"), stamp(NOW - 2_000, "about file"))
        assertEquals(V2SeenWatermark.Verdict.ECHO, judge(m, "k", peel, post = NOW + 20_000).verdict)
        val realNew = listOf(stamp(NOW + 4_000, "actually new"), stamp(NOW, "feedback send"))
        val r = judge(m, "k", realNew, post = NOW + 20_000)
        assertEquals(V2SeenWatermark.Verdict.NEW, r.verdict)
        assertEquals("actually new", r.newestUnseenText)
        assertEquals(1, r.unseenCount)
    }

    // Chain C (19:12): process death -> rebind -> volley with ORIGINAL postTimes (ages 1300-1450 s).
    @Test
    fun chainC_persistedWatermarkKillsVolley() {
        val m = V2SeenWatermark()
        admit(m, "k", listOf(stamp(NOW - 60_000, "read it")))
        val revived = V2SeenWatermark()
        assertTrue(revived.decodeInto(m.encode()))
        assertEquals(V2SeenWatermark.Verdict.ECHO,
            revived.judgeMessaging("k", "pkg", listOf(stamp(NOW - 60_000, "read it")), NOW - 60_000, NOW + 365_000).verdict)
        assertEquals(V2SeenWatermark.Verdict.NEW,
            revived.judgeMessaging("k", "pkg", listOf(stamp(NOW - 30_000, "came while dead")), NOW - 30_000, NOW + 365_000).verdict)
    }

    // Boundary: same ts, different fp -> NEW (and only that one).
    @Test
    fun chainD_boundarySameTs() {
        val m = V2SeenWatermark()
        admit(m, "k", listOf(stamp(NOW, "hi")))
        assertEquals(V2SeenWatermark.Verdict.NEW,
            judge(m, "k", listOf(stamp(NOW, "hi"), stamp(NOW, "yo"))).verdict)
        assertEquals(V2SeenWatermark.Verdict.ECHO, judge(m, "k", listOf(stamp(NOW, "hi"))).verdict)
    }

    // Untrusted clocks: >=3 distinct messages stamped exactly at postTime -> UNKNOWN -> legacy.
    @Test
    fun chainE_untrustedTimestampsFallToLegacy() {
        val m = V2SeenWatermark()
        val msgs = (0 until 4).map { stamp(NOW, "msg$it") }
        admit(m, "k", msgs)
        assertEquals(V2SeenWatermark.Verdict.UNKNOWN, judge(m, "k", msgs, post = NOW).verdict)
    }

    // NONMSG: first NEW_FALLBACK, progress-only ECHO, text change NEW again.
    @Test
    fun chainF_nonMessagingProgress() {
        val m = V2SeenWatermark()
        assertEquals(V2SeenWatermark.Verdict.NEW_FALLBACK,
            m.judgeNonMessaging("k", NOW, V2SeenWatermark.nonMsgFp("App", "Downloading 1%", ""), false).verdict)
        m.markSeen(V2SeenWatermark.SeenStamp("k", "pkg", NOW, "", 2, NOW, V2SeenWatermark.nonMsgFp("App", "Downloading 1%", "")), NOW)
        assertEquals(V2SeenWatermark.Verdict.ECHO,
            m.judgeNonMessaging("k", NOW, V2SeenWatermark.nonMsgFp("App", "Downloading 99%", ""), false).verdict)
        assertEquals(V2SeenWatermark.Verdict.NEW_FALLBACK,
            m.judgeNonMessaging("k", NOW, V2SeenWatermark.nonMsgFp("App", "Download complete", ""), false).verdict)
        // live page: any update refreshes in place (fallback, NOT echo)
        assertEquals(V2SeenWatermark.Verdict.NEW_FALLBACK,
            m.judgeNonMessaging("k", NOW, V2SeenWatermark.nonMsgFp("App", "Downloading 99%", ""), true).verdict)
    }

    @Test
    fun equalTsDifferentFpMeansNewNotEcho() {
        assertEquals(1, breakEvenHelper("hi", "yo"))
    }

    private fun breakEvenHelper(a: String, b: String): Int {
        val m = V2SeenWatermark()
        admit(m, "k", listOf(stamp(NOW, a)))
        return if (judge(m, "k", listOf(stamp(NOW, b))).verdict == V2SeenWatermark.Verdict.NEW) 1 else 0
    }

    @Test
    fun futureTimestampClampsAndDistrusts() {
        val m = V2SeenWatermark()
        val msgs = listOf(stamp(NOW + 10L * 60 * 1000, "from the future"))
        val revivedNow = NOW + 60_000L
        assertEquals(V2SeenWatermark.Verdict.UNKNOWN,
            m.judgeMessaging("k", "pkg", msgs, revivedNow, revivedNow).verdict)
        assertEquals(V2SeenWatermark.Trust.UNTRUSTED, m.trustOf("pkg"))
    }

    @Test
    fun sameTextTwoSendersTwoTimestampsDistrust() {
        val m = V2SeenWatermark()
        val msgs = listOf(stamp(NOW, "hi", "a"), stamp(NOW - 4_000, "hi", "a"))
        m.judgeMessaging("k", "pkg2", msgs, NOW, NOW + 1_000)
        assertEquals(V2SeenWatermark.Trust.UNTRUSTED, m.trustOf("pkg2"))
    }

    @Test
    fun secondsInputNormalizesMillis() {
        assertEquals(1_725_000_000_000L, V2SeenWatermark.normalizeTsMs(1_725_000_000L))
        assertEquals(NOW, V2SeenWatermark.normalizeTsMs(NOW))
    }

    @Test
    fun connectBaselineNeverRegressionsH() {
        val m = V2SeenWatermark()
        admit(m, "k", listOf(stamp(NOW, "current")))
        val (baselined, withState, newWhileDead) = m.connectBaseline(
            listOf(
                V2SeenWatermark.SeenStamp("k", "pkg", NOW - 3_600_000, ""),
                V2SeenWatermark.SeenStamp("fresh", "pkg", NOW - 1_000, "")
            ), NOW)
        assertEquals(1, baselined); assertEquals(1, withState); assertEquals(0, newWhileDead)
        // the baselined fresh conv ECHOes; the old ts does NOT pull H down
        assertEquals(V2SeenWatermark.Verdict.ECHO, judge(m, "fresh", listOf(stamp(NOW - 1_000, "anything"))).verdict)
        assertEquals(V2SeenWatermark.Verdict.ECHO, judge(m, "k", listOf(stamp(NOW - 3_600_000, "ancient"))).verdict)
    }

    @Test
    fun pruneNeverEvictsActiveKeysAndDropsAged() {
        val m = V2SeenWatermark()
        m.markSeen(V2SeenWatermark.SeenStamp("alive", "pkg", NOW, ""), NOW - 31L * 24 * 3600 * 1000)
        m.markSeen(V2SeenWatermark.SeenStamp("dead", "pkg", NOW, ""), NOW - 31L * 24 * 3600 * 1000)
        m.prune(setOf("alive"), NOW)
        assertTrue(m.stateOf("alive") != null)
        assertTrue(m.stateOf("dead") == null)
    }

    @Test
    fun corruptBlobStartsEmptyAndReportsFalse() {
        val m = V2SeenWatermark()
        assertFalse(m.decodeInto(byteArrayOf(9, 9, 9, 9)))
        assertEquals(0, m.size())
    }

    @Test
    fun persistenceRoundTripIsIdentity() {
        val m = V2SeenWatermark()
        m.markSeen(V2SeenWatermark.SeenStamp("k1", "pkg", NOW, "aa,bb"), NOW)
        m.markSeen(V2SeenWatermark.SeenStamp("k2", "pkg", NOW - 5_000, "", 2, NOW - 5_000, V2SeenWatermark.nonMsgFp("t", "x", "")), NOW + 1)
        val copy = V2SeenWatermark()
        assertTrue(copy.decodeInto(m.encode()))
        assertEquals(2, copy.size())
        assertEquals(m.stateOf("k1")?.h, copy.stateOf("k1")?.h)
        assertEquals(m.stateOf("k1")?.boundary, copy.stateOf("k1")?.boundary)
        assertEquals(m.stateOf("k2")?.nonTextFp, copy.stateOf("k2")?.nonTextFp)
    }


    // b1502 survivor fix: HIS clear-all speaks for the PACKAGE - an unseeded conv (seed missed, as
    // telegram's extract-null removal on his 16:17 log) stays dead under the package clock.
    @Test
    fun pkgClockEchoesUnseededConv() {
        val m = V2SeenWatermark()
        m.markSeen(V2SeenWatermark.SeenStamp("other", "apkg", NOW, "", 1, 0L, "", advancePkg = true), NOW)
        // never-seeded chat re-fires with an older message clock -> ECHO (his demand)
        val r = m.judgeMessaging("apkg|shortcut|ndid_99", "apkg", listOf(stamp(NOW - 3_000, "peeled old")), NOW - 3_000, NOW + 1_000)
        assertEquals(V2SeenWatermark.Verdict.ECHO, r.verdict)
        // genuinely new arrival (ts > package clock) -> NEW instantly
        assertEquals(V2SeenWatermark.Verdict.NEW,
            m.judgeMessaging("apkg|shortcut|ndid_99", "apkg", listOf(stamp(NOW + 9_000, "fresh")), NOW + 9_000, NOW + 60_000).verdict)
    }

    @Test
    fun noClockConvsJudgeByWhen() {
        val m = V2SeenWatermark()
        // app post has NO per-message ts; he cleared the shelf (package clock advanced)
        m.markSeen(V2SeenWatermark.SeenStamp("c", "tg", NOW, "", 1, 0L, "", advancePkg = true), NOW)
        val r = m.judgeMessaging("tg|shortcut|ndid_7", "tg", listOf(stamp(0L, "channel post")), NOW, NOW + 1_000, whenMs = NOW - 30_000)
        assertEquals(V2SeenWatermark.Verdict.ECHO, r.verdict)
        assertEquals("pkg-noclock", r.cmp)
        // a later post (when > pkgH) is genuinely new even without message clocks
        assertEquals(V2SeenWatermark.Verdict.UNKNOWN,
            m.judgeMessaging("tg|shortcut|ndid_7", "tg", listOf(stamp(0L, "new channel post")), NOW + 60_000, NOW + 60_000, whenMs = NOW + 60_000).verdict)
    }

    @Test
    fun pkgClockSurvivesBlobRoundTrip() {
        val m = V2SeenWatermark()
        m.markSeen(V2SeenWatermark.SeenStamp("c", "tg", NOW, "", advancePkg = true), NOW)
        val copy = V2SeenWatermark()
        assertTrue(copy.decodeInto(m.encode()))
        assertEquals(NOW, copy.pkgHighOf("tg"))
        assertEquals(V2SeenWatermark.Verdict.ECHO,
            copy.judgeMessaging("tg|shortcut|x", "tg", listOf(stamp(NOW - 1_000, "old")), NOW, NOW + 1_000).verdict)
    }

    @Test
    fun boundaryCapsAtEightFingerprints() {
        val m = V2SeenWatermark()
        val msgs = (0 until 12).map { stamp(NOW, "m$it") }
        admit(m, "k", msgs)
        assertTrue((m.stateOf("k")?.boundary?.size ?: 99) <= V2SeenWatermark.MAX_BOUNDARY)
    }

    @Test
    fun noStateFirstArrivalIsNewButConnectBaselineWasTheEarliestDefense() {
        val m = V2SeenWatermark()
        m.connectBaseline(listOf(V2SeenWatermark.SeenStamp("k", "pkg", NOW, "")), NOW)
        assertEquals(V2SeenWatermark.Verdict.ECHO, judge(m, "k", listOf(stamp(NOW, "arrived at install"))).verdict)
    }
}
