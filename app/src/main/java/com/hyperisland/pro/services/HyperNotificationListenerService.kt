package com.hyperisland.pro.services

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.accessibility.AccessibilityManager
import com.hyperisland.pro.core.AppSettings

class HyperNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TRACE_TAG = "HIP_TRACE"
        @Volatile var isConnected: Boolean = false
        @Volatile var lastDebugMessage: String = "Notification listener not connected"

        /** How long a re-posted notification stays blocked as a zombie repeat. */
        private const val ZOMBIE_BLOCK_MS = 20_000L
        private const val ZOMBIE_SWEEP_AFTER = 64
    }

    private data class RepeatInfo(var count: Int, var firstSeenAt: Long, var lastSeenAt: Long, var blockedUntil: Long)
    private val repeatMap = HashMap<String, RepeatInfo>()

    // Threads seen since the last connect, so a re-sync cannot double-post what already arrived.
    private val recentlyHandled = HashMap<String, Long>()

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
            // Only chat-shaped notifications get replayed; do not resurrect music/timer noise.
            val isChat = sbn.notification?.category == Notification.CATEGORY_MESSAGE
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

        if (pkg == packageName) return drop("own-notification", sbn)
        if (!AppSettings.isIslandEnabled(this)) return drop("island-disabled", sbn)

        // A group summary is a container, not a message. If it is all we got (the app bundles DMs and
        // hides the children), pulling the children from the live shelf still shows the real chat.
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) {
            val children = try { getActiveNotifications() } catch (_: Exception) { null }
            val members = children?.filter { it.key != sbn.key && it.notification?.groupKey == sbn.key }
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
            smallIcon = notification.smallIcon
        )
    }

    private fun drop(reason: String, sbn: StatusBarNotification?, extra: String = "") {
        Log.i(TRACE_TAG, "DROP $reason pkg=${sbn?.packageName} key=${sbn?.key} $extra")
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
