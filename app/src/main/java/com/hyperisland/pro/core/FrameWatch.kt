package com.hyperisland.pro.core

import java.util.Locale

/**
 * Frame accounting for the **whole overlay window**, not only for the animations we thought to instrument.
 *
 * The morph meter can only speak about the frames it was handed, so every failure mode nobody predicted -
 * a badge redraw, a layout storm behind a notification flood, an OEM holding the window at 60 Hz - is
 * invisible to it, and "we measured `slow=0`" turns into a false all-clear. That happened for real: the
 * tester reported the whole island going sluggish while every morph line said the frames were fine. So
 * this watcher is driven by the vsync callback itself. While the island is doing *anything* it counts
 * every frame the renderer is given, the service adds a layout-pass counter (a storm from any cause shows
 * up in it) and its own draw time, and when the window goes quiet the summary lands in the trace log.
 *
 * It also decides the morph meter's budget: a 16 ms "slow" threshold is correct at 60 Hz and blind at
 * 120 Hz, where a 16 ms gap means two missed frames. [periodMs] is measured, not assumed.
 *
 * Android-free on purpose, like [MorphJankMeter]: the service owns the Choreographer, this owns the
 * arithmetic, and CI can prove the arithmetic - which is the only way these numbers can be trusted.
 */
class FrameWatch(private var hintPeriodMs: Long = 16L) {

    var running = false
        private set

    /** While this is true the card view times its own draw. Gated by the window, not checked per frame. */
    var measuring = false
        private set

    /** vsync callbacks seen in this window. */
    var frames = 0L
        private set

    var slowFrames = 0L
        private set

    var layoutPasses = 0L
        private set

    /**
     * Layout passes that landed *inside* an animation. This is the number that separates "the morph is
     * expensive" from "something re-measured the card while the morph was drawing it" - the second one is
     * what makes content shift mid-animation without costing any frame time, so no other counter sees it.
     */
    var midMorphLayouts = 0L
        private set

    var worstGapMs = 0L
        private set

    var gapSumMs = 0L
        private set

    private var drawNanos = 0L
    private var drawSamples = 0L
    private var lastNanos = 0L

    // A flag, not "lastNanos == 0L": the first frame of a window can legitimately arrive at nanoTime 0 in a
    // test (and at any absolute value on a device), and a zero sentinel then swallows the second frame.
    // MorphJankMeter was CI-caught for exactly this.
    private var armed = false

    /**
     * The period carried from the last closed window. Measured gaps restart with every window, and a budget
     * that resets to the hint each time would cry "slow" at 60 Hz for the first frames of a 120 Hz vote -
     * a false alarm is exactly as useless as a blind spot.
     */
    private var carriedPeriodMs = hintPeriodMs
    private var lastActivityMs = 0L
    private var armedAtMs = 0L
    private val gapSamples = ArrayList<Long>(GAP_SAMPLES)

    /**
     * Something started moving: opens a watch window, or extends the one already open. Returns true when
     * this call is the one that opened it, so the caller knows to register its frame callback. [frame]
     * deliberately takes no activity timestamp and this takes no nanos: the two clocks are not
     * interchangeable, and the first version of this class re-posted the frame callback from a frame while
     * a frame also extended the window - so [expired] could never become true and the window never closed.
     * A test says so out loud now.
     */
    fun noteActivity(nowMs: Long): Boolean {
        if (running) {
            lastActivityMs = nowMs
            return false
        }
        begin(nowMs)
        return true
    }

    private fun begin(nowMs: Long) {
        running = true
        measuring = true
        frames = 0L
        slowFrames = 0L
        layoutPasses = 0L
        worstGapMs = 0L
        gapSumMs = 0L
        drawNanos = 0L
        drawSamples = 0L
        lastNanos = 0L
        armed = false
        gapSamples.clear()
        armedAtMs = nowMs
        lastActivityMs = nowMs
    }

