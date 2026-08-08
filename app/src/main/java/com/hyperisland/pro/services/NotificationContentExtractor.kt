package com.hyperisland.pro.services

import android.app.Notification
import android.content.Context
import android.os.Bundle
import android.service.notification.StatusBarNotification

/**
 * Phase 3.6 — Notification Intelligence (latest-message extraction).
 * Extracts latest chat message from MessagingStyle bundles when possible; unread count is returned separately.
 * Falls back to normal EXTRA_TITLE / EXTRA_TEXT for non-chat notifications.
 */
object NotificationContentExtractor {
    data class ExtractedNotificationContent(
        val appName: String,
        val conversationTitle: String,
        val senderName: String,
        val latestMessage: String,
        val unreadCount: Int,
        val isMessagingStyle: Boolean
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
                return ExtractedNotificationContent(
                    appName = appName,
                    conversationTitle = conversation,
                    senderName = sender.ifBlank { conversation },
                    latestMessage = latestText,
                    unreadCount = count,
                    isMessagingStyle = true
                )
            }
        }

        val title = fallbackTitle.ifBlank { appName }
        val text = fallbackText
        if (title.isBlank() && text.isBlank()) return null
        return ExtractedNotificationContent(
            appName = appName,
            conversationTitle = title,
            senderName = title,
            latestMessage = text,
            unreadCount = 1,
            isMessagingStyle = false
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
