package com.hyperisland.pro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Phase 3.6 Target 2 validation for conversation identity.
 *
 * These are the tests that the whole "same chat split into 3 swipe items" bug survived for,
 * because nothing here was unit testable while the logic sat inside a StatusBarNotification.
 * CI runs them via :app:testDebugUnitTest — no phone needed.
 */
class ConversationIdentityTest {

    private fun id(
        pkg: String = "com.instagram.android",
        shortcut: String = "",
        people: String = "",
        conv: String = "",
        tag: String = "",
        nid: Int = 1,
        sender: String = "",
        title: String = ""
    ) = ConversationIdentity.build(pkg, shortcut, people, conv, tag, nid, sender, title)

    /** THE regression: a fresh notification id per message must NOT create a new thread. */
    @Test
    fun sameChatWithPerMessageNotificationIdsMerges() {
        val first = id(nid = 101, sender = "Ram", title = "Ram")
        val second = id(nid = 4827, sender = "Ram", title = "Ram")
        val third = id(nid = 90311, sender = "Ram", title = "Ram")
        assertEquals(first.first, second.first)
        assertEquals(second.first, third.first)
        assertEquals("sender", first.second) // sender == title here, so the pair rule does not apply
    }

    @Test
    fun senderPlusTitlePairIsUsedWhenTheyDiffer() {
        val a = id(sender = "Ram", title = "Project group")
        val b = id(nid = 9991, sender = "Ram", title = "Project group")
        assertEquals("sender+title", a.second)
        assertEquals(a.first, b.first)
        assertNotEquals(a.first, id(sender = "Ram", title = "Other group").first)
    }

    @Test
    fun shortcutIdIsPreferredAndStable() {
        val a = id(shortcut = "direct:555123", nid = 7, sender = "Ram", title = "Ram")
        val b = id(shortcut = "direct:555123", nid = 7777, sender = "Ram", title = "Ram")
        assertEquals(a.first, b.first)
        assertEquals("shortcutId", a.second)
    }

    @Test
    fun peopleSeparatesTwoContactsWithTheSameDisplayName() {
        val ram1 = id(people = "555111", sender = "Ram", title = "Ram")
        val ram2 = id(people = "555999", sender = "Ram", title = "Ram")
        assertNotEquals(ram1.first, ram2.first)
        assertEquals("people", ram1.second)
    }

    /** Apps bake "(N messages)" into the title; if that leaked into the key the thread would move. */
    @Test
    fun trailingMessageCountDoesNotSplitAThread() {
        val before = ConversationIdentity.stripMessageCountSuffix("Ram (3 messages)")
        val after = ConversationIdentity.stripMessageCountSuffix("Ram (9 messages)")
        assertEquals("Ram", before)
        assertEquals("Ram", after)
        assertEquals(id(title = before).first, id(title = after).first)
    }

    @Test
    fun stripOnlyTouchesTheTrailingCount() {
        assertEquals("Ram (2) hi", ConversationIdentity.stripMessageCountSuffix("Ram (2) hi"))
        assertEquals("10 messages sent", ConversationIdentity.stripMessageCountSuffix("10 messages sent"))
    }

    @Test
    fun caseAndWhitespaceAreFolded() {
        assertEquals(id(sender = "Ram", title = "Ram").first, id(sender = "  rAM ", title = "RAM").first)
    }

    @Test
    fun fallbacksStayDistinctAndLabelled() {
        assertEquals("id+tag", id(tag = "android.title:xyz", nid = 3).second)
        assertEquals("idFallback", id(nid = 3).second)
        assertNotEquals(id(tag = "a", nid = 3).first, id(tag = "b", nid = 3).first)
    }

    @Test
    fun differentPackagesNeverCollide() {
        val ig = id(pkg = "com.instagram.android", sender = "Ram", title = "Ram")
        val wa = id(pkg = "com.whatsapp", sender = "Ram", title = "Ram")
        assertNotEquals(ig.first, wa.first)
    }

    @Test
    fun normalizeCollapsesAllWhitespace() {
        assertEquals("a b c", ConversationIdentity.normalize("  A\t\nB   c "))
    }

    /** The exact shape the 2026-09-19 device capture shows for an Instagram DM. */
    @Test
    fun instagramShapedRecordsMergeOnTheirRealShortcutId() {
        val a = id(shortcut = "thread_shortcut_59789964840_3402823668417", nid = 64278,
            sender = "Homieees!!\ud83d\udc31", title = "(kish.ank001) Homieees!!\ud83d\udc31: @shikhakumari320")
        val b = id(shortcut = "thread_shortcut_59789964840_3402823668417", nid = 64279,
            sender = "Homieees!!\ud83d\udc31", title = "(kish.ank001) Homieees!!\ud83d\udc31: @shikhakumari320")
        assertEquals("shortcutId", a.second)
        assertEquals(a.first, b.first)
    }

    /**
     * A group thread puts the SENDER in the title, so sender+title would create one card per person in
     * the same chat. android.conversationTitle names the thread and survives the sender changing.
     */
    @Test
    fun groupThreadStaysOneCardWhenTheSenderChanges() {
        val one = id(pkg = "com.whatsapp", conv = "Chat + Fun Vibes", sender = "Ri", title = "Ri")
        val two = id(pkg = "com.whatsapp", conv = "Chat + Fun Vibes", sender = "Kishan", title = "Kishan")
        assertEquals("conversationTitle", one.second)
        assertEquals(one.first, two.first)
    }

    /**
     * WhatsApp bakes the unread count into conversationTitle ("(18 messages)"). If that leaked into the
     * key, every new message would fork the thread and the ring would grow a page per message.
     */
    @Test
    fun bakedInMessageCountIsStrippedFromTheConversationTitle() {
        val before = id(pkg = "com.whatsapp", conv = "Chat + Fun Vibes (17 messages)", title = "Ri", sender = "Ri")
        val after = id(pkg = "com.whatsapp", conv = "Chat + Fun Vibes (18 messages)", title = "Ri", sender = "Ri")
        assertEquals(before.first, after.first)
        assertEquals("conversationTitle", before.second)
    }

    @Test
    fun shortcutIdStillBeatsTheConversationTitle() {
        val a = id(shortcut = "thread_shortcut_1", conv = "Chat + Fun Vibes")
        assertEquals("shortcutId", a.second)
    }
}
