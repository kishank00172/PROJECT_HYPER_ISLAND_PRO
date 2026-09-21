package com.hyperisland.pro.core

import java.util.Locale

/**
 * Frame accounting for the pill -> card morph, so "frame drop ho raha hai" is a number and not an opinion.
 *
 * The service feeds it the nanoTime of every animation frame; the summary goes into the trace log on the
 * frame that lands the card. Nothing here touches Android, so CI can prove the arithmetic - which matters
 * because a dropped frame is invisible from outside the device and the previous round of morph work was
 * guessed at, twice.
 *
 * A frame is "slow" when it costs more than [budgetMs] * 3 / 2: one 16 ms frame routinely measures 17-19
 * because the animator and the draw are on the same clock, and counting that as a drop made the number
 * useless. Two consecutive slow frames is what a human calls a hitch.
 */
class MorphJankMeter(private val budgetMs: Long = 16L) {

    var frameCount = 0L
        private set

    var slowCount = 0L
        private set

    var worstMs = 0L
        private set

    var worstAtProgress = 0f
        private set

    private var totalMs = 0L
    private var lastNanos = 0L

    // A flag, not "lastNanos == 0L": nanoTime legitimately starts at 0 in a test (and an animator's first
    // callback can be at any absolute value), and a zero sentinel silently swallowed the second frame.
    // CI caught it as "expected frames=2, got frames=1".
    private var armed = false

    /**
     * Record one animation frame at [nowNanos] and [progress] (0..1 of the morph). Returns true when the
     * frame was slow enough that the renderer had nothing new to show. The first call only arms the clock,
     * and an interval over two seconds is the app being paused, not a frame - counting it would report a
     * 4000 ms drop on the first morph after the screen comes back on.
     */
    fun frame(nowNanos: Long, progress: Float): Boolean {
        if (!armed) {
            armed = true
            lastNanos = nowNanos
            return false
        }
        val delta = (nowNanos - lastNanos) / 1_000_000L
        lastNanos = nowNanos
        if (delta <= 0L || delta > 2_000L) return false
        frameCount++
        totalMs += delta
        val slow = delta > budgetMs * 3 / 2
        if (slow) slowCount++
        if (delta > worstMs) {
            worstMs = delta
            worstAtProgress = progress
        }
        return slow
    }

    /** Average over the frames actually recorded; "frames=0" when nothing ran (cancelled on frame one). */
    fun summary(): String {
        if (frameCount == 0L) return "frames=0"
        val avg = totalMs / frameCount
        return "frames=$frameCount avg=${avg}ms max=${worstMs}ms@t=${String.format(Locale.US, "%.2f", worstAtProgress)} slow=$slowCount"
    }
}

/**
 * One main-thread block, phrased for the log. Kept out of the service so CI can prove the formatting: this
 * is the line that has to answer "who starved the morph", and a round of this project was already wasted
 * reading a stall signal that named nothing.
 *
 * [frames] is the stack the main thread was caught inside, topmost first. `nativePollOnce` at the top means
 * the thread was not executing our code at all (the panel or the scheduler held it); anything else is a name
 * we can act on.
 */
fun stallLine(ms: Long, threadState: String, frames: List<StackTraceElement>): String {
    val top = if (frames.isEmpty()) "?" else frames.take(3).joinToString(" <- ") {
        "${it.methodName}@${(it.fileName ?: "native").substringAfterLast('/'))}:${it.lineNumber}"
    }
    return "${ms}ms $threadState :: $top"
}

/**
 * The runtime's own GC counters, read once per morph window. A blocking GC parks the main thread, which is
 * the same symptom as a slow draw with a completely different cure, and until now the log could not tell
 * them apart. Units as the platform documents them: counts and milliseconds.
 */
data class GcSnapshot(
    val gcCount: Long,
    val gcMs: Long,
    val blockingCount: Long,
    val blockingMs: Long,
    val allocated: Long,
) {
    fun deltaText(from: GcSnapshot): String {
        val gc = gcCount - from.gcCount
        val gms = gcMs - from.gcMs
        val bc = blockingCount - from.blockingCount
        val bms = blockingMs - from.blockingMs
        val alloc = allocated - from.allocated
        if (gc <= 0L && bc <= 0L && alloc <= 0L) return "none"
        return "+$gc/$gms" + (if (bc > 0L) " blocking=+$bc/$bms" else "") + " alloc=${mb(alloc)}"
    }

    private fun mb(bytes: Long): String =
        if (bytes >= 1_048_576L) String.format(Locale.US, "%.1fMB", bytes / 1_048_576f)
        else "${bytes / 1024L}KB"
}
