package com.hyperisland.pro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Display rules for chat cards, fixed with the real values from the 2026-09-19 device capture
 * (notification-lab `Exports/notification_raw.jsonl (2)`). These are the four things the tester
 * reported by eye on b1325 — wrong name, "Instagram" as the headline, old messages stamped "now",
 * and no quick actions — and three of them are decided here. (The fourth was the accessibility
 * fallback inventing a card at all; it has no actions to copy, so it could never render tiles.)
 */
class ChatDisplayPolicyTest {

    /** seq 10: Instagram titled the card with the last sender, which was the tester himself. */
    @Test
    fun ourOwnMessageIsHeadedYouAndNotOurName() {
        val title = ChatDisplayPolicy.displayTitle(
            threadTitle = "",
            extraTitle = "Kishan Kumar",
            sender = "Kishan Kumar",
            selfDisplayName = "Kishan Kumar",
            appName = "Instagram"
        )
        assertEquals("You", title)
        assertTrue(ChatDisplayPolicy.isSelf("Kishan Kumar", "Kishan Kumar"))
    }

    /** A group thread keeps its name even when the newest message is ours: "You" would be a loss. */
    @Test
    fun aThreadNameOutranksTheYouLabel() {
        val title = ChatDisplayPolicy.displayTitle(
            threadTitle = "Chat + Fun Vibes\u2764",
            extraTitle = "Kishan Kumar",
            sender = "Kishan Kumar",
            selfDisplayName = "Kishan Kumar",
            appName = "WhatsApp"
        )
        assertEquals("Chat + Fun Vibes\u2764", title)
    }

    /** seq 9: the reply from Meta AI must read "Meta AI", not the app name and not "You". */
    @Test
    fun thePeerNameIsUsedWhenTheReplyComesBack() {
        val title = ChatDisplayPolicy.displayTitle(
            threadTitle = "",
            extraTitle = "Meta AI",
            sender = "Meta AI",
            selfDisplayName = "Kishan Kumar",
            appName = "Instagram"
        )
        assertEquals("Meta AI", title)
        assertFalse(ChatDisplayPolicy.isSelf("Meta AI", "Kishan Kumar"))
    }

    /** seq 85: group threads arrive as "(kish.ank001) Homieees!!" — our handle is not the thread. */
    @Test
    fun ownHandlePrefixIsTrimmedFromGroupTitles() {
        val title = ChatDisplayPolicy.displayTitle(
            threadTitle = "(kish.ank001) Homieees!!🐱",
            extraTitle = "(kish.ank001) Homieees!!🐱: @shikhakumari320",
            sender = "@shikhakumari320",
            selfDisplayName = "Kishan Kumar",
            appName = "Instagram"
        )
        assertEquals("Homieees!!🐱", title)
    }

    /** A card headed with the app name tells you nothing; the sender does. */
    @Test
    fun appNameIsNeverTheHeadlineWhenASenderExists() {
        val title = ChatDisplayPolicy.displayTitle(
            threadTitle = "",
            extraTitle = "Instagram",
            sender = "Adarsh Kumar Jha",
            selfDisplayName = "Kishan Kumar",
            appName = "Instagram"
        )
        assertEquals("Adarsh Kumar Jha", title)
    }

    @Test
    fun nothingChat-shapedFallsBackToMessage() {
        assertEquals("Message", ChatDisplayPolicy.displayTitle("", "", "", "", ""))
    }

    /** WhatsApp bakes the count into the title; it belongs to the badge, not the headline. */
    @Test
    fun bakedInMessageCountIsTrimmedFromTheHeadline() {
        val title = ChatDisplayPolicy.displayTitle(
            threadTitle = "Chat + Fun Vibes❤️ (18 messages)",
            extraTitle = "Chat + Fun Vibes❤️ (18 messages): ~گuj",
            sender = "~گuj",
            selfDisplayName = "",
            appName = "WhatsApp"
        )
        assertEquals("Chat + Fun Vibes❤️", title)
    }

