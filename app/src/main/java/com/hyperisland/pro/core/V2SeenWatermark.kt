package com.hyperisland.pro.core

/**
 * b1501 / Round-I P1 (Claude consultant plan, owner-approved): the seen-watermark engine.
 *
 * THE PROBLEM IT ENDS (three chains from his device TraceLogs):
 *  A) shade open = he read it; the app re-posts the SAME notification 1.4 s later (17:53).
 *  B) clear-all, then the app re-posts the SAME conversation peeled one layer deep: older text,
 *     unread bumped 1->2, postTime rewritten to NOW (18:40). Content fingerprints cannot catch it
 *     because the content MUTATED.
 *  C) MIUI kills the process; on re-bind Android re-fires every active StatusBarNotification with
 *     ORIGINAL postTimes and 1300-1450 s of age (19:12) - 8 read chats re-inflated in 0.1 s.
 *
 * THE DECIDING SIGNAL (why it is decidable at all): a peeled layer is built of OLDER messages, so
 * its per-MESSAGE timestamps are at-or-below the watermark even when the text was never shown.
 * Text is a lie apps can rewrite; MessagingStyle.Message.time, while alive, is not.
 *
 * Pure Kotlin, zero Android imports: JVM-unit-testable. Persistence (binary blob) lives here too
 * (java.io streams only) so the on-device self-test exercises the REAL encode/decode path.
 *
 * Boundaries:
 *  - Never a mechanic of postTime (Chain B rewrites it; it is evidence, never input).
 *  - Unread-count monotonicity rejected (the peel BUMPS unread).
 *  - R3 (his pick, confirmed by Claude): dead until a NEW message; judged per MESSAGE timestamp,
 *    never by a time window. Owner priority: zero false resurrections > zero missed real messages.
 */
class V2SeenWatermark {

    enum class Trust { UNKNOWN, TRUSTED, UNTRUSTED }
    enum class Verdict { ECHO, NEW, UNKNOWN, NEW_FALLBACK }
    enum class Mode(val slug: String) {
        WATERMARK_LEGACY("watermark+legacy"), WATERMARK_ONLY("watermark-only"), LEGACY_ONLY("legacy-only");
        companion object {
            fun from(slug: String?): Mode = values().firstOrNull { it.slug == slug } ?: WATERMARK_LEGACY
        }
    }

    /** One conversation's persisted seen-state. kind: 1 = MESSAGING, 2 = NONMSG. */
    data class ConState(
        val key: String,
        var h: Long = 0L,                       // seen high-water mark (message ts, ms)
        var boundary: MutableList<String> = mutableListOf(),  // fps of messages at ts == H (<= 8)
        var updatedAt: Long = 0L,
        var kind: Int = 1,
        var nonWhen: Long = 0L,                 // NONMSG: Notification.when
        var nonTextFp: String = ""              // NONMSG: fp of title+text+subText (-digits)
    )

    /** One message's judging triple: per-message timestamp + text + sender (MessagingStyle order). */
    data class MsgStamp(val tsMs: Long, val text: String, val sender: String)

    data class JudgeResult(
        val verdict: Verdict,
        val newestTs: Long = 0L,         // for NEW: ts of the newest UNSEEN message; for marks else
        val unseenCount: Int = 0,
        val newestUnseenText: String = "",
        val boundaryCsv: String = "",    // fps at ts == newestTs (capped 8), CSV, ready to persist
        val cmp: String = "",            // "lt" | "eq" | "gt" (how the newest related to H)
        val trust: String = ""
    )

    data class SeenStamp(val convKey: String, val pkg: String, val newestTs: Long, val fpsCsv: String,
                         val kind: Int = 1, val nonWhen: Long = 0L, val nonTextFp: String = "",
                         /** His direct actions (swipe/clear-all/shade-wipe/tap) also speak for the PACKAGE:
                          *  convs a seed never captured still stay dead under the package clock, while a
                          *  genuinely new message (ts > pkgH) passes instantly. */
                         val advancePkg: Boolean = false)

