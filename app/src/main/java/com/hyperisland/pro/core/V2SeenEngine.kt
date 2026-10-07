package com.hyperisland.pro.core

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log

/**
 * b1501 / Round-I P1: the Android shell around [V2SeenWatermark].
 *  - One SharedPreferences value `seen_state_v2` (Base64 binary blob, versioned; corrupt -> empty + log).
 *  - One serial worker owns disk IO; callers mutate the in-memory model synchronously (verdict O(msgs),
 *    no IO) and only REQUEST persistence.
 *  - commit() for wipes/taps/user-removals (process death is routine on MIUI), debounced for admissions.
 *  - VERIFIED-BY-TEST-ONLY until his 24h device run.
 */
object V2SeenEngine {

    private const val TAG = "HIP_TRACE"
    private const val PREFS = "hip_v2_seen"
    private const val KEY_BLOB = "seen_state_v2"
    private const val KEY_MODE = "seen_mode"
    private const val ADMISSION_DEBOUNCE_MS = 300L
    private const val PROOF_RATE_PER_SEC = 20

    private val lock = Any()
    private var appContext: Context? = null
    private var loaded = false
    var failNote: String? = null; private set

    val model = V2SeenWatermark()

    private var worker: HandlerThread? = null
    private var workerHandler: Handler? = null
    private val persistRunnable = Runnable { writeBlob() }

    /** Service checks whether a ring page is live for a convKey (NONMSG echo gate). */
    @Volatile var livePageChecker: ((String) -> Boolean)? = null
    /** Listener supplies which convKeys are still on the shelf; prune never evicts them. */
    @Volatile var activeKeysSupplier: (() -> Set<String>)? = null

    // ---- judgements (main/binder-thread safe: model locks internally; IO never happens here) ----

