package com.hyperisland.pro.core

/**
 * What the island *prints* for a chat: which line becomes the bold title, which message of the
 * bundle counts as "the latest", and what timestamp the reader sees.
 *
 * This is deliberately separate from [ConversationIdentity]. Identity decides which page a
 * notification belongs to; this decides what a human reads. The two must not share rules, because
 * the fields they need disagree: EXTRA_TITLE is fine as identity material (stable-ish per thread)
 * and misleading as a headline, since Instagram fills it with *whoever sent the last message*
 * rather than the conversation. That is the whole reason a message your own AI sent appeared on the
 * island with *your* name on it — device capture `notification_raw.jsonl (2)`, seq 10:
 *   android.title = "Kishan Kumar", android.messages[last].sender = "Kishan Kumar"
 * and the app even hands you the way out, which nothing was reading:
 *   android.selfDisplayName = "Kishan Kumar"
 *
 * Same object, second lesson, capture seq 335 (`uploads/noti.txt`): a 1:1 with a bot where Instagram wrote
 * `android.conversationTitle = "kish.ank001"` - *my* handle - while the sender sat in
 * `android.messages[0].sender = "Meta AI"`. Trusting the thread name printed my own username as the
 * headline of a message from Meta AI. So the thread name is only trusted for groups, which is exactly
 * what `android.isGroupConversation` says.
 *
 * Android-free on purpose: every rule here is a pure function over strings/numbers, so CI verifies
 * it with the real values from that capture instead of a rebuild-by-rebuild guess on a phone.
 */
object ChatDisplayPolicy {

    /** Headline used when the newest message in a thread is one that *we* sent. */
    const val YOU = "You"

    /** Fallback headline when an app gives us nothing chat-shaped at all. */
    const val MESSAGE = "Message"

    fun isSelf(sender: String, selfDisplayName: String): Boolean {
        val a = ConversationIdentity.normalize(sender)
        val b = ConversationIdentity.normalize(selfDisplayName)
        return a.isNotBlank() && b.isNotBlank() && a == b
    }

    /** "2 new messages", "2 messages from 2 chats" - the summary-shaped line an app puts in EXTRA_TITLE. */
    private val SUMMARY_TEXT = Regex("^\\d+ (new )?messages?( (from|in) \\d+ .*)?$", RegexOption.IGNORE_CASE)

    /** The headline plus which rule produced it - the rule goes into the trace log, so a wrong name is
     *  a line in the file instead of a rebuild. */
    data class TitleChoice(val text: String, val rule: String)

    fun displayTitle(
        threadTitle: String,
        extraTitle: String,
        sender: String,
        selfDisplayName: String,
        appName: String,
        isGroupConversation: Boolean = false,
        senderIsMe: Boolean = false
    ): String = titleFor(threadTitle, extraTitle, sender, selfDisplayName, appName, isGroupConversation, senderIsMe).text

