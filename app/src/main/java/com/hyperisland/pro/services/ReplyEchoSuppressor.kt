package com.hyperisland.pro.services

import android.app.Notification
import android.os.Bundle
import java.util.Locale

/**
 * Suppresses the immediate notification echo that chat apps post after an inline reply.
 * Example: user sends "okay" from Hyper Island, WhatsApp updates notification as "You: okay".
 * Instagram may update using the conversation name instead of "You".
 *
 * Architecture:
 * 1) Try semantic MessagingStyle latest-message bundle detection first.
 * 2) Fall back to personal-build title/text matching.
 * 3) Fall back to You/Me marker matching.
 *
 * Note: We intentionally parse MessagingStyle message bundles manually instead of calling
 * Notification.MessagingStyle.Message.getMessageFromBundle(), because that helper is not
 * available in all compile SDK stubs used by CI.
 */
object ReplyEchoSuppressor {
    private const val SAME_CONVERSATION_WINDOW_MS = 3_000L
    private const val SELF_MARKER_WINDOW_MS = 5_000L
    private const val IMMEDIATE_ECHO_WINDOW_MS = 800L
    private const val RETAIN_WINDOW_MS = 6_000L

    private const val KEY_TEXT = "text"
    private const val KEY_SENDER = "sender"
    private const val KEY_SENDER_PERSON = "sender_person"

    private data class PendingReplyEcho(
        val id: Long,
        val packageName: String,
        val conversationTitle: String,
        val replyText: String,
        val createdAt: Long
    )

    private data class LatestMessagingMessage(
        val text: String,
        val senderName: String,
        val senderWasNull: Boolean
    )

    private val lock = Any()
    private val pending = ArrayList<PendingReplyEcho>()
    private var nextId = 1L