    fun mode(context: Context): V2SeenWatermark.Mode =
        V2SeenWatermark.Mode.from(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODE, null))

    fun setMode(context: Context, m: V2SeenWatermark.Mode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MODE, m.slug).commit()
    }

    fun judgeMessaging(context: Context, key: String, pkg: String, msgs: List<V2SeenWatermark.MsgStamp>, postTime: Long, whenMs: Long = 0L): V2SeenWatermark.JudgeResult {
        ensureLoaded(context)
        val r = model.judgeMessaging(key, pkg, msgs, postTime, System.currentTimeMillis(), whenMs)
        proof("verdict=${r.verdict} src=msg pkg=$pkg convKey=$key newestTs=${r.newestTs} H=${model.stateOf(key)?.h ?: 0} cmp=${r.cmp} unseen=${r.unseenCount} mode=${mode(context).slug}")
        return r
    }

    fun judgeNonMessaging(context: Context, key: String, whenMs: Long, textFp: String): V2SeenWatermark.JudgeResult {
        ensureLoaded(context)
        val live = runCatching { livePageChecker?.invoke(key) ?: false }.getOrDefault(false)
        val r = model.judgeNonMessaging(key, whenMs, textFp, live)
        proof("verdict=${r.verdict} src=nonmsg convKey=$key when=$whenMs live=$live")
        return r
    }

    fun markAdmittedAndSchedule(context: Context, s: V2SeenWatermark.SeenStamp) {
        ensureLoaded(context)
        model.markSeen(s, System.currentTimeMillis())
        schedulePersist(context)
    }

    /** Wipe / tap / user-removal: immediate commit - MIUI kills the process minutes later (19:12 proof). */
    fun markSeenNow(context: Context, stamps: List<V2SeenWatermark.SeenStamp>, why: String) {
        ensureLoaded(context)
        val now = System.currentTimeMillis()
        stamps.forEach { model.markSeen(it, now) }
        TraceLog.line("V2SEEN", "markSeen n=${stamps.size} because=$why")
        persistNow(context)
        // b1508: shelf probe + prune ride the writer thread - never the animation main thread.
        workerHandler?.post {
            runCatching { activeKeysSupplier?.invoke() }?.onSuccess { keys -> keys?.let { model.prune(it, now) } }
        }
    }

    fun connectBaseline(context: Context, actives: List<V2SeenWatermark.SeenStamp>): Triple<Int, Int, Int> {
        ensureLoaded(context)
        return model.connectBaseline(actives, System.currentTimeMillis())
    }

    fun legacyConflict(context: Context, oldGuard: String, pkg: String, key: String, newestTs: Long) {
        model.conflictCount++
        TraceLog.ingest("v2 seen-guard CONFLICT old=$oldGuard pkg=$pkg key=$key newestTs=$newestTs H=${model.stateOf(key)?.h ?: 0}")
    }

    fun stats(): String = model.statsLine()

    fun clear(context: Context) {
        synchronized(lock) {
            loaded = true
            val fresh = V2SeenWatermark()
            copyState(fresh, model)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_BLOB).commit()
        TraceLog.line("V2SEEN", "seen_clear: state wiped")
    }

    /** Copy one model's truth into another (model fields are package-private-ish data; rebuild via blob). */
    private fun copyState(from: V2SeenWatermark, into: V2SeenWatermark) {
        into.decodeInto(from.encode())
    }

    fun dumpLines(limit: Int = 50): List<String> = model.dump(limit)

    // ---------------- disk ----------------

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (loaded) return
        appContext = context.applicationContext
        loaded = true
        try {
            val b64 = appContext!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_BLOB, null)
            if (b64 != null) {
                val ok = model.decodeInto(Base64.decode(b64, Base64.DEFAULT))
                if (!ok) {
                    failNote = "blob corrupt/unknown version - started empty (connect baseline protects)"
                    TraceLog.line("V2SEEN", failNote!!)
                }
            }
        } catch (t: Throwable) {
            failNote = "load failed: ${t.javaClass.simpleName}"
            TraceLog.line("V2SEEN", failNote!!)
        }
        ensureWorker()
    }

    private fun ensureWorker() {
        if (worker?.isAlive == true) return
        worker = HandlerThread("v2-seen-writer").apply { start() }
        workerHandler = Handler(worker!!.looper)
    }

    private fun schedulePersist(context: Context) {
        ensureLoaded(context)
        workerHandler?.let { it.removeCallbacks(persistRunnable); it.postDelayed(persistRunnable, ADMISSION_DEBOUNCE_MS) }
    }

    fun persistNow(context: Context) {
        ensureLoaded(context)
        workerHandler?.removeCallbacks(persistRunnable)
        workerHandler?.post(persistRunnable)
    }

    private fun writeBlob() {
        val ctx = appContext ?: return
        try {
            val b64 = Base64.encodeToString(model.encode(), Base64.NO_WRAP)
            // commit, not apply: the wipe moment is exactly the moment MIUI kills us (his 19:12 proof).
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_BLOB, b64).commit()
        } catch (t: Throwable) {
            Log.w(TAG, "v2 seen persist failed: ${t.javaClass.simpleName}")
        }
    }

    // ---------------- proof line rate-limit (20/s, aggregate beyond) ----------------

    private var proofWindowStart = 0L
    private var proofThisWindow = 0
    private var proofSuppressed = 0

    private fun proof(line: String) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            if (now - proofWindowStart >= 1000L) {
                proofWindowStart = now; proofThisWindow = 0
                if (proofSuppressed > 0) { TraceLog.ingest("v2 seen-guard: (+${proofSuppressed} lines suppressed)"); proofSuppressed = 0 }
            }
            if (proofThisWindow < PROOF_RATE_PER_SEC) { proofThisWindow++; TraceLog.ingest("v2 seen-guard: $line") } else proofSuppressed++
        }
    }

    // ---------------- acceptance self-test (P1-9): synthetic ingests through the REAL model + REAL blob ----------------

    /**
     * Feeds a FRESH model through the same encode/decode/judge/markSeen code the live engine uses.
     * Never touches the live model, the ring, or the UI. One TRACE line per step:
     *   v2 sim chain=B step=peel verdict=ECHO expect=ECHO PASS
     */
    fun runSelfTest(chain: String): String {
        val m = V2SeenWatermark()
        fun stamp(key: String, ts: Long, text: String, sender: String = "them") =
            V2SeenWatermark.MsgStamp(ts, text, sender)
        fun mark(m: V2SeenWatermark, key: String, msgs: List<V2SeenWatermark.MsgStamp>) {
            val newest = msgs.maxBy { it.tsMs }!!
            val csv = msgs.filter { it.tsMs == newest.tsMs }.joinToString(",") { V2SeenWatermark.fp(it.tsMs, it.text, it.sender) }
            m.markSeen(V2SeenWatermark.SeenStamp(key, "pkg", newest.tsMs, csv), 10_000L)
        }
        data class Step(val name: String, val expect: V2SeenWatermark.Verdict, val run: () -> V2SeenWatermark.Verdict)
        val results = StringBuilder()
        var pass = 0; var total = 0
        fun report(c: String, steps: List<Step>) {
            for (s in steps) {
                total++
                val got = s.run()
                val ok = got == s.expect
                if (ok) pass++
                results.append("v2 sim chain=").append(c).append(" step=").append(s.name)
                    .append(" verdict=").append(got).append(" expect=").append(s.expect)
                    .append(if (ok) " PASS\n" else " FAIL\n")
            }
        }
        val now = 1_000_000_000_000L + 100_000L

        fun chainA() = report("A", listOf(
            Step("replay", V2SeenWatermark.Verdict.ECHO) {
                val k = "pkg|shortcut|vali"
                mark(m, k, listOf(stamp(k, now - 5_000, "hi")))
                m.judgeMessaging(k, "pkg", listOf(stamp(k, now - 5_000, "hi")), now - 5_000, now).verdict
            }
        ))
        fun chainB() = report("B", listOf(
            Step("peel", V2SeenWatermark.Verdict.ECHO) {
                val k = "pkg|shortcut|vali2"
                mark(m, k, listOf(stamp(k, now - 2_000, "older"), stamp(k, now, "newest")))
                // peel: same chat, one layer down, unread bumped, postTime rewritten to NOW - clock still old
                m.judgeMessaging(k, "pkg", listOf(stamp(k, now - 5_000, "oldest"), stamp(k, now - 2_000, "older")), now, now).verdict
            },
            Step("real-new-after-peel", V2SeenWatermark.Verdict.NEW) {
                val k = "pkg|shortcut|vali2"
                m.judgeMessaging(k, "pkg", listOf(stamp(k, now + 4_000, "actually new"), stamp(k, now, "newest")), now, now).verdict
            }
        ))
        fun chainC() = report("C", listOf(
            Step("persist-resync-volley", V2SeenWatermark.Verdict.ECHO) {
                val k = "pkg|shortcut|vali3"
                mark(m, k, listOf(stamp(k, now, "read it")))
                // kill process: persist -> fresh engine from disk -> volley with original postTimes
                val fresh = V2SeenWatermark()
                fresh.decodeInto(m.encode())
                fresh.judgeMessaging(k, "pkg", listOf(stamp(k, now, "read it")), now, now + 365_000).verdict
            },
            Step("new-arrived-while-dead", V2SeenWatermark.Verdict.NEW) {
                val fresh = V2SeenWatermark(); fresh.decodeInto(m.encode())
                fresh.judgeMessaging("pkg|shortcut|vali3", "pkg", listOf(stamp("pkg|shortcut|vali3", now + 60_000, "came while dead")), now + 60_000, now + 365_000).verdict
            }
        ))
        fun chainD() = report("D", listOf(
            Step("boundary-same-ts-new-fp", V2SeenWatermark.Verdict.NEW) {
                val k = "pkg|shortcut|vali4"
                mark(m, k, listOf(stamp(k, now, "hi")))
                m.judgeMessaging(k, "pkg", listOf(stamp(k, now, "hi"), stamp(k, now, "yo")), now, now).verdict
            },
            Step("boundary-same-ts-same-fp", V2SeenWatermark.Verdict.ECHO) {
                val k = "pkg|shortcut|vali4"
                m.judgeMessaging(k, "pkg", listOf(stamp(k, now, "hi")), now, now).verdict
            }
        ))
        fun chainE() = report("E", listOf(
            Step("unrusted-posttime-clocks", V2SeenWatermark.Verdict.UNKNOWN) {
                val k = "pkg2|shortcut|dodgy"
                m.judgeMessaging(k, "pkg2", (0 until 4).map { stamp(k, now - 1_000, "msg$it") }, now - 1_000, now).verdict
            }
        ))
        fun chainF(): Unit {
            val k = "pkg3|title|download"
            report("F", listOf(
                Step("first-post", V2SeenWatermark.Verdict.NEW_FALLBACK) {
                    m.judgeNonMessaging(k, now, V2SeenWatermark.nonMsgFp("App", "Downloading 1%", ""), false).verdict
                }
            ))
            m.markSeen(V2SeenWatermark.SeenStamp(k, "pkg3", now, "", 2, now, V2SeenWatermark.nonMsgFp("App", "Downloading 1%", "")), now)
            report("F", listOf(
                Step("progress-update-after-wipe", V2SeenWatermark.Verdict.ECHO) {
                    m.judgeNonMessaging(k, now, V2SeenWatermark.nonMsgFp("App", "Downloading 99%", ""), false).verdict
                },
                Step("complete-text-change", V2SeenWatermark.Verdict.NEW_FALLBACK) {
                    m.judgeNonMessaging(k, now, V2SeenWatermark.nonMsgFp("App", "Download complete", ""), false).verdict
                }
            ))
        }
        fun chainG() = report("G", listOf(
            Step("pkg-clock-unseeded-conv", V2SeenWatermark.Verdict.ECHO) {
                m.markSeen(V2SeenWatermark.SeenStamp("seen-one", "tg", now, "", 1, 0L, "", advancePkg = true), now)
                m.judgeMessaging("tg|shortcut|ndid_x", "tg", listOf(stamp("ndid_x", now - 10_000, "peeled back")), now, now).verdict
            },
            Step("pkg-clock-real-new-passes", V2SeenWatermark.Verdict.NEW) {
                m.judgeMessaging("tg|shortcut|ndid_x", "tg", listOf(stamp("ndid_x", now + 7_000, "real fresh")), now, now).verdict
            },
            Step("noclock-by-when", V2SeenWatermark.Verdict.ECHO) {
                m.judgeMessaging("tg|shortcut|ndid_y", "tg", listOf(stamp("ndid_y", 0L, "channel noise")), now, now, whenMs = now - 5_000).verdict
            }
        ))
        when (chain) {
            "A" -> chainA(); "B" -> chainB(); "C" -> chainC(); "D" -> chainD(); "E" -> chainE(); "F" -> chainF(); "G" -> chainG()
            else -> { chainA(); chainB(); chainC(); chainD(); chainE(); chainF(); chainG() }
        }
        TraceLog.line("V2SEEN", results.toString().trimEnd())
        return "sim $chain: $pass/$total PASS"
    }
}