    /**
     * The rule, in one place: **a 1:1 conversation is named by who spoke, a group by which thread**.
     *
     * That ordering is what this device's data forces. Instagram put my own handle in
     * `android.conversationTitle` for a 1:1 with a bot (`seq 335`: conversationTitle='kish.ank001',
     * isGroupConversation=false) while the actual sender sat in `android.messages[0].sender='Meta AI'`,
     * with `sender_person.isBot=true`. Preferring the thread title printed "kish.ank001" as the headline
     * of a message from Meta AI - "mera username aa rha hai Meta AI ke jagah". For a 1:1 the thread title
     * is *supposed* to be the peer, so when the app fills it with something else, the message's own
     * sender is the better answer. Groups keep the thread name (that is the only thing that tells chats
     * apart there), and the sender is recovered from IG's "<thread>: <sender>" title shape when the
     * messages array carries none.
     *
     * `senderIsMe` comes from the caller comparing names *and* Person keys, because a display name can be
     * anything while `android.messagingUser.key` is the account itself.
     */
    fun titleFor(
        threadTitle: String,
        extraTitle: String,
        sender: String,
        selfDisplayName: String,
        appName: String,
        isGroupConversation: Boolean = false,
        senderIsMe: Boolean = false
    ): TitleChoice {
        val thread = cleanTitle(threadTitle)
        val declared = cleanTitle(sender)
        val fromTitle = peerFromIGTitle(extraTitle)
        val speaker = declared.ifBlank { fromTitle }
        val me = senderIsMe || isSelf(sender, selfDisplayName)
        val app = cleanTitle(appName)
        val extra = cleanTitle(extraTitle)

        if (isGroupConversation) {
            if (thread.isNotBlank() && !thread.equals(extra, true)) return TitleChoice(thread, "group:thread")
            if (me) return TitleChoice(YOU, "group:self")
            if (speaker.isNotBlank() && !speaker.equals(app, true)) return TitleChoice(speaker, "group:speaker")
            if (thread.isNotBlank()) return TitleChoice(thread, "group:thread")
            if (extra.isNotBlank() && !extra.equals(app, true)) return TitleChoice(extra, "group:extra")
            return TitleChoice(if (app.isBlank()) MESSAGE else app, "group:app")
        }
        if (me) return TitleChoice(YOU, "1to1:you")
        if (speaker.isNotBlank() && !speaker.equals(app, true)) return TitleChoice(speaker, "1to1:sender")
        if (thread.isNotBlank() && !thread.equals(app, true)) return TitleChoice(thread, "1to1:thread")
        if (extra.isNotBlank() && !extra.equals(app, true)) return TitleChoice(extra, "1to1:extra")
        return TitleChoice(if (app.isBlank()) MESSAGE else app, "1to1:app")
    }

    /**
     * Instagram formats a MessagingStyle EXTRA_TITLE as "<thread>: <sender>" -
     * 'kish.ank001: Meta AI', 'Homieees!!🐱: Adarsh Kumar Jha'. When the messages array has no sender,
     * the part after the last ": " still names who spoke.
     */
    fun peerFromIGTitle(extraTitle: String): String {
        // Whitespace collapsed, case kept: a headline is printed, a key is compared. CI caught the
        // difference - the first version lower-cased and the card would have said "meta ai".
        val v = extraTitle.trim().replace(Regex("\\s+"), " ")
        val cut = v.lastIndexOf(": ")
        if (cut < 2 || cut > v.length - 3) return ""
        val tail = v.substring(cut + 2).trim()
        if (tail.length < 2 || SUMMARY_TEXT.matches(tail)) return ""
        return tail
    }

    /**
     * Trims the "(handle)" prefix and the baked-in "(N messages)" suffix, both of which are noise in a
     * headline and both of which real apps send: IG writes "(kish.ank001) Homieees!!", WhatsApp writes
     * "Chat + Fun Vibes (18 messages)".
     */
    fun cleanTitle(value: String): String {
        // Case is kept on purpose: normalize() is for keys, and "homieees!!" printed on a card is
        // someone's thread name mangled. Folding is only for comparing, never for showing.
        var v = ConversationIdentity.stripMessageCountSuffix(value.trim().replace(Regex("\\s+"), " "))
        if (v.startsWith("(")) {
            val close = v.indexOf(')')
            if (close in 2..40 && v.length > close + 2) v = v.substring(close + 1).trim()
        }
        return v
    }

