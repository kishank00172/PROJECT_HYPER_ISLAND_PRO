package com.hyperisland.pro.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationStackSignatureTest {
    private val k = "org.telegram.messenger|sender|VALI MODS 💀"

    @Test fun peel_isOldLayers() {
        val s = ConversationStackSignature()
        // stack as READ at wipe (bottom-up): older layers then the top message 'File'
        s.record(k, listOf("sale hoga kya", "Feedback send ❤️", "File"))
        // 18:40 peel: app re-posts with one layer less ('Feedback send ❤️' becomes the new top)
        assertEquals(ConversationStackSignature.Verdict.PEEL_OR_OLD, s.classify(k, listOf("sale hoga kya", "Feedback send ❤️")))
        assertEquals(ConversationStackSignature.Verdict.PEEL_OR_OLD, s.classify(k, listOf("File")))
        assertEquals(ConversationStackSignature.Verdict.PEEL_OR_OLD, s.classify(k, listOf("sale hoga kya")))
    }

    @Test fun newSuffix_isGenuinelyNew() {
        val s = ConversationStackSignature()
        s.record(k, listOf("Feedback send ❤️", "File"))
        assertEquals(ConversationStackSignature.Verdict.GENUINELY_NEW, s.classify(k, listOf("Feedback send ❤️", "File", "naya mod aa gaya")))
        assertEquals(ConversationStackSignature.Verdict.GENUINELY_NEW, s.classify(k, listOf("File", "naya mod")))
    }

    @Test fun unknownChat_andEmpty_areUnknown() {
        val s = ConversationStackSignature()
        assertEquals(ConversationStackSignature.Verdict.UNKNOWN, s.classify("nobody", listOf("hi")))
        s.record(k, listOf("x"))
        assertEquals(ConversationStackSignature.Verdict.UNKNOWN, s.classify(k, emptyList()))
        assertEquals(ConversationStackSignature.Verdict.UNKNOWN, s.classify(k, listOf("   ")))
    }

    @Test fun normalization_ignoresWhitespaceAndKeepsEmoji() {
        val s = ConversationStackSignature()
        s.record(k, listOf("Hello   world", "❤️ Sticker"))
        assertEquals(ConversationStackSignature.Verdict.PEEL_OR_OLD, s.classify(k, listOf("Hello world")))
        assertEquals(ConversationStackSignature.Verdict.PEEL_OR_OLD, s.classify(k, listOf("  ❤️ Sticker \n")))
    }

    @Test fun capacityFifo_andSnapshotRestore() {
        val s = ConversationStackSignature(capacity = 2)
        s.record("a", listOf("1")); s.record("b", listOf("2")); s.record("c", listOf("3"))
        assertEquals(ConversationStackSignature.Verdict.UNKNOWN, s.classify("a", listOf("1")))  // evicted
        val s2 = ConversationStackSignature(capacity = 2)
        s2.restore(s.snapshot())
        assertEquals(ConversationStackSignature.Verdict.PEEL_OR_OLD, s2.classify("c", listOf("3")))
        assertEquals(2, s2.size)
    }
}