    /**
     * One vsync. Note that a frame does *not* extend the window: only real activity does, or the window
     * would never close (we re-post every frame, so frames keep arriving while we are watching).
     */
    fun frame(nowNanos: Long, nowMs: Long): Boolean {
        frames++
        if (!armed) {
            armed = true
            lastNanos = nowNanos
            return false
        }
        val delta = (nowNanos - lastNanos) / 1_000_000L
        lastNanos = nowNanos
        // A gap over two seconds is the app being paused or the screen going off, not a dropped frame.
        if (delta <= 0L || delta > 2_000L) return false
        gapSumMs += delta
        if (delta > worstGapMs) worstGapMs = delta
        if (gapSamples.size >= GAP_SAMPLES) gapSamples.removeAt(0)
        gapSamples.add(delta)
        val slow = delta > periodMs() * 3 / 2
        if (slow) slowFrames++
        return slow
    }

    /**
     * Every layout pass of the overlay's tree, whoever asked for it. A storm is visible as a big number, and
     * the split says whether it landed while an animation was on screen.
     */
    fun noteLayoutPass(midMorph: Boolean = false) {
        layoutPasses++
        if (midMorph) midMorphLayouts++
    }

    /** Nanoseconds the card view spent in `dispatchDraw`, so our own cost is separated from the rest. */
    fun noteDrawNanos(nanos: Long) {
        if (nanos > 0L) {
            drawNanos += nanos
            drawSamples++
        }
    }

    fun expired(nowMs: Long): Boolean = running && nowMs - lastActivityMs > WINDOW_MS

    /** Closes the window and returns the line for the trace log. Safe to call once per window. */
    fun end(nowMs: Long): String {
        val line = summary(nowMs - armedAtMs)
        carriedPeriodMs = periodMs()
        running = false
        measuring = false
        return line
    }

    fun summary(windowMs: Long): String {
        val median = periodMs()
        val hz = snapHertz(median)
        val avgDraw = if (drawSamples == 0L) 0f else drawNanos.toFloat() / 1_000_000f / drawSamples
        return "frames=$frames hz=$hz gap=${median}ms slow=$slowFrames layouts=$layoutPasses/$midMorphLayouts " +
            "draw=${String.format(Locale.US, "%.1f", avgDraw)}ms/$drawSamples worst=${worstGapMs}ms window=${windowMs}ms"
    }

    /**
     * The frame period to treat as "one frame of work". The median of the recent gaps while there are
     * enough of them, otherwise the hint the service was given - which is the panel's own mode rate, so a
     * first window is never measured against the wrong budget.
     */
    fun periodMs(): Long {
        if (gapSamples.size < 3) return carriedPeriodMs
        val sorted = gapSamples.toLongArray().also { it.sort() }
        return sorted[sorted.size / 2]
    }

    /**
     * What the panel says its frame period is, from the refresh-rate vote. Seeded so the very first morph is
     * measured against 120 Hz if the window was voted to 120 Hz, instead of against an assumed 16 ms.
     */
    fun notePanelPeriod(periodMs: Long) {
        if (periodMs in 3L..40L) {
            hintPeriodMs = periodMs
            if (gapSamples.isEmpty()) carriedPeriodMs = periodMs
        }
    }

    companion object {
        private const val GAP_SAMPLES = 48
        private const val WINDOW_MS = 900L
        private val STANDARD_HERTZ = intArrayOf(60, 72, 90, 120, 144, 165, 240)

        /**
         * Frame gaps are whole milliseconds, and a 60 Hz panel reports 16 or 17 for a perfect frame, so
         * the gap alone reads as "62 fps" and starts arguments. Snap to the rate the panel could actually
         * be running; the raw `gap=` is logged next to it for anyone who wants to check the snap.
         */
        fun snapHertz(gapMs: Long): Int {
            if (gapMs <= 0L) return 0
            var best = STANDARD_HERTZ[0]
            var bestDelta = Double.MAX_VALUE
            for (hz in STANDARD_HERTZ) {
                val delta = kotlin.math.abs(1000.0 / hz - gapMs.toDouble())
                if (delta < bestDelta) {
                    bestDelta = delta
                    best = hz
                }
            }
            return best
        }
    }
}
