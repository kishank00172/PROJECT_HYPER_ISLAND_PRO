package com.hyperisland.pro.services

import android.app.Notification
import android.content.Context
import android.os.Bundle
import android.service.notification.StatusBarNotification
import com.hyperisland.pro.core.ChatDisplayPolicy
import com.hyperisland.pro.core.V2SeenWatermark
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
        val displayTimeMs: Long,
        /** Which [ChatDisplayPolicy] rule produced `conversationTitle`; goes in the trace log. */
        val titleRule: String
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
        // Android's own definition: android.messagingUser is the *user of this conversation*, i.e. me. Its
        // key is the account id (seq 335: key='59789964840' / name='Kishan Kumar'), which is what settles
        // "did I send this" when a display name is something the app invented.
        val selfKey = personKey(extras.get("android.messagingUser"))
        // isGroupConversation is the field that says whether a thread name is even meaningful: in a 1:1 the
        // thread IS the peer, so a 1:1 title filled with my own handle (Instagram does this) must not become
        // the headline.
        val isGroup = try {
            extras.getBoolean("android.isGroupConversation")
        } catch (_: Exception) {
            false
        }

        // A group summary is a container, not a message - "WhatsApp / 21 messages from 3 chats". The
        // framework flag that should have caught it was not set by either app on this device (measured:
        // flags 512 and 529, FLAG_GROUP_SUMMARY=128 absent), so the listener let them through and each
        // one became a card: it ate a slot, headed itself with the app name, and its badge is the
        // app-wide total, which double-counted every chat under it.
        if (ChatDisplayPolicy.isGroupSummary(appName, fallbackTitle, fallbackText)) return null

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
                val latestText = oneLine(bodies[newest])
                val sender = readMessageSender(latest).trim()
                val senderKey = readSenderPersonKey(latest)
                val senderIsMe = ChatDisplayPolicy.isSelf(sender, selfName) ||
                    (senderKey.isNotBlank() && selfKey.isNotBlank() && senderKey == selfKey)
                val rawConversation = readConversationTitle(extras).ifBlank { fallbackTitle }.ifBlank { sender }.ifBlank { appName }
                val conversation = stripMessageCountSuffix(rawConversation)
                // The app's own badge number is the unread count; messages.size is only how much
                // history the style happens to carry (capture: IG sent number=2 with 3 messages in the
                // style, so size alone over-reported, while InstaPro's 7/7 agreed by luck).
                val count = if (notification.number > 0) notification.number else messages.size.coerceAtLeast(1)
                val identity = buildConversationIdentity(pkg, sbn, notification, conversation, sender, threadTitle)
                val choice = ChatDisplayPolicy.titleFor(threadTitle, fallbackTitle, sender, selfName, appName, isGroup, senderIsMe)
                return ExtractedNotificationContent(
                    appName = appName,
                    conversationTitle = choice.text,
                    senderName = sender.ifBlank { conversation },
                    latestMessage = latestText,
                    unreadCount = count,
                    isMessagingStyle = true,
                    conversationKey = identity.first,
                    conversationKeySource = identity.second,
                    displayTimeMs = ChatDisplayPolicy.displayTimeMs(times[newest], notification.`when`, sbn.postTime, now),
                    titleRule = choice.rule
                )
            }
        }

        // No body at all and no thread name means there is nothing to show, whatever the app calls
        // itself - a card headed "Google" with an empty message line is how the weather summary used
        // to look.
        if (fallbackTitle.isBlank() && fallbackText.isBlank() && threadTitle.isBlank()) return null
        val identity = buildConversationIdentity(pkg, sbn, notification, fallbackTitle, fallbackTitle, threadTitle)
        val choice = ChatDisplayPolicy.titleFor(threadTitle, fallbackTitle, "", selfName, appName, isGroup)
        return ExtractedNotificationContent(
            appName = appName,
            conversationTitle = choice.text,
            senderName = fallbackTitle,
            latestMessage = oneLine(fallbackText),
            unreadCount = if (notification.number > 0) notification.number else 1,
            isMessagingStyle = false,
            conversationKey = identity.first,
            conversationKeySource = identity.second,
            displayTimeMs = ChatDisplayPolicy.displayTimeMs(0L, notification.`when`, sbn.postTime, now),
            titleRule = choice.rule
        )
    }

    /**
     * Chat previews are one paragraph. A message sent as "Hii Kishan! 👋\n\nBolo kya chal raha hai?" with
     * the card's message line at maxLines=2 + ellipsize used to render as the first line plus a bare "…" -
     * the blank line ate the second row, and that stray "…" was in three of the tester's screenshots with
     * no explanation. Folding the breaks costs nothing (the full text is still one sentence long) and the
     * ellipsis disappears.
     */
    private fun oneLine(value: String): String = value.replace(Regex("\\s*\\n+\\s*"), " ").trim()

    /** Person.getKey() without importing android.app.Person - the key is the account, the name is not. */
    private fun personKey(person: Any?): String = try {
        person?.javaClass?.methods
            ?.firstOrNull { it.name == "getKey" && it.parameterCount == 0 }
            ?.invoke(person)?.toString().orEmpty()
    } catch (_: Exception) {
        ""
    }

    /** The sender Person's key on a MessagingStyle message, when the app filled one in. */
    private fun readSenderPersonKey(bundle: Bundle): String = try {
        personKey(bundle.get("sender_person"))
    } catch (_: Exception) {
        ""
    }

    /** b1513: latest MessagingStyle sender's Person.icon as a circular 96 px bitmap (sender DP).
     *  Person.getIcon() is a plain public API (the lab export proved the field survives the
     *  binder both ways). Returns null when the app shipped no icon or any step fails. */
    fun latestSenderAvatar(context: Context, sbn: StatusBarNotification): android.graphics.Bitmap? = try {
        val extras = sbn.notification?.extras ?: return null
        val msgs = extractMessagingBundles(extras)
        if (msgs.isEmpty()) return null
        // b1514: walk backwards for the last icon-bearing sender (skips silent self/icon-less tails).
        var person: Any? = null; var icon: Any? = null
        for (i in msgs.size - 1 downTo 0) {
            val p = msgs[i].get("sender_person") ?: continue
            val ic = p.javaClass.methods.firstOrNull { it.name == "getIcon" && it.parameterCount == 0 }?.invoke(p)
            if (ic != null) { person = p; icon = ic; break }
        }
        if (person == null) return null
        val d = when (icon) {
            is android.graphics.drawable.Icon -> icon.loadDrawable(context)
            else -> null
        } ?: return null
        val size = 96
        val out = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
        val path = android.graphics.Path().apply {
            addCircle(size / 2f, size / 2f, size / 2f, android.graphics.Path.Direction.CW)
        }
        canvas.clipPath(path)
        val src = if (d is android.graphics.drawable.BitmapDrawable) d.bitmap else null
        if (src != null) {
            val dest = android.graphics.Rect(0, 0, size, size)
            canvas.drawBitmap(src, null, dest, paint)
        } else {
            d.setBounds(0, 0, size, size); d.draw(canvas)
        }
        out
    } catch (_: Exception) { null }

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

    /** b1501 / Round-I P1-2: per-message clock for the seen-watermark (MessagingStyle ONLY).
     *  EXTRA_MESSAGES + EXTRA_HISTORIC_MESSAGES, in style order (our watermark sorts by ts itself). */
    fun messageStamps(sbn: StatusBarNotification): List<V2SeenWatermark.MsgStamp> {
        val extras = sbn.notification?.extras ?: return emptyList()
        val bundles = ArrayList<Bundle>()
        bundles.addAll(extractMessagingBundles(extras))
        try {
            @Suppress("DEPRECATION")
            extras.getParcelableArray(Notification.EXTRA_HISTORIC_MESSAGES)
                ?.mapNotNullTo(bundles) { it as? Bundle }
        } catch (_: Exception) {}
        val out = ArrayList<V2SeenWatermark.MsgStamp>(bundles.size)
        for (b in bundles) {
            val ts = readMessageTime(b)
            val text = readMessageText(b)
            if (text.isBlank()) continue        // empty padding entries (capture seq 85) carry no identity
            out.add(V2SeenWatermark.MsgStamp(ts, text, readMessageSender(b).trim()))
        }
        return out
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

    /**
     * Two binder round trips per notification, on whatever thread the notification arrived, for a string that
     * cannot change while the process lives. The service's own log caught the main-thread version of this cost;
     * this copy is the same call from the extractor, so it is cached the same way - failures included as
     * non-cacheable, so a lookup that misses once does not pin the package name onto every later card.
     */
    private val appNamesByPkg = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun getAppName(context: Context, pkg: String): String = appNamesByPkg[pkg] ?: try {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
            .also { appNamesByPkg[pkg] = it }
    } catch (_: Exception) {
        pkg
    }
}
