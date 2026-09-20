package com.hyperisland.pro.services

import android.app.Notification
import android.content.Context
import android.os.Bundle
import android.service.notification.StatusBarNotification
import com.hyperisland.pro.core.ChatDisplayPolicy
import com.hyperisland.pro.core.ConversationIdentity

/**
 * Phase 3.6 — Notification Intelligence (latest-message extraction).
 * Extracts latest chat message from MessagingStyle bundles when possible; unread count and conversation identity are returned separately.
 * Falls back to normal EXTRA_TITLE / EXTRA_TEXT for non-chat notifications.
 */
object NotificationContentExtractor {
    data class ExtractedNotificationContent(
        val appName: String,
        val conversationTitle: String,
        val senderName: String,
        val latestMessage: String,
        val unreadCount: Int,
        val isMessagingStyle: Boolean,
        val conversationKey: String,
        val conversationKeySource: String,
        /** What the card is stamped with: the newest message's own time when the app gives one. */
        val displayTimeMs: Long
    )

    fun extract(context: Context, sbn: StatusBarNotification): ExtractedNotificationContent? {
        val pkg = sbn.packageName ?: return null
        val notification = sbn.notification ?: return null
        val extras = notification.extras ?: return null
        val appName = getAppName(context, pkg)
        val now = System.currentTimeMillis()

        val fallbackTitle = (extras.getCharSequence(Notification.EXTRA_TITLE) ?: "").toString().trim()
        val fallbackText = (extras.getCharSequence(Notification.EXTRA_TEXT) ?: "").toString().trim()
        val threadTitle = readThreadTitle(extras)
        // Instagram publishes its own display name under android.selfDisplayName, which is the only
        // reliable way to tell "they wrote to you" apart from "you wrote to them" (capture seq 10: a
        // message the tester's own AI sent was titled with his own name, because EXTRA_TITLE on
        // Instagram means "last sender", not "conversation"). Nothing used to read it.
        val selfName = (extras.getCharSequence("android.selfDisplayName") ?: "").toString().trim()

        val messages = extractMessagingBundles(extras)
        if (messages.isNotEmpty()) {
            val times = ArrayList<Long>(messages.size)
            val bodies = ArrayList<String>(messages.size)
            for (b in messages) {
                times.add(readMessageTime(b))
                bodies.add(readMessageText(b))
            }
            // Which element is "the latest" is not answered by the array position: Instagram's bundle
            // is neither time-sorted (seq 8: …602962 then …595013) nor free of empty padding entries
            // (seq 85: "Sent a reel", "", "Sent a reel"). Reading .last() blindly is how a card landed
            // on a blank message and then fell out of this branch entirely, printing "Instagram".
            val newest = ChatDisplayPolicy.newestIndex(times, bodies, fallbackText)
            if (newest >= 0) {
                val latest = messages[newest]
                val latestText = bodies[newest].trim()
                val sender = readMessageSender(latest).trim()
                val rawConversation = readConversationTitle(extras).ifBlank { fallbackTitle }.ifBlank { sender }.ifBlank { appName }
                val conversation = stripMessageCountSuffix(rawConversation)
                // The app's own badge number is the unread count; messages.size is only how much
                // history the style happens to carry (capture: IG sent number=2 with 3 messages in the
                // style, so size alone over-reported, while InstaPro's 7/7 agreed by luck).
                val count = if (notification.number > 0) notification.number else messages.size.coerceAtLeast(1)
                val identity = buildConversationIdentity(pkg, sbn, notification, conversation, sender, threadTitle)
                return ExtractedNotificationContent(
                    appName = appName,
                    conversationTitle = ChatDisplayPolicy.displayTitle(threadTitle, fallbackTitle, sender, selfName, appName),
                    senderName = sender.ifBlank { conversation },
                    latestMessage = latestText,
                    unreadCount = count,
                    isMessagingStyle = true,
                    conversationKey = identity.first,
                    conversationKeySource = identity.second,
                    displayTimeMs = ChatDisplayPolicy.displayTimeMs(times[newest], notification.getWhen(), sbn.postTime, now)
                )
            }
        }

        // No body at all and no thread name means there is nothing to show, whatever the app calls
        // itself - a card headed "Google" with an empty message line is how the weather summary used
        // to look.
        if (fallbackTitle.isBlank() && fallbackText.isBlank() && threadTitle.isBlank()) return null
        val identity = buildConversationIdentity(pkg, sbn, notification, fallbackTitle, fallbackTitle, threadTitle)
        return ExtractedNotificationContent(
            appName = appName,
            conversationTitle = ChatDisplayPolicy.displayTitle(threadTitle, fallbackTitle, "", selfName, appName),
            senderName = fallbackTitle,
            latestMessage = fallbackText,
            unreadCount = if (notification.number > 0) notification.number else 1,
            isMessagingStyle = false,
            conversationKey = identity.first,
            conversationKeySource = identity.second,
            displayTimeMs = ChatDisplayPolicy.displayTimeMs(0L, notification.getWhen(), sbn.postTime, now)
        )
    }

