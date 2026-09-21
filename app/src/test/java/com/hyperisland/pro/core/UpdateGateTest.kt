package com.hyperisland.pro.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule these tests protect is one-directional on purpose: a gate that wrongly says "unchanged" costs the
 * user a visible update - that is the kind of bug that comes back as "notification ka text update hi nahi
 * hua" - while a gate that wrongly says "changed" only costs the work we were already doing. So every test
 * below checks that a *possible* change gets through, and only provable non-changes get skipped.
 */
class UpdateGateTest {

    /** Stands in for spanned text: content equal, `equals` useless. This is what a TextView already holds. */
    private class Styled(private val s: String) : CharSequence {
        override val length get() = s.length
        override fun get(i: Int) = s[i]
        override fun subSequence(startIndex: Int, endIndex: Int) = s.subSequence(startIndex, endIndex)
        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
        override fun toString() = s
    }

    @Test
    fun identicalTextDoesNotReachTheView() {
        assertFalse(UpdateGate.textChanged("Hey", "Hey"))
        assertFalse(UpdateGate.textChanged(null, null))
    }

    @Test
    fun aMissingCurrentValueAlwaysReachesTheView() {
        assertTrue(UpdateGate.textChanged(null, "Hey"))
        assertTrue(UpdateGate.textChanged("Hey", null))
    }

    @Test
    fun sameLengthDifferentContentIsStillAChange() {
        assertTrue(UpdateGate.textChanged("Hey", "Hoy"))
        assertTrue(UpdateGate.textChanged("Hey", "Hey "))
        assertFalse(UpdateGate.textChanged("Hey ", "Hey"))
    }

    @Test
    fun spannedTextIsComparedByContentNotByEquals() {
        // The real case from the service: a freshly built SpannableString for the same title. Its own equals
        // would call the two different, so a naive check re-measures three spans on every update - 12 s of
        // nBuildMeasuredText in one 22 minute log.
        val a = Styled("Ali 2 new")
        val b = Styled("Ali 2 new")
        assertFalse(UpdateGate.textChanged(a, b))
        assertTrue(UpdateGate.textChanged(a, Styled("Ali 3 new")))
    }

    @Test
    fun tilesMatchOnlyWhenEveryActionIsTheSameObject() {
        val x = Any(); val y = Any()
        assertTrue(UpdateGate.sameElements(listOf(x, y), listOf(x, y)))
        assertTrue(UpdateGate.sameElements<Any>(null, null)) // explicit T: (null to null) has nothing to infer from
        // Equal content in new instances = a re-posted notification with new PendingIntents. Never skip that.
        val p: Any = StringBuilder("reply"); val q: Any = StringBuilder("reply")
        assertFalse(UpdateGate.sameElements(listOf(p), listOf(q)))
        assertFalse(UpdateGate.sameElements(listOf(x), listOf(x, y)))
        assertFalse(UpdateGate.sameElements(listOf(x, y), listOf(y, x)))
        assertFalse(UpdateGate.sameElements(null, listOf(x)))
    }
}
