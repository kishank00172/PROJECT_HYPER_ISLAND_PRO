package com.hyperisland.pro.services

import android.app.Notification
import android.os.Bundle
import android.util.Log
import java.util.Locale

/**
 * Suppresses the immediate notification echo that chat apps post after an inline reply.
 * Example: user sends "okay" from Hyper Island, WhatsApp updates notification as "You: okay".
 * Instagram may update using the conversation name instead of "You".
 *
 * Matching priority:
 * 1) Same notification key + same text.
 * 2) MessagingStyle latest-message bundle signal.
 * 3) Instagram/personal exact-text fallback is intentionally aggressive for this personal build.
 * 4) You/Me marker fallback.
 * Keep this object shared: NotificationListener and Accessibility fallback both use it.
 */
object ReplyEchoSuppressor {
    private const val TAG = "HyperEchoSuppressor"
    private const val DEBUG_ECHO_DUMP = false

    private const val KEY_MATCH_WINDOW_MS = 4_500L
    private const val SAME_CONVERSATION_WINDOW_MS = 4_500L
    private const val SELF_MARKER_WINDOW_MS = 5_000L
    private const val IMMEDIATE_ECHO_WINDOW_MS = 800L

    // Personal-build Instagram rule:
    // Instagram re-posts my own notification reply without any "You:" marker, so text has to carry
    // the match. Two deliberate tightenings, both learned the hard way on device:
    //  - the window is short (an echo rides the same post, it does not arrive 9s later)
    //  - it never ignores the thread any more, and never matches on substrings (see MIN_ECHO_TEXT)
    // The old rule was "Instagram + my text appears anywhere inside the incoming message, within
    // 10s" -> reply "ok" and every incoming chat containing "ok" ("look at this") was deleted.
    // That is why whole stretches of Instagram DMs never reached the island.
    private const val PERSONAL_INSTAGRAM_ECHO_WINDOW_MS = 2_500L

    /** Below this many characters, text is not evidence of anything. Never suppress on it. */
    private const val MIN_ECHO_TEXT = 6

    private const val RETAIN_WINDOW_MS = 12_000L

    private const val KEY_TEXT = "text"
    private const val KEY_SENDER = "sender"
    private const val KEY_SENDER_PERSON = "sender_person"

    private data class PendingReplyEcho(
        val id: Long,
        val packageName: String,
        val notificationKey: String,
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

    fun recordSent(
        packageName: String?,
        conversationTitle: String?,
        replyText: String,
        notificationKey: String? = null
    ): Long {
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
                    notificationKey = notificationKey?.trim().orEmpty(),
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
            notification = null,
            notificationKey = null
        )
    }

    fun shouldSuppress(
        packageName: String,
        title: String,
        message: String,
        notification: Notification?,
        notificationKey: String? = null
    ): Boolean {
        return shouldSuppressInternal(
            packageName = packageName,
            title = title,
            message = message,
            notification = notification,
            notificationKey = notificationKey
        )
    }

