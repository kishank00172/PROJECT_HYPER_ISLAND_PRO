package com.hyperisland.pro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One rule, his words, that used to be duplicated between the pill's update path and the preview path. The
 * interesting boundary is 1 vs 2 and it is where a regression would be invisible on a screenshot.
 */
class PillBadgeTest {

    @Test
    fun oneChatShowsNothingTwoShowTheDigit() {
        assertEquals("", PillBadge.textFor(0))
        assertEquals("", PillBadge.textFor(1))
        assertEquals("2", PillBadge.textFor(2))
        assertEquals("11", PillBadge.textFor(11))
        assertFalse(PillBadge.isShown(1))
        assertTrue(PillBadge.isShown(2))
    }
}
