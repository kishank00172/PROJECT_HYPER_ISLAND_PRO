package com.hyperisland.pro.core

/**
 * b1500 — READINESS LAYER for the peeled-layer detector (consultant brief strategy #1).
 * MessagingStyle notifications carry the visible message stack; a conversation he READ that
 * later resurfaces with a stack made ONLY of pieces he already saw = the app peeling OLD layers,
 * not news (his 18:40 VALI MODS 'File' -> 'Feedback send ❤️' chain; the peeled text sits further
 * down the stack he already consumed). DORMANT until the owner picks the final semantics (R3);
 * pure Kotlin, unit-tested, no service wiring in b1500.
 */
class ConversationStackSignature(private val capacity: Int = 60) {

    enum class Verdict { UNKNOWN, PEEL_OR_OLD, GENUINELY_NEW }

    private val stacks = LinkedHashMap<String, List<String>>()

    fun normalize(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    fun normalizeStack(texts: List<String>): List<String> =
        texts.map { normalize(it) }.filter { it.isNotEmpty() }

    /** Store the stacked texts as they were when he consumed the conversation (wipe/tap-open). */
    fun record(convKey: String, texts: List<String>) {
        val st = normalizeStack(texts)
        if (st.isEmpty()) return
        stacks[convKey] = st
        while (stacks.size > capacity) stacks.remove(stacks.keys.first())
    }

    /** Is `sub` fully contained inside `full`, in the same relative order? */
    private fun isSubsequence(sub: List<String>, full: List<String>): Boolean {
        if (sub.isEmpty()) return true
        var i = 0
        for (t in full) if (t == sub[i]) { i++; if (i == sub.size) return true }
        return false
    }

    /**
     * UNKNOWN       -> nothing recorded for this chat (judge elsewhere).
     * PEEL_OR_OLD   -> incoming stack is a subsequence of the read stack: old layers resurfacing.
     * GENUINELY_NEW -> incoming carries at least one piece he has never consumed.
     */
    fun classify(convKey: String, texts: List<String>): Verdict {
        val stored = stacks[convKey] ?: return Verdict.UNKNOWN
        val inc = normalizeStack(texts)
        if (inc.isEmpty()) return Verdict.UNKNOWN
        return if (isSubsequence(inc, stored)) Verdict.PEEL_OR_OLD else Verdict.GENUINELY_NEW
    }

    /** Durable across MIUI kills (same persistence pattern as the b1499 guards). */
    fun snapshot(): List<Pair<String, List<String>>> = stacks.entries.map { it.key to it.value }

    fun restore(entries: List<Pair<String, List<String>>>) {
        stacks.clear()
        entries.forEach { (k, v) -> stacks[k] = v }
        while (stacks.size > capacity) stacks.remove(stacks.keys.first())
    }

    val size: Int get() = stacks.size
}
