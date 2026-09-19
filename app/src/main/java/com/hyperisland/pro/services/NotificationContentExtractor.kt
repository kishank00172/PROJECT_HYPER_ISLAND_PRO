package com.hyperisland.pro.services

import android.app.Notification
import android.content.Context
import android.os.Bundle
import android.service.notification.StatusBarNotification
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
        val conversationKeySource: String
    )

    fun extract(context: Context, sbn: StatusBarNotification): ExtractedNotificationContent? {
        val pkg = sbn.packageName ?: return null
        val notification = sbn.notification ?: return null
        val extras = notification.extras ?: return null
        val appName = getAppName(context, pkg)

        val fallbackTitle = (extras.getCharSequence(Notification.EXTRA_TITLE) ?: "").toString().trim()
        val fallbackText = (extras.getCharSequence(Notification.EXTRA_TEXT) ?: "").toString().trim()

        val messages = extractMessagingBundles(extras)
        if (messages.isNotEmpty()) {
            val latest = messages.last()
            val latestText = readMessageText(latest).trim()
            val sender = readMessageSender(latest).trim()
            val rawConversation = readConversationTitle(extras).ifBlank { fallbackTitle }.ifBlank { sender }.ifBlank { appName }
            val conversation = stripMessageCountSuffix(rawConversation)
            if (latestText.isNotBlank()) {
                // The app's own badge number is the unread count; messages.size is only how much
                // history the style happens to carry (capture: IG sent number=2 with 3 messages in the
                // style, so size alone over-reported, while InstaPro's 7/7 agreed by luck).
                val count = if (notification.number > 0) notification.number else messages.size.coerceAtLeast(1)
                val identity = buildConversationIdentity(pkg, sbn, notification, conversation, sender, readThreadTitle(extras))
                return ExtractedNotificationContent(
                    appName = appName,
                    conversationTitle = conversation,
                    senderName = sender.ifBlank { conversation },
                    latestMessage = latestText,
                    unreadCount = count,
                    isMessagingStyle = true,
                    conversationKey = identity.first,
                    conversationKeySource = identity.second
                )
            }
        }

        val title = fallbackTitle.ifBlank { appName }
        val text = fallbackText
        if (title.isBlank() && text.isBlank()) return null
        val identity = buildConversationIdentity(pkg, sbn, notification, title, title, readThreadTitle(extras))
        return ExtractedNotificationContent(
            appName = appName,
            conversationTitle = title,
            senderName = title,
            latestMessage = text,
            unreadCount = 1,
            isMessagingStyle = false,
            conversationKey = identity.first,
            conversationKeySource = identity.second
        )
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
