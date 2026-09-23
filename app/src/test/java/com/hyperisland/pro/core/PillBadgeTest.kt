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

    /**
     * .numericText(): the digits travel the way the value moved. Zero on no change is the part that matters -
     * it is what stops an animation for a number that did not move, and the badge is written more often than it
     * changes.
     */
    @Test fun theCountRollsInTheDirectionTheNumberMoved() {
        assertEquals(1f, PillBadge.rollDirection(3, 11), 0f)
        assertEquals(-1f, PillBadge.rollDirection(11, 3), 0f)
        assertEquals(0f, PillBadge.rollDirection(7, 7), 0f)
        // The badge earns a roll only between two visible numbers; 1 -> 2 is the appearance of the badge, not a
        // change of it, and the view has no height to travel over yet.
        assertEquals(1f, PillBadge.rollDirection(1, 2), 0f)
        assertEquals(0f, PillBadge.rollDirection(0, 0), 0f)
    }
}
