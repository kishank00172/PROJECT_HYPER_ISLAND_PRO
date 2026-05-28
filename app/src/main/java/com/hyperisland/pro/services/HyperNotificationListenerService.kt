package com.hyperisland.pro.services

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.widget.Toast
import com.hyperisland.pro.core.AppSettings

class HyperNotificationListenerService : NotificationListenerService() {

    companion object {
        @Volatile
        var isConnected: Boolean = false
            private set

        @Volatile
        var lastDebugMessage: String = "Notification listener not connected"
            private set
    }

    private data class RepeatInfo(
        var count: Int,
        var firstSeenAt: Long,
        var lastSeenAt: Long,
        var blockedUntil: Long
    )

    private val repeatMap = HashMap<String, RepeatInfo>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        lastDebugMessage = "Notification listener connected"

        try {
            Toast.makeText(
                this,
                "Hyper Island notification listener connected",
                Toast.LENGTH_SHORT
            ).show()
        } catch (_: Exception) {
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
        lastDebugMessage = "Notification listener disconnected"
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        val pkg = sbn.packageName ?: return
        val notification = sbn.notification ?: return

        if (pkg == packageName) {
            lastDebugMessage = "Ignored own app notification"
            return
        }

        if (!AppSettings.isIslandEnabled(this)) {
            lastDebugMessage = "Ignored because island OFF: $pkg"
            return
        }

        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) {
            lastDebugMessage = "Ignored group summary: $pkg"
            return
        }

        val appName = getAppName(pkg)
        val title = extractTitle(notification)
        val message = extractMessage(notification)

        if (title.isBlank() && message.isBlank()) {
            lastDebugMessage = "Ignored empty notification: $appName ($pkg)"
            return
        }

        /*
         * New rule:
         * Show every normal clearable notification.
         * Ignore only permanent/non-removable service/status notifications,
         * and zombie notifications that rapidly recreate/update themselves.
         */

        if (isPermanentNonRemovable(notification)) {
            lastDebugMessage = "Ignored permanent/non-removable notification: $appName"
            return
        }

        val fingerprint = "$pkg|${sbn.id}|${sbn.tag ?: ""}|$title|$message"
        val now = System.currentTimeMillis()

        if (isZombieRepeat(fingerprint, now, appName)) {
            return
        }

        lastDebugMessage = "SHOWN: $appName | $title | $message"

        HyperAccessibilityService.showNotificationFromApp(
            context = this,
            packageName = pkg,
            appName = appName,
            title = title,
            message = message,
            postTime = sbn.postTime,
            contentIntent = notification.contentIntent
        )
    }

    private fun isPermanentNonRemovable(notification: Notification): Boolean {
        val flags = notification.flags

        /*
         * These are normally persistent service/status notifications.
         * They should not trigger the island as regular notifications.
         */
        return (flags and Notification.FLAG_ONGOING_EVENT) != 0 ||
            (flags and Notification.FLAG_FOREGROUND_SERVICE) != 0 ||
            (flags and Notification.FLAG_NO_CLEAR) != 0
    }

    private fun isZombieRepeat(
        fingerprint: String,
        now: Long,
        appName: String
    ): Boolean {
        val info = repeatMap[fingerprint]

        if (info == null) {
            repeatMap[fingerprint] = RepeatInfo(
                count = 1,
                firstSeenAt = now,
                lastSeenAt = now,
                blockedUntil = 0L
            )
            return false
        }

        if (now < info.blockedUntil) {
            lastDebugMessage = "Ignored zombie/repeating notification: $appName"
            return true
        }

        val timeSinceFirst = now - info.firstSeenAt
        val timeSinceLast = now - info.lastSeenAt

        if (timeSinceLast <= 10_000L) {
            info.count += 1
            info.lastSeenAt = now
        } else {
            info.count = 1
            info.firstSeenAt = now
            info.lastSeenAt = now
            info.blockedUntil = 0L
            return false
        }

        /*
         * If the exact same notification appears/updates 3 times within 30 sec,
         * treat it as zombie/status spam and silence it for 2 minutes.
         *
         * First and second occurrences still show.
         * Third and later are suppressed.
         */
        if (info.count >= 3 && timeSinceFirst <= 30_000L) {
            info.blockedUntil = now + 120_000L
            lastDebugMessage = "Marked and ignored zombie notification: $appName"
            return true
        }

        return false
    }

    private fun extractTitle(notification: Notification): String {
        val extras = notification.extras

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
        if (!title.isNullOrBlank()) return title

        val titleBig = extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()?.trim()
        if (!titleBig.isNullOrBlank()) return titleBig

        val conversationTitle =
            extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim()
        if (!conversationTitle.isNullOrBlank()) return conversationTitle

        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim()
        if (!subText.isNullOrBlank()) return subText

        return ""
    }

    private fun extractMessage(notification: Notification): String {
        val extras = notification.extras

        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (!messages.isNullOrEmpty()) {
            val last = messages.lastOrNull()
            val text = try {
                val bundle = last as? android.os.Bundle
                bundle?.getCharSequence("text")?.toString()?.trim()
            } catch (_: Exception) {
                null
            }

            if (!text.isNullOrBlank()) return text
        }

        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
        if (!bigText.isNullOrBlank()) return bigText

        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
        if (!text.isNullOrBlank()) return text

        val summary = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()?.trim()
        if (!summary.isNullOrBlank()) return summary

        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        if (!lines.isNullOrEmpty()) {
            return lines.joinToString("\n") { it.toString() }.trim()
        }

        return ""
    }

    private fun getAppName(pkg: String): String {
        return try {
            val info = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            pkg
        }
    }
}
