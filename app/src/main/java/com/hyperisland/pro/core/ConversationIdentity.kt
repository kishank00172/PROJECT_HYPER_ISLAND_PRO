package com.hyperisland.pro.core

import java.util.Locale

/**
 * Which conversation does this notification belong to?
 *
 * Extracted from NotificationContentExtractor and deliberately free of any Android type, so the
 * ranking can be unit tested on the JVM (CI runs it) instead of being guessed at on a phone.
 *
 * WHY this exists: the old ranking used `StatusBarNotification.key` as the #2 identity source.
 * That key is per-NOTIFICATION, not per-CONVERSATION — an app that posts a fresh id for each
 * message (Instagram DMs, Telegram) produced a new key for every message, so three messages from
 * one person became three separate ring items and the "1/3" counter lied. It also made the two
 * ingestion paths disagree: the notification listener keyed on the per-message key while the
 * accessibility fallback keyed on the title, so the same chat merged or split depending on which
 * path delivered it.
 *
 * Ranking, strongest signal first:
 *   1. shortcutId      set by the app to name the conversation thread; stable across messages
 *   2. people          the notification's person list; stable, and it separates two "Ram"s
 *   3. sender + title   what a human reads as the thread
 *   4. title           conversation name only
 *   5. id + tag        last resort, NOT an identity we want to rely on
 *   6. notification id  true fallback so the key is never blank
 */
object ConversationIdentity {

    fun build(
        pkg: String,
        shortcutId: String,
        people: String,
        tag: String,
        notificationId: Int,
        sender: String,
        title: String
    ): Pair<String, String> {
        val shortcut = shortcutId.trim()
        if (shortcut.isNotBlank()) return "$pkg|shortcut|$shortcut" to "shortcutId"

        val peopleKey = normalize(people)
        if (peopleKey.isNotBlank()) return "$pkg|people|$peopleKey" to "people"

        val senderKey = normalize(sender)
        val titleKey = normalize(title)
        if (senderKey.isNotBlank() && titleKey.isNotBlank() && senderKey != titleKey) {
            return "$pkg|conv|$senderKey|$titleKey" to "sender+title"
        }
        if (senderKey.isNotBlank()) return "$pkg|sender|$senderKey" to "sender"
        if (titleKey.isNotBlank()) return "$pkg|title|$titleKey" to "titleFallback"

        val tagKey = tag.trim()
        if (tagKey.isNotBlank()) return "$pkg|idTag|$notificationId|$tagKey" to "id+tag"

        return "$pkg|notification|$notificationId" to "idFallback"
    }

    /** Lower-case, single-spaced, trimmed — so "Ram" and " ram " are one thread, not two. */
    fun normalize(value: String): String =
        value.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()

    /**
     * Removes the trailing "(N messages)" that apps bake into MessagingStyle titles, otherwise the
     * title changes with every new message and the conversation identity would change with it.
     */
    fun stripMessageCountSuffix(value: String): String =
        value.replace(Regex("\\s*\\(\\d+\\s+messages?\\)\\s*$", RegexOption.IGNORE_CASE), "").trim()
}
