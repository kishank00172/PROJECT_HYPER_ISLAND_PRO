package com.hyperisland.pro.core


/**
 * An in-app trace buffer: every decision the island makes, kept where the user can read it.
 *
 * Why this exists: the last three bug rounds were diagnosed from a screenshot plus a rebuild of what
 * *probably* happened in the code. Some of those guesses were right and some were not, and the tester
 * had to install four builds to find out. A decision that is silently ignored ("swipe dropped because a
 * swap is still in flight") is invisible from outside, and the interesting failures happen in a service
 * process an OEM is free to kill at any moment. So the service now narrates itself into this buffer,
 * every line that matters, and the buffer is (a) readable in-app, (b) mirrored to `logcat -s HIP_TRACE`,
 * and (c) persisted so a kill does not erase the evidence.
 *
 * Deliberately Android-free: `sink` is injected by the caller. That keeps the ring-buffer rules testable
 * on the JVM, which is the only place they can be verified without a phone.
 */
object TraceLog {

    /**
     * Newest at the end, like a terminal. Beyond this the oldest lines fall off. It used to be 600, which
     * was fine for decisions and hopeless for a flood: a promo blast pushes ~5 lines per notification, and
     * the tester lost the evidence he needed to say *which* build felt slow. 1500 lines is still a few
     * hundred KB of string, and the whole buffer now goes to a file on demand.
     */
    const val MAX_LINES = 4000

    /**
     * Lines that fell off the front of the buffer. It used to be silent: his export said "1500 lines of 1500"
     * and 2686 lines had already been thrown away, so I was reading a session that had lost its first half and
     * calling it the whole story. A capped buffer is fine; an uncapped-looking one is not.
     */
    var droppedLines = 0L
        private set

    /** How much of the tail survives a process kill (SharedPreferences is not a database; keep it small). */
    const val PERSISTED_LINES = 600

    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private var seq = 0L
    /**
     * `SimpleDateFormat` was being **constructed for every single line** - the allocation plus the timezone rule
     * lookup is what his sampler kept catching as `clock$lambda$0` (19 stalls in one session, on a path that
     * runs on every notification of a flood). The time is now arithmetic, with the offset re-read once a minute.
     */
    private var clock: (Long) -> String = { formatClock(it) }
    private var clockOffsetMs = 0L
    private var clockOffsetAt = Long.MIN_VALUE

    fun formatClock(tms: Long): String {
        // The first call must always compute: `tms - Long.MIN_VALUE` overflows, so the sentinel is tested
        // explicitly instead of arithmetically. A backwards jump (an NTP correction) re-reads as well.
        if (clockOffsetAt == Long.MIN_VALUE || tms < clockOffsetAt || tms - clockOffsetAt > 60_000L) {
            clockOffsetAt = tms
            clockOffsetMs = java.util.TimeZone.getDefault().getOffset(tms).toLong() // getOffset returns Int
        }
        var rest = (tms + clockOffsetMs) / 1000L
        val ms = (tms + clockOffsetMs) % 1000L
        val sec = rest % 60; rest /= 60
        val min = rest % 60
        val hr = (rest / 60) % 24
        return pad2(hr) + ":" + pad2(min) + ":" + pad2(sec) + "." + pad3(ms)
    }

    private fun pad2(v: Long) = if (v < 10) "0$v" else "$v"
    private fun pad3(v: Long) = when { v < 10 -> "00$v"; v < 100 -> "0$v"; else -> "$v" }

    /** Where each finished line also goes — the service points this at logcat. */
    var sink: (String) -> Unit = {}

    /** Called whenever a line is added, for anything that needs to flush (the service saves to prefs). */
    var onLine: (String) -> Unit = {}

    fun line(tag: String, message: String) {
        val stamped = "${clock(System.currentTimeMillis())} #${++seq} [$tag] $message"
        synchronized(lock) {
            lines.addLast(stamped)
            // Dropping the oldest line is the whole point: the buffer must survive a 200-message flood
            // from a promo app without ever growing.
            if (lines.size > MAX_LINES) { lines.removeFirst(); droppedLines++ }
        }
        sink(stamped)
        onLine(stamped)
    }

    /** Short form for the hot paths; tag first so `grep` on logcat still reads the same. */
    fun gesture(message: String) = line("TOUCH", message)
    fun ring(message: String) = line("RING", message)

    /**
     * Every change to the number he sees, with what caused it. The count going down looked like the app
     * taking notifications back ("ye wapas ghar kaise ja rha hai? Notification telegram wapas le rha hai
     * kya"), and from inside a phone a correct drop and a lost page are indistinguishable without this.
     */
    fun count(message: String) = line("COUNT", message)
    fun stage(message: String) = line("STAGE", message)
    fun ingest(message: String) = line("INGEST", message)
    fun reply(message: String) = line("REPLY", message)
    fun morph(message: String) = line("MORPH", message)

    /** The whole-window frame watcher: what every frame cost, whoever asked for it. */
    fun frame(message: String) = line("FRAME", message)

    /** What the panel can do and what we asked it for - so "120 fps" is never an assumption. */
    fun display(message: String) = line("DISPLAY", message)

    fun snapshot(): String = synchronized(lock) { lines.joinToString("\n") }

    fun tail(n: Int): String = synchronized(lock) {
        if (lines.size <= n) lines.joinToString("\n") else lines.drop(lines.size - n).joinToString("\n")
    }

    fun size(): Int = synchronized(lock) { lines.size }

    /** Wiping the buffer also restarts the line numbering, so a test/reader can rely on `#1`. */
    fun clear() = synchronized(lock) {
        lines.clear()
        seq = 0L
        droppedLines = 0L // otherwise the header keeps reporting losses from before the clear
    }

    /** After a process restart: seed the buffer from what was persisted, newest last. */
    fun restore(text: String?) {
        if (text.isNullOrBlank()) return
        synchronized(lock) {
            if (lines.isNotEmpty()) return
            text.lineSequence().filter { it.isNotBlank() }.toList().let { all ->
                val kept = if (all.size > PERSISTED_LINES) all.takeLast(PERSISTED_LINES) else all
                kept.forEach { lines.addLast(it) }
                seq = kept.size.toLong() // local numbering restarts; the timestamps are the real order
            }
        }
    }

    fun persisted(): String = synchronized(lock) {
        if (lines.size <= PERSISTED_LINES) lines.joinToString("\n")
        else lines.drop(lines.size - PERSISTED_LINES).joinToString("\n")
    }
}