    fun recordSent(packageName: String?, conversationTitle: String?, replyText: String): Long {
        val pkg = packageName?.trim().orEmpty()
        val text = normalizeMessageText(replyText)
        if (pkg.isBlank() || text.isBlank()) return -1L

        val now = System.currentTimeMillis()
        return synchronized(lock) {
            cleanupLocked(now)
            val id = nextId++
            pending.add(
                PendingReplyEcho(
                    id = id,
                    packageName = pkg,
                    conversationTitle = normalizeBase(conversationTitle.orEmpty()),
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
        return shouldSuppressInternal(
            packageName = packageName,
            title = title,
            message = message,
            notification = null
        )
    }

    fun shouldSuppress(packageName: String, title: String, message: String, notification: Notification?): Boolean {
        return shouldSuppressInternal(
            packageName = packageName,
            title = title,
            message = message,
            notification = notification
        )
    }

    private fun shouldSuppressInternal(
        packageName: String,
        title: String,
        message: String,
        notification: Notification?
    ): Boolean {
        val now = System.currentTimeMillis()
        val pkg = packageName.trim()
        val baseTitle = normalizeBase(title)
        val baseMessage = normalizeBase(message)
        val cleanTitle = stripSelfPrefixes(baseTitle)
        val cleanMessage = stripSelfPrefixes(baseMessage)
        val combinedClean = stripSelfPrefixes(normalizeBase("$title $message"))
        val latestMessagingMessage = extractLatestMessagingMessage(notification)

        if (pkg.isBlank() || (combinedClean.isBlank() && latestMessagingMessage?.text.isNullOrBlank())) return false

        return synchronized(lock) {
            cleanupLocked(now)
            val matchIndex = pending.indexOfFirst { entry ->
                isMatch(
                    entry = entry,
                    pkg = pkg,
                    baseTitle = baseTitle,
                    baseMessage = baseMessage,
                    cleanTitle = cleanTitle,
                    cleanMessage = cleanMessage,
                    combinedClean = combinedClean,
                    latestMessagingMessage = latestMessagingMessage,
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
        baseTitle: String,
        baseMessage: String,
        cleanTitle: String,
        cleanMessage: String,
        combinedClean: String,
        latestMessagingMessage: LatestMessagingMessage?,
        now: Long
    ): Boolean {
        if (entry.packageName != pkg) return false
        val age = now - entry.createdAt
        if (age !in 0L..RETAIN_WINDOW_MS) return false

        val sentText = entry.replyText
        val latestTextMatches = latestMessagingMessage?.text?.let { latestText ->
            latestText == sentText || latestText.contains(sentText)
        } == true
        val normalTextMatches = cleanMessage == sentText ||
            cleanMessage.contains(sentText) ||
            cleanTitle == sentText ||
            combinedClean.contains(sentText)

        if (!latestTextMatches && !normalTextMatches) return false

        // Tier 1: semantic MessagingStyle signal.
        // Many inline reply echoes are represented as latest message with null/self sender.
        if (latestTextMatches && latestMessagingMessage != null) {
            val senderName = latestMessagingMessage.senderName
            val senderIsSelfLike = latestMessagingMessage.senderWasNull || senderName == "you" || senderName == "me"
            if (senderIsSelfLike && age <= SELF_MARKER_WINDOW_MS) return true
        }

        // Tier 2: personal-build Instagram-style echo.
        // Instagram may keep title as conversation name instead of using "You".
        val sameConversation = entry.conversationTitle.isNotBlank() && baseTitle == entry.conversationTitle
        if (sameConversation && age <= SAME_CONVERSATION_WINDOW_MS) return true

        // Tier 3: WhatsApp/Telegram-style explicit self markers.
        val titleIsSelf = baseTitle == "you" || baseTitle == "me"
        val messageLooksSelf = baseMessage.startsWith("you ") ||
            baseMessage.startsWith("you:") ||
            baseMessage.startsWith("me ") ||
            baseMessage.startsWith("me:") ||
            baseMessage.contains("you replied") ||
            baseMessage.contains("you sent") ||
            baseMessage.contains("sent")
        if ((titleIsSelf || messageLooksSelf) && age <= SELF_MARKER_WINDOW_MS) return true

        // Tier 4: ultra-fast exact echo fallback.
        return age <= IMMEDIATE_ECHO_WINDOW_MS
    }

    private fun extractLatestMessagingMessage(notification: Notification?): LatestMessagingMessage? {
        val extras = notification?.extras ?: return null
        @Suppress("DEPRECATION")
        val rawMessages = try {
            extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        } catch (_: Exception) {
            null
        } ?: return null

        val latestBundle = rawMessages.asSequence().mapNotNull { it as? Bundle }.lastOrNull() ?: return null
        val text = normalizeMessageText(readBundleText(latestBundle))
        if (text.isBlank()) return null

        val legacySender = normalizeBase(readBundleSender(latestBundle))
        val personSender = normalizeBase(readPersonNameFromBundle(latestBundle))
        val senderName = personSender.ifBlank { legacySender }
        val senderWasNull = senderName.isBlank()

        return LatestMessagingMessage(
            text = text,
            senderName = senderName,
            senderWasNull = senderWasNull
        )
    }

    private fun readBundleText(bundle: Bundle): String {
        return (bundle.getCharSequence(KEY_TEXT)
            ?: bundle.getCharSequence("android.text")
            ?: bundle.getCharSequence("message")
            ?: bundle.getCharSequence("message_text")
            ?: "").toString()
    }

    private fun readBundleSender(bundle: Bundle): String {
        return (bundle.getCharSequence(KEY_SENDER)
            ?: bundle.getCharSequence("android.sender")
            ?: bundle.getCharSequence("sender_name")
            ?: "").toString()
    }

    private fun readPersonNameFromBundle(bundle: Bundle): String {
        val personObject = try {
            @Suppress("DEPRECATION")
            bundle.get(KEY_SENDER_PERSON)
        } catch (_: Exception) {
            null
        } ?: return ""

        return try {
            val method = personObject.javaClass.methods.firstOrNull { it.name == "getName" && it.parameterTypes.isEmpty() }
            method?.invoke(personObject)?.toString().orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    private fun cleanupLocked(now: Long) {
        pending.removeAll { now - it.createdAt > RETAIN_WINDOW_MS }
    }

    private fun normalizeMessageText(value: String): String {
        return stripSelfPrefixes(normalizeBase(value))
    }

    private fun stripSelfPrefixes(value: String): String {
        return value
            .removePrefix("you replied:")
            .removePrefix("you replied")
            .removePrefix("you sent:")
            .removePrefix("you sent")
            .removePrefix("you:")
            .removePrefix("me:")
            .trim()
    }

    private fun normalizeBase(value: String): String {
        return value
            .lowercase(Locale.getDefault())
            .replace("\u200B", "")
            .replace("\u200C", "")
            .replace("\u200D", "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
