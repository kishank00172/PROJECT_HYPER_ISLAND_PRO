package com.hyperisland.pro.services

import android.app.Notification
import android.content.Context
import android.os.Bundle
import android.service.notification.StatusBarNotification

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
                val count = messages.size.coerceAtLeast(1)
                val identity = buildConversationIdentity(pkg, sbn, notification, extras, conversation, sender)
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
        val identity = buildConversationIdentity(pkg, sbn, notification, extras, title, title)
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

    private fun buildConversationIdentity(
        pkg: String,
        sbn: StatusBarNotification,
        notification: Notification,
        extras: Bundle,
        conversationTitle: String,
        senderName: String
    ): Pair<String, String> {
        val shortcutId = readShortcutId(notification)
        if (shortcutId.isNotBlank()) return "$pkg|shortcut|$shortcutId" to "shortcutId"

        val key = sbn.key.orEmpty()
        if (key.isNotBlank()) return "$pkg|sbnKey|$key" to "sbn.key"

        val tag = sbn.tag.orEmpty()
        if (tag.isNotBlank()) return "$pkg|idTag|${sbn.id}|$tag" to "id+tag"

        val people = readPeopleIdentity(extras)
        if (people.isNotBlank()) return "$pkg|people|$people" to "people"

        val sender = normalizeKeyPart(senderName)
        if (sender.isNotBlank()) return "$pkg|sender|$sender" to "sender"

        val title = normalizeKeyPart(conversationTitle)
        if (title.isNotBlank()) return "$pkg|title|$title" to "titleFallback"

        return "$pkg|notification|${sbn.id}" to "idFallback"
    }

    private fun readShortcutId(notification: Notification): String {
        return try {
            notification.javaClass.getMethod("getShortcutId").invoke(notification)?.toString().orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    private fun readPeopleIdentity(extras: Bundle): String {
        val parts = ArrayList<String>()
        try {
            extras.getStringArray("android.people")?.forEach { if (it.isNotBlank()) parts.add(it) }
        } catch (_: Exception) {}
        try {
            @Suppress("DEPRECATION")
            val list = extras.get("android.people.list") as? ArrayList<*>
            list?.forEach { if (it != null) parts.add(it.toString()) }
        } catch (_: Exception) {}
        return parts.map { normalizeKeyPart(it) }.filter { it.isNotBlank() }.distinct().sorted().joinToString("|")
    }

    private fun normalizeKeyPart(value: String): String {
        return value.lowercase().replace(Regex("\\s+"), " ").trim()
    }

    private fun stripMessageCountSuffix(value: String): String {
        return value
            .replace(Regex("\\s*\\(\\d+\\s+messages?\\)\\s*$", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    private fun getAppName(context: Context, pkg: String): String = try {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg
    }
}
