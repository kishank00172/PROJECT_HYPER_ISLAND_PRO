package com.hyperisland.pro.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShadeDismissSilencerTest {
    @Test fun stampedPkgSilenced_forWindow_thenExpires() {
        val s = ShadeDismissSilencer(windowMs = 90_000L)
        s.stamp("org.telegram.messenger", 1000L)
        assertTrue(s.isSilenced("org.telegram.messenger", 1000L + 19_000L))   // his 19s repost volley
        assertTrue(s.isSilenced("org.telegram.messenger", 1000L + 89_999L))
        assertFalse(s.isSilenced("org.telegram.messenger", 1000L + 90_001L))
        assertFalse(s.isSilenced("org.telegram.messenger", 200_000L))          // still gone (purged)
    }

    @Test fun otherPackagesUnaffected() {
        val s = ShadeDismissSilencer()
        s.stamp("a", 0L)
        assertFalse(s.isSilenced("b", 1L))
    }

    @Test fun restampExtendsWindow() {
        val s = ShadeDismissSilencer(windowMs = 1000L)
        s.stamp("a", 0L); s.stamp("a", 900L)
        assertTrue(s.isSilenced("a", 1800L))
        assertFalse(s.isSilenced("a", 1901L))
    }
}
