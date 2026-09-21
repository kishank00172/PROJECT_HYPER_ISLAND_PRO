package com.hyperisland.pro.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    /** Newest at the end, like a terminal. Beyond this the oldest lines fall off. */
    const val MAX_LINES = 600

    /** How much of the tail survives a process kill (SharedPreferences is not a database; keep it small). */
    const val PERSISTED_LINES = 300

    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private var seq = 0L
    private var clock: (Long) -> String = { SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(it)) }

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
            if (lines.size > MAX_LINES) lines.removeFirst()
        }
        sink(stamped)
        onLine(stamped)
    }

    /** Short form for the hot paths; tag first so `grep` on logcat still reads the same. */
    fun gesture(message: String) = line("TOUCH", message)
    fun ring(message: String) = line("RING", message)
    fun stage(message: String) = line("STAGE", message)
    fun ingest(message: String) = line("INGEST", message)
    fun reply(message: String) = line("REPLY", message)
    fun morph(message: String) = line("MORPH", message)

    fun snapshot(): String = synchronized(lock) { lines.joinToString("\n") }

    fun tail(n: Int): String = synchronized(lock) {
        if (lines.size <= n) lines.joinToString("\n") else lines.drop(lines.size - n).joinToString("\n")
    }

    fun size(): Int = synchronized(lock) { lines.size }

    /** Wiping the buffer also restarts the line numbering, so a test/reader can rely on `#1`. */
    fun clear() = synchronized(lock) {
        lines.clear()
        seq = 0L
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