    /** seq 8: Instagram's array is not time-ordered, so "last element" is the wrong message. */
    @Test
    fun newestMessageIsChosenByTimeNotByArrayPosition() {
        val times = listOf(1789795231267L, 1789796507205L, 1789796602962L, 1789796595013L, 1789796798954L, 1789797144728L)
        val texts = listOf("Sent a reel by mahak__19____", "Sent a reel by i.kaif_____", "khatam kro behas", "bhai khatam kro", "Dusra topic lelo kuch", "Reacted 😂 to your message")
        assertEquals(5, ChatDisplayPolicy.newestIndex(times, texts, "Reacted 😂 to your message: New topic 🤣??"))
        // Same bundle, but the app appended an out-of-order entry: still the newest by time.
        val shuffled = listOf(1789796602962L, 1789797144728L, 1789795231267L)
        val shuffledTexts = listOf("older", "newest", "oldest")
        assertEquals(1, ChatDisplayPolicy.newestIndex(shuffled, shuffledTexts, ""))
    }

    /**
     * seq 85: IG pads the array with an empty entry — "Sent a reel", "", "Sent a reel" at one and the
     * same timestamp. Taking it literally dropped the card out of the MessagingStyle branch, which is
     * how a headline of "Instagram" with no actions appeared.
     */
    @Test
    fun blankTrailingEntriesAreSkipped() {
        val times = listOf(1789804625087L, 1789804625087L, 1789804625087L)
        val texts = listOf("Sent a reel", "", "Sent a reel")
        val idx = ChatDisplayPolicy.newestIndex(times, texts, "Sent a reel")
        assertEquals(0, idx)
        assertEquals(-1, ChatDisplayPolicy.newestIndex(listOf(1L, 2L), listOf("", "  "), ""))
    }

    /** seq 85 again: postTime landed 12.6 min after the message. Stamping with it said "now". */
    @Test
    fun theMessageTimeWinsOverThePostTime() {
        val whenMs = 1789804625087L
        val postTime = 1789805380355L
        val now = postTime + 2_000L
        val stamp = ChatDisplayPolicy.displayTimeMs(whenMs, whenMs, postTime, now)
        assertEquals(whenMs, stamp)
        assertEquals("12 min", ChatDisplayPolicy.timeLabel(stamp, now, true, "7:31 PM", "19 Sep"))
    }

    @Test
    fun bogusMessageTimesFallBackToThePostTime() {
        val now = 1_789_805_400_000L
        assertEquals(now - 1_000L, ChatDisplayPolicy.displayTimeMs(0L, 0L, now - 1_000L, now))
        // A clock that jumped forward must not date the card in the future.
        assertEquals(now - 500L, ChatDisplayPolicy.displayTimeMs(now + 900_000L, 0L, now - 500L, now))
    }

    @Test
    fun labelsAgeFromFreshToDated() {
        val now = 1_789_805_400_000L
        assertEquals("now", ChatDisplayPolicy.timeLabel(now - 30_000L, now, true, "7:31 PM", "19 Sep"))
        assertEquals("59 min", ChatDisplayPolicy.timeLabel(now - 59L * 60_000L, now, true, "7:31 PM", "19 Sep"))
        assertEquals("6:31 PM", ChatDisplayPolicy.timeLabel(now - 60L * 60_000L, now, true, "6:31 PM", "19 Sep"))
        assertEquals("7:31 PM", ChatDisplayPolicy.timeLabel(now - 20L * 3_600_000L, now, true, "7:31 PM", "19 Sep"))
        assertEquals("Yesterday", ChatDisplayPolicy.timeLabel(now - 20L * 3_600_000L, now, false, "7:31 PM", "19 Sep"))
        assertEquals("18 Sep", ChatDisplayPolicy.timeLabel(now - 3L * 86_400_000L, now, false, "7:31 PM", "18 Sep"))
    }
}