    private fun shouldSuppressInternal(
        packageName: String,
        title: String,
        message: String,
        notification: Notification?,
        notificationKey: String?
    ): Boolean {
        val now = System.currentTimeMillis()
        val pkg = packageName.trim()
        val incomingKey = notificationKey?.trim().orEmpty()
        val baseTitle = normalizeBase(title)
        val baseMessage = normalizeBase(message)
        val cleanTitle = stripSelfPrefixes(baseTitle)
        val cleanMessage = stripSelfPrefixes(baseMessage)
        val combinedClean = stripSelfPrefixes(normalizeBase("$title $message"))
        val latestMessagingMessage = extractLatestMessagingMessage(notification)

        if (DEBUG_ECHO_DUMP) {
            dumpNotificationDebugInfo(pkg, title, message, notification, incomingKey)
        }

        if (pkg.isBlank() || (combinedClean.isBlank() && latestMessagingMessage?.text.isNullOrBlank())) return false

        return synchronized(lock) {
            cleanupLocked(now)
            val matchIndex = pending.indexOfFirst { entry ->
                isMatch(
                    entry = entry,
                    pkg = pkg,
                    incomingKey = incomingKey,
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
                val hit = pending[matchIndex]
                pending.removeAt(matchIndex)
                // One line per suppression, always on: "a message vanished and nobody knew why" was
                // the actual bug report. Filtered out of release noise by level if that ever matters.
                Log.i(TAG, "SUPPRESSED echo pkg=$pkg thread=\"$title\" text=\"${message.take(60)}\" " +
                    "sent=\"${hit.replyText.take(60)}\" ageMs=${now - hit.createdAt}")
                true
            } else {
                false
            }
        }
    }

    private fun isMatch(
        entry: PendingReplyEcho,
        pkg: String,
        incomingKey: String,
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
        if (sentText.length < MIN_ECHO_TEXT) return false // "ok", "hi", "\ud83d\udc4d" match half the language
        val latestTextMatches = latestMessagingMessage?.text == sentText
        val normalTextMatches = cleanMessage == sentText || cleanTitle == sentText
        // Same thread guard: an echo rides the notification of the thread I replied from. If the
        // incoming notification belongs to a different thread, it is a real message, not my echo.
        val sameThread = entry.conversationTitle.isBlank() || baseTitle == entry.conversationTitle

        if (!latestTextMatches && !normalTextMatches) return false

        // Personal-build Instagram override:
        // If I replied from Instagram notification and the exact same text comes back within 10 seconds,
        // suppress it even if Instagram does not use "You:" and even if title formatting changes.
        val isInstagram = entry.packageName.contains("instagram", ignoreCase = true) || pkg.contains("instagram", ignoreCase = true)
        val instagramTextMatches = (normalTextMatches || latestTextMatches) && sameThread
        if (isInstagram && instagramTextMatches && age <= PERSONAL_INSTAGRAM_ECHO_WINDOW_MS) {
            return true
        }

        // Tier 1: exact notification thread key. This is the strongest signal.
        val keyMatches = entry.notificationKey.isNotBlank() && incomingKey.isNotBlank() && entry.notificationKey == incomingKey
        if (keyMatches && age <= KEY_MATCH_WINDOW_MS) return true

        // Tier 2: semantic MessagingStyle signal.
        if (latestTextMatches && latestMessagingMessage != null) {
            val senderName = latestMessagingMessage.senderName
            val senderIsSelfLike = latestMessagingMessage.senderWasNull || senderName == "you" || senderName == "me"
            if (senderIsSelfLike && age <= SELF_MARKER_WINDOW_MS) return true
        }

        // Tier 3: same app + identical sent text within 4.5s, and only for the same thread.
        if (normalTextMatches && sameThread && age <= SAME_CONVERSATION_WINDOW_MS) return true

        // Tier 4: explicit WhatsApp/Telegram self markers.
        val titleIsSelf = baseTitle == "you" || baseTitle == "me"
        val messageLooksSelf = baseMessage.startsWith("you ") ||
            baseMessage.startsWith("you:") ||
            baseMessage.startsWith("me ") ||
            baseMessage.startsWith("me:") ||
            baseMessage.contains("you replied") ||
            baseMessage.contains("you sent") ||
            baseMessage.contains("sent")
        if ((titleIsSelf || messageLooksSelf) && age <= SELF_MARKER_WINDOW_MS) return true

        return age <= IMMEDIATE_ECHO_WINDOW_MS
    }

    private fun extractLatestMessagingMessage(notification: Notification?): LatestMessagingMessage? {
        return try {
            val extras = notification?.extras ?: return null
            @Suppress("DEPRECATION")
            val rawMessages = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return null
            val latestBundle = rawMessages.asSequence().mapNotNull { it as? Bundle }.lastOrNull() ?: return null
            val text = normalizeMessageText(readBundleText(latestBundle))
            if (text.isBlank()) return null

            val legacySender = normalizeBase(readBundleSender(latestBundle))
            val personSender = normalizeBase(readPersonNameFromBundle(latestBundle))
            val senderName = personSender.ifBlank { legacySender }
            val senderWasNull = senderName.isBlank()

            LatestMessagingMessage(
                text = text,
                senderName = senderName,
                senderWasNull = senderWasNull
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun readBundleText(bundle: Bundle): String {
        return try {
            (bundle.getCharSequence(KEY_TEXT)
                ?: bundle.getCharSequence("android.text")
                ?: bundle.getCharSequence("message")
                ?: bundle.getCharSequence("message_text")
                ?: "").toString()
        } catch (_: Exception) {
            ""
        }
    }

    private fun readBundleSender(bundle: Bundle): String {
        return try {
            (bundle.getCharSequence(KEY_SENDER)
                ?: bundle.getCharSequence("android.sender")
                ?: bundle.getCharSequence("sender_name")
                ?: "").toString()
        } catch (_: Exception) {
            ""
        }
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

    private fun dumpNotificationDebugInfo(
        pkg: String,
        title: String,
        message: String,
        notification: Notification?,
        notificationKey: String
    ) {
        try {
            Log.e(TAG, "=== ECHO DUMP START: $pkg ===")
            Log.e(TAG, "Title=$title | Message=$message | Key=$notificationKey")
            val extras = notification?.extras
            if (extras == null) {
                Log.e(TAG, "Extras=NULL")
                return
            }
            extras.keySet().forEach { key ->
                val value = try { extras.get(key) } catch (_: Exception) { null }
                Log.e(TAG, "EXTRA $key = $value (${value?.javaClass?.simpleName})")
            }
            @Suppress("DEPRECATION")
            val messages = try { extras.getParcelableArray(Notification.EXTRA_MESSAGES) } catch (_: Exception) { null }
            messages?.forEachIndexed { index, item ->
                val bundle = item as? Bundle
                if (bundle == null) {
                    Log.e(TAG, "MSG[$index] non-bundle = ${item?.javaClass?.simpleName}")
                } else {
                    Log.e(TAG, "MSG[$index] keys:")
                    bundle.keySet().forEach { key ->
                        val value = try { bundle.get(key) } catch (_: Exception) { null }
                        Log.e(TAG, "  $key = $value (${value?.javaClass?.simpleName})")
                    }
                }
            }
            Log.e(TAG, "=== ECHO DUMP END ===")
        } catch (_: Exception) {
            // Debug logger must never crash notification listener.
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
