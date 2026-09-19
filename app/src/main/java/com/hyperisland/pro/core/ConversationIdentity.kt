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
 * Ranking, strongest signal first (each tier re-checked against the 2026-09-19 device capture in
 * the notification-lab repo, which is the first time this was measured instead of assumed):
 *   1. shortcutId        IG/InstaPro publish one per thread ("thread_shortcut_<uid>_<tid>") and it is
 *                        identical across updates, so this is the tier that makes "3 messages = 1
 *                        card" true for Instagram.
 *   2. people            now fed from the Person objects (android.messagingUser / sender_person) whose
 *                        getKey() is a stable numeric id; the older android.people read stayed, but on
 *                        this device that key simply is not present, so it used to return "".
 *   3. conversationTitle android.conversationTitle names the thread and survives a change of sender,
 *                        which sender+title does not (group threads put the sender in the title).
 *   4. sender + title    what a human reads as the thread
 *   5. title             conversation name only
 *   6. id + tag          last resort, NOT an identity we want to rely on
 *   7. notification id    true fallback so the key is never blank
 */
object ConversationIdentity {

    fun build(
        pkg: String,
        shortcutId: String,
        people: String,
        conversationTitle: String,
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
        val titleKey = stripMessageCountSuffix(normalize(title))
        val convKey = stripMessageCountSuffix(normalize(conversationTitle))
        if (convKey.isNotBlank() && convKey != titleKey) return "$pkg|thread|$convKey" to "conversationTitle"
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