    companion object {
        const val SNAPSHOT_VERSION = 2
        const val MAX_CONVS = 500
        const val TTL_MS = 30L * 24 * 60 * 60 * 1000
        const val MAX_BOUNDARY = 8
        const val FUTURE_CLAMP_MS = 5L * 60 * 1000
        /** MessagingStyle times below this are seconds, not millis (1e11 ms ~ 1973; 1e11 s ~ 5138). */
        const val SECONDS_IS_MILLIS_BELOW = 100_000_000_000L

        fun normalizeTsMs(ts: Long): Long = when {
            ts in 1 until SECONDS_IS_MILLIS_BELOW -> ts * 1000L
            else -> ts
        }

        /** fp = hash(ts, text, sender). 64-bit hex keeps collisions ~impossible at ring scale. */
        fun fp(tsMs: Long, text: String, sender: String): String {
            var h = -3750763034362895579L   // FNV-1a 64-bit offset basis (signed)
            fun bytes(s: String) { for (c in s) { h = h xor c.code.toLong(); h *= 1099511628211L } }
            fun num(v: Long) { for (i in 0 until 8) { h = h xor ((v shr (i * 8)) and 0xff); h *= 1099511628211L } }
            num(tsMs); bytes(text); num(0xA5A5); bytes(sender)
            return java.lang.Long.toHexString(h)
        }

        /** NONMSG fingerprint: title+text+subText, digits/whitespace stripped (progress % is noise:
         *  "42%" and "43%" are the SAME dialog; "Download complete" differs in letters and stays NEW). */
        fun nonMsgFp(title: String, text: String, sub: String): String =
            fp(0L, (title + "|" + text + "|" + sub).replace(Regex("""[\s0-9]+"""), ""), "")
    }

