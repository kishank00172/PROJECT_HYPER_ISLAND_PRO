package com.hyperisland.pro.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What this object is for: after a reply is sent from the island, the chat app posts its own notification of
 * what I just typed, and if nothing stops it, my own words come back at me as a card. His b1396 log has the
 * whole failure in eight lines - `send tapped text=hiii`, then 4.9 s later an INGEST titled 'You', a new page,
 * the count going 1 -> 2, and the pill re-lighting after it had already collapsed.
 *
 * The rules that keep it honest are the two-sided ones: a self marker is enough to drop, and anything that
 * relies on my text alone must match the whole message (never a substring) and be long enough to mean
 * something. Dropping a real message is worse than showing one echo, because the user cannot get it back.
 */
class ReplyEchoSuppressorTest {

    private val insta = "com.instagram.android"

    @Test fun `a short reply echoed under the name You is dropped`() {
        // The log verbatim: four characters, no "You:" prefix in the body, and the notification titled You.
        ReplyEchoSuppressor.recordSent(insta, "HANA", "hiii", "com.instagram.android|thread|kish.ank001")
        assertTrue(ReplyEchoSuppressor.shouldSuppress(insta, "You", "hiii"))
    }

    @Test fun `the memory is one-shot so the next real message survives`() {
        ReplyEchoSuppressor.recordSent(insta, "HANA", "hello there", "k1")
        assertTrue(ReplyEchoSuppressor.shouldSuppress(insta, "You", "hello there"))
        // Same words again, five seconds later, is a conversation - not an echo of a send.
        assertFalse(ReplyEchoSuppressor.shouldSuppress(insta, "HANA", "hello there"))
    }

    @Test fun `a real short message in the same thread is not my echo`() {
        ReplyEchoSuppressor.recordSent(insta, "HANA", "hi", "k2")
        // She sent exactly what I just sent. Titles decide it: hers is the thread, not "You", and "hi" is too
        // short to be evidence on its own - which is why the length rule stays, just not before the self marker.
        assertFalse(ReplyEchoSuppressor.shouldSuppress(insta, "HANA", "hi"))
    }

    @Test fun `containment is not a match`() {
        ReplyEchoSuppressor.recordSent(insta, "HANA", "ok", "k3")
        assertFalse(ReplyEchoSuppressor.shouldSuppress(insta, "HANA", "ok then see you at the gate"))
    }

    @Test fun `long identical text in the thread I replied to is an echo`() {
        ReplyEchoSuppressor.recordSent(insta, "HANA", "see you at the gate", "k4")
        assertTrue(ReplyEchoSuppressor.shouldSuppress(insta, "HANA", "see you at the gate"))
    }

    @Test fun `another app is never covered by my reply`() {
        ReplyEchoSuppressor.recordSent(insta, "HANA", "see you at the gate", "k5")
        assertFalse(ReplyEchoSuppressor.shouldSuppress("org.telegram.messenger", "You", "see you at the gate"))
    }

    @Test fun `a failed send gives the message back to the island`() {
        // clear(id) is what the catch block calls; after it nothing may be suppressed on that text.
        val id = ReplyEchoSuppressor.recordSent(insta, "HANA", "cancelled because it failed", "k6")
        ReplyEchoSuppressor.clear(id)
        assertFalse(ReplyEchoSuppressor.shouldSuppress(insta, "You", "cancelled because it failed"))
    }
}