    /**
     * A group summary is a container, not a message: WhatsApp posts "WhatsApp / 21 messages from 3
     * chats" and Telegram posts "Telegram / 946536 new messages from 5 chats" *alongside* the per-chat
     * notifications. Two things make them harmful here:
     *  - their badge number is the app-wide unread total, so counting them double-counts every chat
     *    beneath it (measured: 21 + 2 + 1 + 18 = 42 shown for what is really 21), and Telegram's badge
     *    was literally 946536, which pinned the pill to its ceiling;
     *  - their headline is the app name, which is the one thing the island should never print.
     * The framework flag does not save us on this device: both summaries were posted with flags 512 and
     * 529, i.e. FLAG_GROUP_SUMMARY (128) unset, so the listener's flag check let them through as
     * ordinary notifications. Shape of the text is the honest detector.
     */
    fun isGroupSummary(appName: String, title: String, text: String): Boolean {
        val titleIsTheApp = title.isBlank() || title.trim().equals(appName.trim(), ignoreCase = true)
        if (!titleIsTheApp) return false
        return SUMMARY_TEXT.matches(text.trim().replace(Regex("\\s+"), " "))
    }

    /**
     * Which element of `android.messages` is the one to show? Position is not a safe answer.
     *
     * Two things the capture showed:
     *  - Instagram's array is not sorted: seq 8 holds time …602962 then …595013 then …798954, so
     *    `messages.last()` is "the last one the app happened to append", not the newest message.
     *  - Instagram pads with empty entries: seq 85 = ["Sent a reel", "", "Sent a reel"] — a `last()`
     *    read that landed on the empty one, fell out of the MessagingStyle branch, and produced the
     *    title-less "Instagram" card.
     *
     * So: newest by time, blank entries skipped, and among equal times the one the app also put in
     * EXTRA_TEXT wins, because EXTRA_TEXT is what the app wants the shelf to read.
     * Returns -1 when every entry is blank.
     */
    fun newestIndex(times: List<Long>, texts: List<String>, latestKnownText: String): Int {
        val n = minOf(times.size, texts.size)
        if (n <= 0) return -1
        val clean = (0 until n).map { texts[it].trim() }
        val probe = latestKnownText.trim()
        fun matches(i: Int): Boolean {
            if (probe.isEmpty() || clean[i].isEmpty()) return false
            val head = clean[i].take(minOf(16, clean[i].length))
            return clean[i] == probe || probe.startsWith(head)
        }
        var best = -1
        for (i in 0 until n) {
            if (clean[i].isBlank()) continue
            if (best < 0) { best = i; continue }
            val newer = times[i] > times[best]
            val tieBetterMatch = times[i] == times[best] && matches(i) && !matches(best)
            if (newer || tieBetterMatch) best = i
        }
        return best
    }

    /**
     * Which instant should the card be stamped with? The message's own time — not the post time.
     *
     * `StatusBarNotification.postTime` is when the app pushed it, and Instagram pushes a thread's
     * whole pending batch at once: seq 85 carried when/messages.time = 1789804625087 but
     * postTime = 1789805380355, 12.6 minutes later. Stamping with postTime is precisely what made a
     * ten-minute-old message read "now". A message time in the future or a missing (0) one is not
     * trusted; postTime then takes over so a card is never undated.
     */
    fun displayTimeMs(messageTimeMs: Long, notificationWhenMs: Long, postTimeMs: Long, nowMs: Long): Long {
        val ceiling = nowMs + 5_000L
        val best = listOf(messageTimeMs, notificationWhenMs).filter { it in 1L..ceiling }.maxOrNull()
        return best ?: postTimeMs
    }

    /**
     * "now" only for something that genuinely just happened; then a minute count, then the clock,
     * then the date. `clockText`/`dateText` come from the caller so formatting stays the Android side's
     * business (locale/timezone) and this stays testable.
     */
    fun timeLabel(millis: Long, nowMs: Long, sameLocalDay: Boolean, clockText: String, dateText: String): String {
        if (millis <= 0L) return "now"
        val delta = nowMs - millis
        if (delta < 60_000L) return "now"
        val minutes = delta / 60_000L
        if (minutes < 60L) return "$minutes min"
        if (sameLocalDay) return clockText
        if (delta < 2L * 86_400_000L) return "Yesterday"
        return dateText
    }
}