    // LRU by key access; eviction deliberately skips still-active keys (evicting those resurrects).
    private val convs = object : LinkedHashMap<String, ConState>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ConState>?): Boolean = false // manual pruning only
    }
    private val trust = HashMap<String, Trust>()
    private val pkgH = HashMap<String, Long>()

    fun pkgHighOf(pkg: String): Long = pkgH[pkg] ?: 0L

    var echoCount = 0; var newCount = 0; var unknownCount = 0; var conflictCount = 0
    fun legacyConflict(pkg: String, key: String) { conflictCount++ }

    fun trustOf(pkg: String): Trust = trust[pkg] ?: Trust.UNKNOWN
    fun stateOf(key: String): ConState? = synchronized(convs) { convs[key] }

    /** P1-3 + trust test (P1-2): per-package timestamp honesty, sticky UNTRUSTED. */
    private fun trustGate(pkg: String, msgs: List<MsgStamp>, postTimeMs: Long, nowMs: Long): Trust {
        val current = trust[pkg] ?: Trust.UNKNOWN
        if (current == Trust.UNTRUSTED) return Trust.UNTRUSTED
        var bad = false
        val byContent = HashMap<String, Long>()
        var sameAsPost = 0
        for (m in msgs) {
            if (m.tsMs > nowMs + FUTURE_CLAMP_MS) bad = true
            val ck = m.text + "" + m.sender
            val prev = byContent.put(ck, m.tsMs)
            if (prev != null && prev != m.tsMs) bad = true
            if (m.tsMs == postTimeMs) sameAsPost++
        }
        if (sameAsPost >= 3) bad = true
        if (bad) { trust[pkg] = Trust.UNTRUSTED; return Trust.UNTRUSTED }
        if (current == Trust.UNKNOWN && msgs.isNotEmpty()) trust[pkg] = Trust.TRUSTED
        return trust[pkg] ?: Trust.UNKNOWN
    }

    /** P1-3 MESSAGING path garlic bread. msgs MUST be raw ts (normalizeTsMs applied by caller for display;
     *  here we normalize defensively - idempotent). Returns metrics for the proof line. */
    fun judgeMessaging(key: String, pkg: String, msgs: List<MsgStamp>, postTimeMs: Long, nowMs: Long, whenMs: Long = 0L): JudgeResult {
        if (msgs.isEmpty()) { unknownCount++; return JudgeResult(Verdict.UNKNOWN, trust = trustOf(pkg).name) }
        val norm = msgs.filter { it.tsMs > 0L }.map { it.copy(tsMs = normalizeTsMs(it.tsMs)) }
        // Telegram MODS (his device: ndid_* chats) post with ZERO per-message clocks - judge by the
        // notification's own `when` against the conversation/package clock (his fix demand: nothing
        // returns after his clear, but a real new message posts when > pkgH and still appears).
        if (norm.isEmpty()) {
            val t = trustOf(pkg)
            if (whenMs > 0L) {
                val stNoClock = synchronized(convs) { convs[key] }
                if (stNoClock != null && stNoClock.h >= whenMs) { echoCount++; return JudgeResult(Verdict.ECHO, stNoClock.h, 0, "", "", "noclock", t.name) }
                if (stNoClock == null && (pkgH[pkg] ?: 0L) >= whenMs) { echoCount++; return JudgeResult(Verdict.ECHO, pkgH[pkg] ?: 0L, 0, "", "", "pkg-noclock", t.name) }
            }
            unknownCount++; return JudgeResult(Verdict.UNKNOWN, trust = t.name)
        }
        val t = trustGate(pkg, norm, normalizeTsMs(postTimeMs), nowMs)
        if (t == Trust.UNTRUSTED) {
            unknownCount++; return JudgeResult(Verdict.UNKNOWN, trust = t.name)
        }
        val st = synchronized(convs) { convs[key] }
        if (st == null) {
            // Unseeded conv: the PACKAGE clock (set only by HIS actions) decides old-vs-new.
            val ph = pkgH[pkg] ?: 0L
            if (ph > 0L) {
                val unseen = norm.filter { it.tsMs > ph }
                if (unseen.isEmpty()) { echoCount++; return JudgeResult(Verdict.ECHO, ph, 0, "", "", "pkg-lt", t.name) }
                val newest = unseen.maxBy { it.tsMs }!!
                newCount++
                return JudgeResult(Verdict.NEW, newest.tsMs, unseen.size, newest.text, boundaryFor(norm, newest.tsMs), "pkg-gt", t.name)
            }
            val newest = norm.maxBy { it.tsMs }!!
            newCount++
            return JudgeResult(Verdict.NEW, newest.tsMs, norm.count { it.tsMs == newest.tsMs },
                newest.text, boundaryFor(norm, newest.tsMs), "gt", t.name)
        }
        // ts==H gate: an EMPTY boundary means a coarse stamp (connect baseline / Notification.when
        // fallback) - we only know "everything <= H is seen". A NON-empty boundary is the precise
        // same-millimeter disambiguation the chain-D test proves.
        val unseen = norm.filter { m ->
            !(m.tsMs < st.h || (m.tsMs == st.h && (st.boundary.isEmpty() || fp(m.tsMs, m.text, m.sender) in st.boundary)))
        }
        if (unseen.isEmpty()) {
            echoCount++
            return JudgeResult(Verdict.ECHO, st.h, 0, "", "", "lt", t.name)
        }
        val newest = unseen.maxBy { it.tsMs }!!
        newCount++
        val cmp = if (newest.tsMs > st.h) "gt" else "eq"
        return JudgeResult(Verdict.NEW, newest.tsMs, unseen.size, newest.text, boundaryFor(norm, newest.tsMs), cmp, t.name)
    }

    private fun boundaryFor(msgs: List<MsgStamp>, ts: Long): String =
        msgs.filter { it.tsMs == ts }.take(MAX_BOUNDARY).joinToString(",") { fp(it.tsMs, it.text, it.sender) }

    /** P1-3 NONMSG fallback: echo iff seen-state exists, `when` and textFp unchanged, and no live page. */
    fun judgeNonMessaging(key: String, whenMs: Long, textFp: String, hasLivePage: Boolean): JudgeResult {
        val st = synchronized(convs) { convs[key] }
        if (st != null && st.kind == 2 && st.nonWhen == whenMs && st.nonTextFp == textFp && !hasLivePage) {
            echoCount++
            return JudgeResult(Verdict.ECHO, whenMs, 0, "", "", "eq")
        }
        return JudgeResult(Verdict.NEW_FALLBACK, whenMs, 1)
    }

    /** P1-4a admission: island actually SHOWED these messages - advance. Never advances for unseen work. */
    fun markAdmitted(key: String, pkg: String, newestTs: Long, fpsCsv: String, kind: Int, nonWhen: Long, nonTextFp: String, nowMs: Long) {
        markSeen(SeenStamp(key, pkg, newestTs, fpsCsv, kind, nonWhen, nonTextFp), nowMs)
    }

    /** P1-4 b/c/d + b1501 wipe + tap + user-removal marks. H never regresses; boundary REPLACES at new H. */
    fun markSeen(stamp: SeenStamp, nowMs: Long) {
        synchronized(convs) {
            val st = convs.getOrPut(stamp.convKey) { ConState(stamp.convKey) }
            val ts = normalizeTsMs(stamp.newestTs)
            if (stamp.kind == 2) {
                st.kind = 2; st.nonWhen = stamp.nonWhen; st.nonTextFp = stamp.nonTextFp
                if (stamp.nonWhen > st.h) st.h = stamp.nonWhen
            } else {
                st.kind = 1
                if (ts > st.h || st.h == 0L) {
                    st.h = ts
                    st.boundary = stamp.fpsCsv.split(',').filter { it.isNotBlank() }.take(MAX_BOUNDARY).toMutableList()
                } else if (ts == st.h) {
                    val add = stamp.fpsCsv.split(',').filter { it.isNotBlank() }
                    st.boundary = (st.boundary + add).distinct().takeLast(MAX_BOUNDARY).toMutableList()
                }
            }
            st.updatedAt = nowMs
        }
        if (stamp.advancePkg) {
            val t = normalizeTsMs(stamp.newestTs)
            if (stamp.kind == 2 && stamp.nonWhen > 0L) pkgH[stamp.pkg] = maxOf(pkgH[stamp.pkg] ?: 0L, stamp.nonWhen)
            else if (t > 0L) pkgH[stamp.pkg] = maxOf(pkgH[stamp.pkg] ?: 0L, t)
        }
    }

    fun markAllSeen(stamps: List<SeenStamp>, nowMs: Long) = stamps.forEach { markSeen(it, nowMs) }

    /**
     * P1-5 connect baseline: for actives WITHOUT state -> baseline H = newest ts (treated as seen; kills
     * Chain C volley + fresh-install inflation). WITH state -> untouched; messages that arrived while
     * dead (ts > H) judge NEW. Returns Triple(baselined K, withState M, newWhileDead J).
     */
    fun connectBaseline(actives: List<SeenStamp>, nowMs: Long): Triple<Int, Int, Int> {
        var baseline = 0; var kept = 0; var newWhileDead = 0
        for (a in actives) {
            synchronized(convs) {
                val st = convs[a.convKey]
                if (st == null) {
                    convs[a.convKey] = ConState(a.convKey, normalizeTsMs(a.newestTs),
                        a.fpsCsv.split(',').filter { it.isNotBlank() }.take(MAX_BOUNDARY).toMutableList(),
                        nowMs, a.kind, a.nonWhen, a.nonTextFp)
                    baseline++
                } else {
                    kept++
                    if (normalizeTsMs(a.newestTs) > st.h) newWhileDead++
                }
            }
        }
        return Triple(baseline, kept, newWhileDead)
    }

    /** TTL + LRU prune; NEVER evict keys still on the shelf (would resurrect them). */
    fun prune(activeKeys: Set<String>, nowMs: Long) {
        synchronized(convs) {
            val it = convs.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (e.key in activeKeys) continue
                if (nowMs - e.value.updatedAt > TTL_MS) it.remove()
            }
            while (convs.size > MAX_CONVS) {
                val victim = convs.entries.firstOrNull { it.key !in activeKeys } ?: break
                convs.remove(victim.key)
            }
        }
    }

    fun size(): Int = synchronized(convs) { convs.size }

    /** cmd seen_dump: <= limit lines, key / H / boundary size / kind / updatedAt. */
    fun dump(limit: Int = 50): List<String> = synchronized(convs) {
        convs.values.toList().takeLast(limit).map { s ->
            "key=${s.key} H=${s.h} fpN=${s.boundary.size} kind=${s.kind} updated=${s.updatedAt}"
        } + trust.entries.take(limit).map { (p, t) -> "trust $p=$t" }
    }

    fun statsLine(): String = "v2 seen-guard stats: echo=$echoCount new=$newCount unknown=$unknownCount conflict=$conflictCount"

    // ---------------- persistence (b1499 style: one binary blob, Base64 in ONE prefs value) ----------------

    fun encode(): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        val out = java.io.DataOutputStream(buf)
        out.writeByte(SNAPSHOT_VERSION)
        val snapshot = synchronized(convs) { convs.values.toList() }
        out.writeInt(snapshot.size)
        for (s in snapshot) {
            out.writeUTF(s.key); out.writeLong(s.h)
            val fps = s.boundary.toList()
            out.writeByte(fps.size); fps.forEach { out.writeUTF(it) }
            out.writeLong(s.updatedAt); out.writeByte(s.kind)
            out.writeLong(s.nonWhen); out.writeUTF(s.nonTextFp)
        }
        out.writeInt(trust.size)
        trust.forEach { (p, t) -> out.writeUTF(p); out.writeByte(t.ordinal) }
        out.writeInt(pkgH.size)
        pkgH.forEach { (p, h) -> out.writeUTF(p); out.writeLong(h) }
        out.flush()
        return buf.toByteArray()
    }

    /** Corrupt/unknown version -> EMPTY model + false, so callers can log and the baseline protects. */
    fun decodeInto(bytes: ByteArray): Boolean = try {
        val din = java.io.DataInputStream(java.io.ByteArrayInputStream(bytes))
        val version = din.readByte().toInt()
        if (version != SNAPSHOT_VERSION && version != 1) { false } else {
            val n = din.readInt()
            synchronized(convs) {
                convs.clear()
                repeat(n) {
                    val key = din.readUTF(); val h = din.readLong()
                    val fps = mutableListOf<String>()
                    repeat(din.readByte().toInt()) { fps.add(din.readUTF()) }
                    val updated = din.readLong(); val kind = din.readByte().toInt()
                    val nonWhen = din.readLong(); val fp = din.readUTF()
                    convs[key] = ConState(key, h, fps, updated, kind, nonWhen, fp)
                }
            }
            trust.clear()
            repeat(din.readInt()) {
                val p = din.readUTF(); val t = Trust.values().getOrElse(din.readByte().toInt()) { Trust.UNKNOWN }
                trust[p] = t
            }
            pkgH.clear()
            if (version >= 2 && din.available() > 0) {
                repeat(din.readInt()) { pkgH[din.readUTF()] = din.readLong() }
            }
            true
        }
    } catch (_: Exception) {
        false
    }
}
