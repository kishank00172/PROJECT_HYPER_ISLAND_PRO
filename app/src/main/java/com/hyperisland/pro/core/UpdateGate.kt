package com.hyperisland.pro.core

/**
 * The guard in front of "update the island".
 *
 * It exists because of a number, not a feeling: in the tester's b1369 export, 904 main-thread blocks totalling
 * 154 seconds were sampled, and the top of the stack was almost never our drawing - it was
 * `nGetFontMetricsInt` (16.7 s), `transactNative` (23.3 s), `nBuildMeasuredText` (12.0 s), `nDrawTextRun`,
 * `nComputeLineBreaks`. In words: **measuring and laying out text, and one binder call per update.** The
 * morph itself drew in 0.2 ms a frame. So the animation starves because the thread is busy re-measuring text
 * that did not change, and no amount of retuning the curves fixes that.
 *
 * Every gate here is allowed to fail only in one direction: it may miss a change that should have been applied
 * (the update then runs, as before), it may never invent one. That is why the tile check compares object
 * identity instead of titles - a re-posted notification unparcels new `Action` objects with new PendingIntents,
 * so an identity match is proof we already built exactly these tiles.
 */
object UpdateGate {

    /**
     * True when the view has to be told. Comparing the two strings costs nanoseconds; `setText` costs a
     * StaticLayout build, which is what the log is full of. `CharSequence.equals` on spanned text walks spans
     * (the `charAt`/`equals` entries in the same sample), so the length check goes first.
     */
    fun textChanged(current: CharSequence?, next: CharSequence?): Boolean {
        if (current === next) return false
        if (current == null || next == null) return true
        if (current.length != next.length) return true
        return current.toString() != next.toString()
    }

    /** Same list, same element instances, in order. See the class doc for why identity is the safe test. */
    fun <T> sameElements(a: List<T>?, b: List<T>?): Boolean {
        if (a === b) return true
        if (a == null || b == null) return false
        if (a.size != b.size) return false
        for (i in a.indices) if (a[i] !== b[i]) return false
        return true
    }
}
