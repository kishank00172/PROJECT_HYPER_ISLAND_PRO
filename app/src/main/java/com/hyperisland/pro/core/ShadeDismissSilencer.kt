package com.hyperisland.pro.core

/**
 * b1498 — his 18:40 device proof, and his own words: "jo bhi pill pe rehta hai thodi der me wapas
 * aa jata hai... baaki sab khatam hona chahiye". Chain from his log: he CLEAR-ALLs the shade
 * (18:39:4x), every chat fires REASON_CANCEL_ALL, and within seconds the apps re-post the SAME
 * conversations with a peeled next-layer message ('File' -> 'Feedback send ❤️', unread 1->2,
 * postTime bumped to now) - so a content-fingerprint guard correctly calls it "new content"
 * and the island repopulates. His semantics: HIS dismissal = silence that package. Pure JVM.
 */
class ShadeDismissSilencer(private val windowMs: Long = DEFAULT_WINDOW_MS) {

    companion object {
        /** Clear-all/teardown reposts arrive within seconds (his: 19s). 90s covers the whole
         *  repost volley; a REAL message after that still pops. Bounded deliberately. */
        const val DEFAULT_WINDOW_MS = 90_000L
    }

    private val until = HashMap<String, Long>()

    /** Arms silence for a package FROM this moment (his dismiss/shade-wipe). */
    fun stamp(pkg: String, now: Long) {
        until[pkg] = now + windowMs
    }

    fun isSilenced(pkg: String, now: Long): Boolean {
        val u = until[pkg] ?: return false
        if (u <= now) { until.remove(pkg); return false }
        return true
    }

    fun remainingMs(pkg: String, now: Long): Long = ((until[pkg] ?: now) - now).coerceAtLeast(0L)

    val size: Int get() = until.size
}
