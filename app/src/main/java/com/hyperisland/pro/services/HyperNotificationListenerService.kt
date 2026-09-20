package com.hyperisland.pro.services

import android.app.Notification
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.service.notification.NotificationListenerService.RankingMap
import android.util.Log
import android.view.accessibility.AccessibilityManager
import com.hyperisland.pro.core.AppSettings
import com.hyperisland.pro.core.TraceLog

class HyperNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TRACE_TAG = "HIP_TRACE"
        @Volatile var isConnected: Boolean = false
        @Volatile var lastDebugMessage: String = "Notification listener not connected"

        /** How long a re-posted notification stays blocked as a zombie repeat. */
        private const val ZOMBIE_BLOCK_MS = 20_000L
        private const val ZOMBIE_SWEEP_AFTER = 64

        // NotificationListenerService.REASON_* values spelled out, so nothing here depends on a
        // constant the SDK may or may not expose. The 2026-09-19 device capture showed reason 8:
        // opening a chat in the app cancels its own notification, and that is exactly the moment the
        // island should drop the page.
        private const val REASON_CANCEL_ALL = 1
        private const val REASON_CANCEL = 2
        private const val REASON_TIMEOUT = 4
        private const val REASON_GROUP_SUMMARY_CANCELED = 5
        private const val REASON_APP_CANCEL = 8
        private const val REASON_APP_CANCEL_ALL = 9

        /** Instagram cancelled a notification 559 ms after posting it (measured). 1.5 s is the floor. */
        private const val REMOVAL_GRACE_MS = 1_500L
    }

    private data class RepeatInfo(var count: Int, var firstSeenAt: Long, var lastSeenAt: Long, var blockedUntil: Long)
    private val repeatMap = HashMap<String, RepeatInfo>()

    // Threads seen since the last connect, so a re-sync cannot double-post what already arrived.
    private val recentlyHandled = HashMap<String, Long>()

    // Removals are delayed a beat: cancel-then-re-post during an update must not blink the card out.
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingRemovals = HashMap<String, Runnable>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        lastDebugMessage = "Notification listener connected"
        resyncActiveNotifications()
    }

    /**
     * Anything posted while the listener was dead was gone for good — the OEM kills this service on
     * memory pressure and Android rebinds it later, silently. Re-reading the live shelf on connect is
     * the only way to recover those, and it is why whole batches of DMs used to vanish rather than one.
     */
    private fun resyncActiveNotifications() {
        val active = try {
            getActiveNotifications()
        } catch (e: Exception) {
            Log.w(TRACE_TAG, "RESYNC unavailable: ${e.javaClass.simpleName}")
            return
        } ?: return
        var replayed = 0
        for (sbn in active) {
            if (sbn.packageName == packageName) continue
            // CATEGORY_MESSAGE alone is the wrong filter and this is where the capture paid for it:
            // WhatsApp's real chat notifications carry NO category at all (only their bundle summary
            // says "21 messages from 3 chats"), so a re-sync used to replay the summary and skip every
            // actual chat - WhatsApp looked permanently broken after the OEM killed the service.
            val n = sbn.notification ?: continue
            val isChat = NotificationContentExtractor.looksLikeConversation(n) ||
                n.category == Notification.CATEGORY_MESSAGE
            if (!isChat) continue
            Log.i(TRACE_TAG, "RESYNC replay ${sbn.packageName} ${sbn.key}")
            handlePosted(sbn, resync = true)
            replayed++
        }
        if (replayed > 0) lastDebugMessage = "Re-synced $replayed live notification(s)"
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
        lastDebugMessage = "Notification listener disconnected"
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        handlePosted(sbn, resync = false)
    }

    /**
     * Every rejection is logged with a reason. That is the point of this function: the previous
     * version had 15 bare `return`s, so "Instagram messages never show up" was undiagnosable except
     * by guessing. Filter decisions belong in the log, not in the user's patience.
     */
    private fun handlePosted(sbn: StatusBarNotification, resync: Boolean) {
        // RE-BINDING HOOK for system stability (unchanged: not touched in this fix)
        try {
            val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            if (am.isEnabled) { am.interrupt() }
        } catch (_: Exception) {}

        val pkg = sbn.packageName ?: return drop("no-package", sbn)
        val notification = sbn.notification ?: return drop("no-notification", sbn)

        // Anything posted right now wins over a removal that is still inside its grace window.
        cancelPendingRemoval(sbn.key)

        if (pkg == packageName) return drop("own-notification", sbn)
        if (!AppSettings.isIslandEnabled(this)) return drop("island-disabled", sbn)

        // A group summary is a container, not a message. If it is all we got (the app bundles DMs and
        // hides the children), pulling the children from the live shelf still shows the real chat.
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) {
            val children = try { getActiveNotifications() } catch (_: Exception) { null }
            val groupName = notification.group
            // Children carry the group NAME, the summary carries the same name in Notification.getGroup();
            // StatusBarNotification.key ("0|com.whatsapp|1|null|10289") never equals it, which is why the
            // first version of this branch matched nothing. Same package is not optional either: WhatsApp
            // and the ShareKaro app both use a plain group name ("group_key_messages" / "group").
            val members: List<StatusBarNotification> = if (groupName.isNullOrBlank()) emptyList() else children
                ?.filter {
                    it.key != sbn.key &&
                        it.packageName == sbn.packageName &&
                        it.notification?.group == groupName &&
                        (it.notification?.flags?.and(Notification.FLAG_GROUP_SUMMARY) ?: 0) == 0
                }
                ?.sortedByDescending { it.postTime }
                .orEmpty()
            if (members.isEmpty()) return drop("group-summary-without-children", sbn)
            var handled = false
            for (child in members) {
                if (child.notification?.let { isPermanentNonRemovable(it) } == true) continue
                handlePosted(child, resync = resync)
                handled = true
                break // newest readable child is enough; the ring does the rest
            }
            if (!handled) return drop("group-summary-children-filtered", sbn)
            return
        }

        PillIconCache.record(pkg, notification.smallIcon, notification.icon) // cache real icon data for Pill Icon Lab

        val extracted = NotificationContentExtractor.extract(this, sbn)
            ?: return drop("extractor-null-empty-notification", sbn)
        val appName = extracted.appName
        val title = extracted.conversationTitle
        val message = extracted.latestMessage

        cancelPendingRemoval(extracted.conversationKey)

        if (title.isBlank() && message.isBlank()) return drop("blank-title-and-body", sbn)

        if (isPermanentNonRemovable(notification)) return drop("ongoing-or-persistent", sbn, "msgStyle=${extracted.isMessagingStyle}")

        if (ReplyEchoSuppressor.shouldSuppress(pkg, title, message, notification, sbn.key)) {
            lastDebugMessage = "SUPPRESSED REPLY ECHO: $appName | $title"
            return // already logged with detail inside the suppressor
        }

        val now = System.currentTimeMillis()
        val fingerprint = "${sbn.key.orEmpty()}|${sbn.postTime}"
        if (isZombieRepeat(fingerprint, now)) return drop("zombie-repeat", sbn, "fp=$fingerprint")

        if (resync) {
            val seenAt = recentlyHandled[sbn.key] ?: 0L
            if (now - seenAt < 10_000L) return drop("already-shown-before-resync", sbn)
        }
        recentlyHandled[sbn.key] = now
        if (recentlyHandled.size > 128) {
            val stale = recentlyHandled.filterValues { now - it > 60_000L }.keys
            if (stale.isNotEmpty()) recentlyHandled.keys.removeAll(stale)
        }

        lastDebugMessage = "SHOWN: $appName | $title"
        Log.i(TRACE_TAG, "SHOW $pkg thread=\"$title\" unread=${extracted.unreadCount} " +
            "identity=${extracted.conversationKeySource} resync=$resync")

        val actionList = notification.actions?.toList() ?: emptyList()

        HyperAccessibilityService.showNotificationFromApp(
            context = this,
            packageName = pkg,
            notificationKey = sbn.key,
            appName = appName,
            title = title,
            message = message,
            unreadCount = extracted.unreadCount,
            conversationKey = extracted.conversationKey,
            conversationKeySource = extracted.conversationKeySource,
            postTime = sbn.postTime,
            contentIntent = notification.contentIntent,
            actions = actionList,
            smallIcon = notification.smallIcon,
            isMessagingStyle = NotificationContentExtractor.looksLikeConversation(notification),
            displayTimeMs = extracted.displayTimeMs
        )
        TraceLog.ingest(
            "listener show ${sbn.packageName} key=${extracted.conversationKeySource} " +
                "title='${extracted.conversationTitle}' rule=${extracted.titleRule} unread=${extracted.unreadCount} " +
                "actions=${notification.actions?.size ?: 0}"
        )
    }

    /**
     * "The app removed this notification" finally means something. Two reasons are honoured: the user
     * swiped it away in the shade, or the app cancelled it (opening the chat in WhatsApp/Instagram does
     * this, capture reason = 8). Everything else - a group summary being torn down, a re-post - is
     * ignored, because those are not the user reading anything.
     */
    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?, reason: Int) {
        super.onNotificationRemoved(sbn, rankingMap, reason)
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return
        if (reason == REASON_CANCEL_ALL || reason == REASON_APP_CANCEL_ALL) {
            // Clearing the whole shelf is already owned by the shade-open path in the island, which
            // marks everything seen; doing it twice would fight over the same state.
            Log.i(TRACE_TAG, "IGNORE removal reason=$reason pkg=$pkg (bulk: shade owns this)")
            return
        }
        if (reason != REASON_CANCEL && reason != REASON_APP_CANCEL && reason != REASON_TIMEOUT) {
            Log.i(TRACE_TAG, "IGNORE removal reason=$reason pkg=$pkg")
            return
        }
        val extracted = sbn.notification?.let { NotificationContentExtractor.extract(this, sbn) }
        val key = extracted?.conversationKey
        if (key.isNullOrBlank()) {
            drop("removal-without-conversation-key", sbn, "reason=$reason")
            return
        }
        val notificationKey = sbn.key
        val task = Runnable {
            pendingRemovals.remove(key)
            pendingRemovals.remove(notificationKey)
            Log.i(TRACE_TAG, "REMOVE reason=$reason pkg=$pkg thread=\"${extracted.conversationTitle}\"")
            HyperAccessibilityService.dismissConversationFromApp(this, key)
        }
        pendingRemovals[key]?.let { mainHandler.removeCallbacks(it) }
        pendingRemovals[key] = task
        notificationKey?.let { pendingRemovals[it] = task }
        mainHandler.postDelayed(task, REMOVAL_GRACE_MS)
    }

    private fun cancelPendingRemoval(key: String?) {
        if (key.isNullOrBlank()) return
        pendingRemovals.remove(key)?.let { mainHandler.removeCallbacks(it) }
    }

    private fun drop(reason: String, sbn: StatusBarNotification?, extra: String = "") {
        TraceLog.ingest("listener drop $reason pkg=${sbn?.packageName} key=${sbn?.key} $extra")
    }

    /**
     * An ongoing/foreground marker means "not swipeable away", it does NOT mean "not a message".
     * Chat apps legitimately mark a live conversation ongoing (chat open, typing, delivery pending),
     * so exempting messaging notifications is what stopped real DMs from being silently discarded.
     */
    private fun isPermanentNonRemovable(notification: Notification): Boolean {
        val flags = notification.flags
        val messaging = notification.category == Notification.CATEGORY_MESSAGE
        if (flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return true
        if (flags and Notification.FLAG_NO_CLEAR != 0 && !messaging) return true
        return flags and Notification.FLAG_ONGOING_EVENT != 0 && !messaging
    }

    /**
     * Repeat suppression, re-keyed on notification identity + post time instead of the message text.
     * Keying on text meant a second "hi" from the same thread within the block window was invisible
     * for 2 minutes. A genuine new message always carries a fresh postTime, so it now passes.
     */
    private fun isZombieRepeat(fingerprint: String, now: Long): Boolean {
        val info = repeatMap[fingerprint] ?: RepeatInfo(0, now, now, 0L).also { repeatMap[fingerprint] = it }
        if (now < info.blockedUntil) return true
        if (now - info.lastSeenAt <= 10_000L) {
            info.count += 1
            info.lastSeenAt = now
        } else {
            info.count = 1
            info.firstSeenAt = now
            info.lastSeenAt = now
            info.blockedUntil = 0L
            return false
        }
        if (info.count >= 3 && now - info.firstSeenAt <= 30_000L) {
            info.blockedUntil = now + ZOMBIE_BLOCK_MS
            return true
        }
        // The map used to only ever grow; entries for dead notifications are worthless.
        if (repeatMap.size > ZOMBIE_SWEEP_AFTER) {
            val stale = repeatMap.filterValues { now - it.lastSeenAt > ZOMBIE_BLOCK_MS }.keys
            if (stale.isNotEmpty()) repeatMap.keys.removeAll(stale)
        }
        return false
    }
}
