package com.hyperisland.pro.services

import java.util.Locale

/**
 * Suppresses the immediate notification echo that many chat apps post after an inline reply.
 * Example: user sends "okay" from Hyper Island, WhatsApp updates notification as "You: okay".
 * That update should not re-expand the island.
 */
object ReplyEchoSuppressor {
    private const val MATCH_WINDOW_MS = 2_000L
    private const val RETAIN_WINDOW_MS = 5_000L

    private data class PendingReplyEcho(
        val id: Long,
        val packageName: String,
        val conversationTitle: String,
        val replyText: String,
        val createdAt: Long
    )

    private val lock = Any()
    private val pending = ArrayList<PendingReplyEcho>()
    private var nextId = 1L

    fun recordSent(packageName: String?, conversationTitle: String?, replyText: String): Long {
        val pkg = packageName?.trim().orEmpty()
        val text = normalize(replyText)
        if (pkg.isBlank() || text.isBlank()) return -1L

        val now = System.currentTimeMillis()
        return synchronized(lock) {
            cleanupLocked(now)
            val id = nextId++
            pending.add(
                PendingReplyEcho(
                    id = id,
                    packageName = pkg,
                    conversationTitle = normalize(conversationTitle.orEmpty()),
                    replyText = text,
                    createdAt = now
                )
            )
            id
        }
    }

    fun clear(id: Long) {
        if (id < 0L) return
        synchronized(lock) {
            pending.removeAll { it.id == id }
        }
    }

    fun shouldSuppress(packageName: String, title: String, message: String): Boolean {
        val now = System.currentTimeMillis()
        val pkg = packageName.trim()
        val cleanTitle = normalize(title)
        val cleanMessage = normalize(message)
        val combined = normalize("$title $message")
        if (pkg.isBlank() || combined.isBlank()) return false

        return synchronized(lock) {
            cleanupLocked(now)
            val matchIndex = pending.indexOfFirst { entry ->
                isMatch(
                    entry = entry,
                    pkg = pkg,
                    cleanTitle = cleanTitle,
                    cleanMessage = cleanMessage,
                    combined = combined,
                    now = now
                )
            }

            if (matchIndex >= 0) {
                pending.removeAt(matchIndex)
                true
            } else {
                false
            }
        }
    }

    private fun isMatch(
        entry: PendingReplyEcho,
        pkg: String,
        cleanTitle: String,
        cleanMessage: String,
        combined: String,
        now: Long
    ): Boolean {
        if (entry.packageName != pkg) return false
        val age = now - entry.createdAt
        if (age !in 0L..MATCH_WINDOW_MS) return false

        val text = entry.replyText
        val textMatches = cleanMessage == text || cleanMessage.contains(text) || combined.contains(text)
        if (!textMatches) return false

        val sameConversation = entry.conversationTitle.isNotBlank() && cleanTitle == entry.conversationTitle
        val titleIsSelf = cleanTitle == "you" || cleanTitle == "me"
        val messageLooksSelf = cleanMessage.startsWith("you ") ||
            cleanMessage.startsWith("you:") ||
            cleanMessage.startsWith("me ") ||
            cleanMessage.startsWith("me:") ||
            cleanMessage.contains("you replied") ||
            cleanMessage.contains("you sent") ||
            cleanMessage.contains("sent")

        // Very tight window allows same-conversation exact text echo even if the app formats it simply.
        return sameConversation || titleIsSelf || messageLooksSelf || age <= 900L
    }

    private fun cleanupLocked(now: Long) {
        pending.removeAll { now - it.createdAt > RETAIN_WINDOW_MS }
    }

    private fun normalize(value: String): String {
        return value
            .lowercase(Locale.getDefault())
            .replace("\u200B", "")
            .replace("\u200C", "")
            .replace("\u200D", "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .removePrefix("you replied:")
            .removePrefix("you replied")
            .removePrefix("you sent:")
            .removePrefix("you sent")
            .removePrefix("you:")
            .removePrefix("me:")
            .trim()
    }
}
