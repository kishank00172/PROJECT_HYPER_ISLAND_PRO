package com.hyperisland.pro.core

/**
 * b1497 — his 17:53 device proof: after a shade-open WIPE ('CLEAR 2 pages because=shade-open'),
 * Telegram re-posted the SAME notification the moment the shade closed ('VIJAY TRADER' age=248s,
 * identical '❤️ Sticker' content), and the island re-popped the chat he had just read.
 * Semantics: shade-open WIPE = he READ those pages. An identical echo afterwards is not news;
 * the same chat returning with DIFFERENT content is real news and must pass.
 * Pure Kotlin, memoised by conversationKey -> "title|message" fingerprint, FIFO-capped.
 */
class ShadeWipeEchoGuard(private val capacity: Int = 25) {

    private val wiped = LinkedHashMap<String, String>()

    /** Fingerprints of pages a shade-open wipe consumed. Call BEFORE the ring is cleared. */
    fun recordWipe(pages: List<Triple<String, String, String>>) {
        for ((convKey, title, message) in pages) wiped[convKey] = fingerprint(title, message)
        while (wiped.size > capacity) wiped.remove(wiped.keys.first())
    }

    fun fingerprint(title: String, message: String): String = "$title|$message"

    /**
     * true  => identical echo of a read page: drop it.
     * false => unknown key (pass) or same chat with NEW content (pass, and forget the key so the
     *          fresh page lives a normal life afterwards).
     */
    fun shouldDrop(convKey: String, title: String, message: String): Boolean {
        val old = wiped[convKey] ?: return false
        if (old == fingerprint(title, message)) return true
        wiped.remove(convKey)
        return false
    }

    val size: Int get() = wiped.size

    /** b1499 (his 19:12 proof: MIUI killed the process, rebind re-fired every active notification,
     *  and the in-memory guard died with it): durable across process death via AppSettings. */
    fun snapshot(): List<Pair<String, String>> = wiped.entries.map { it.key to it.value }

    fun restore(entries: List<Pair<String, String>>) {
        wiped.clear()
        entries.forEach { (k, v) -> wiped[k] = v }
        while (wiped.size > capacity) wiped.remove(wiped.keys.first())
    }
}