    /** MessagingStyle.Message.time, or 0 when the app did not set one. */
    private fun readMessageTime(bundle: Bundle): Long = try {
        bundle.getLong("time")
    } catch (_: Exception) {
        0L
    }

    private fun extractMessagingBundles(extras: Bundle): List<Bundle> {
        val raw = try {
            @Suppress("DEPRECATION")
            extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        return raw.mapNotNull { it as? Bundle }
    }

    private fun readConversationTitle(extras: Bundle): String {
        return (extras.getCharSequence("android.conversationTitle")
            ?: extras.getCharSequence(Notification.EXTRA_TITLE)
            ?: "").toString().trim()
    }

    private fun readMessageText(bundle: Bundle): String {
        return (bundle.getCharSequence("text")
            ?: bundle.getCharSequence("android.text")
            ?: bundle.getCharSequence("message")
            ?: bundle.getCharSequence("message_text")
            ?: "").toString()
    }

    private fun readMessageSender(bundle: Bundle): String {
        val sender = (bundle.getCharSequence("sender")
            ?: bundle.getCharSequence("android.sender")
            ?: bundle.getCharSequence("sender_name")
            ?: "").toString()
        if (sender.isNotBlank()) return sender

        val person = try {
            @Suppress("DEPRECATION")
            bundle.get("sender_person")
        } catch (_: Exception) {
            null
        } ?: return ""

        return try {
            val method = person.javaClass.methods.firstOrNull { it.name == "getName" && it.parameterTypes.isEmpty() }
            method?.invoke(person)?.toString().orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Ranking lives in ConversationIdentity (Android-free, unit tested). Two things are deliberately
     * NOT passed in: sbn.key, which identified a notification rather than a conversation and is what
     * split one chat into several ring items (it stays in use for echo suppression and dedupe, where a
     * per-notification identity is the right thing), and the notification's Person extras, which on
     * this device name the notification's OWNER ("You" / "me" / our own id) for every chat an app owns
     * and would therefore merge unrelated chats instead of separating them.
     */
    private fun buildConversationIdentity(
        pkg: String,
        sbn: StatusBarNotification,
        notification: Notification,
        conversationTitle: String,
        senderName: String,
        threadTitle: String
    ): Pair<String, String> = ConversationIdentity.build(
        pkg = pkg,
        shortcutId = readShortcutId(notification),
        conversationTitle = threadTitle,
        tag = sbn.tag.orEmpty(),
        notificationId = sbn.id,
        sender = senderName,
        title = conversationTitle
    )

    private fun readShortcutId(notification: Notification): String {
        return try {
            notification.javaClass.getMethod("getShortcutId").invoke(notification)?.toString().orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    /** android.conversationTitle when the app sets one, else "" (never the per-message EXTRA_TITLE). */
    private fun readThreadTitle(extras: Bundle): String =
        (extras.getCharSequence("android.conversationTitle") ?: "").toString().trim()

    /**
     * Is this a conversation, or an app wearing a message category? Measured on the same device:
     * every real chat (WhatsApp children, Instagram, InstaPro, Telegram, Discord) carries at least
     * one of android.messages / android.messagingUser / android.conversationTitle, and the noise does
     * not - including eight Snapchat promos that DO set CATEGORY_MESSAGE. WhatsApp chats have no
     * category at all, which is why filtering a re-sync on CATEGORY_MESSAGE alone lost them.
     */
    fun looksLikeConversation(notification: Notification?): Boolean {
        val extras = notification?.extras ?: return false
        return try {
            val keys = extras.keySet()
            keys.contains(Notification.EXTRA_MESSAGES) ||
                keys.contains("android.messagingUser") ||
                keys.contains("android.conversationTitle")
        } catch (_: Exception) {
            false
        }
    }

    private fun normalizeKeyPart(value: String): String = ConversationIdentity.normalize(value)

    private fun stripMessageCountSuffix(value: String): String = ConversationIdentity.stripMessageCountSuffix(value)

    private fun getAppName(context: Context, pkg: String): String = try {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg
    }
}
