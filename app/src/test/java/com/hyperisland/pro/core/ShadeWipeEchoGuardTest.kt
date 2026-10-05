package com.hyperisland.pro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** b1497 semantics lock: identical echo dropped, new content of the same chat allowed. */
class ShadeWipeEchoGuardTest {
    private fun p(key: String) = Triple(key, "VIJAY TRADER", "❤️ Sticker")

    @Test fun identicalEchoDropped() {
        val g = ShadeWipeEchoGuard()
        g.recordWipe(listOf(p("org.telegram.messenger|sender|VIJAY")))
        assertTrue(g.shouldDrop("org.telegram.messenger|sender|VIJAY", "VIJAY TRADER", "❤️ Sticker"))
        assertTrue(g.shouldDrop("org.telegram.messenger|sender|VIJAY", "VIJAY TRADER", "❤️ Sticker"))  // stays suppressed
    }

    @Test fun newContentPasses_andForgetKey() {
        val g = ShadeWipeEchoGuard()
        g.recordWipe(listOf(p("k")))
        assertFalse(g.shouldDrop("k", "VIJAY TRADER", "naya message aa gaya"))
        assertFalse(g.shouldDrop("k", "VIJAY TRADER", "naya message aa gaya"))  // key forgotten -> normal life
    }

    @Test fun unknownKeyPasses() {
        assertFalse(ShadeWipeEchoGuard().shouldDrop("nobody", "t", "m"))
    }

    @Test fun capacityFifo() {
        val g = ShadeWipeEchoGuard(capacity = 2)
        g.recordWipe(listOf(Triple("a", "ta", "ma"), Triple("b", "tb", "mb")))
        g.recordWipe(listOf(Triple("c", "tc", "mc")))
        assertEquals(2, g.size)
        assertFalse(g.shouldDrop("a", "ta", "ma"))   // evicted oldest
        assertTrue(g.shouldDrop("c", "tc", "mc"))
    }
}
