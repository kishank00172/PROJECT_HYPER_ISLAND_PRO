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

class ShadeDismissSilencerPersistenceTest {
    @Test fun restoreKeepsLiveEntriesDropsExpired() {
        val s = ShadeDismissSilencer(windowMs = 90_000L)
        s.stamp("com.chrome.dev", 1000L); s.stamp("org.telegram.messenger", 1000L)
        val snap = s.snapshot()
        // process dies ~19s later (his: kill at 19:12:39, volley at :41) -> fresh process restores
        val s2 = ShadeDismissSilencer(windowMs = 90_000L)
        s2.restore(snap, 20_000L)
        org.junit.Assert.assertTrue(s2.isSilenced("com.chrome.dev", 20_100L))
        org.junit.Assert.assertTrue(s2.isSilenced("org.telegram.messenger", 90_999L))
        org.junit.Assert.assertFalse(s2.isSilenced("org.telegram.messenger", 91_001L))
    }

    @Test fun restorePrunesExpiredAtBorder() {
        val s = ShadeDismissSilencer(windowMs = 1000L)
        s.stamp("a", 0L)
        val s2 = ShadeDismissSilencer(windowMs = 1000L)
        s2.restore(s.snapshot(), now = 9999L)
        org.junit.Assert.assertEquals(0, s2.size)
        org.junit.Assert.assertFalse(s2.isSilenced("a", 9999L))
    }
}
