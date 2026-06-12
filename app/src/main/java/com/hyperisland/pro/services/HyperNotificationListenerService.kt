package com.hyperisland.pro.services

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.accessibility.AccessibilityManager
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
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
        lastDebugMessage = "Notification listener disconnected"
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        // RE-BINDING HOOK
        try {
            val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            if (am.isEnabled) { am.interrupt() }
        } catch (_: Exception) {}

        val pkg = sbn.packageName ?: return
        val notification = sbn.notification ?: return

        if (pkg == packageName) return
        if (!AppSettings.isIslandEnabled(this)) return
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val appName = getAppName(pkg)
        val title = extractTitle(notification)
        val message = extractMessage(notification)

        if (title.isBlank() && message.isBlank()) return
        if (isPermanentNonRemovable(notification)) return

        val fingerprint = "$pkg|${sbn.id}|${sbn.tag ?: ""}|$title|$message"
        val now = System.currentTimeMillis()

        if (isZombieRepeat(fingerprint, now, appName)) return

        lastDebugMessage = "SHOWN: $appName | $title | $message"

        // PASSING ACTIONS TO ACCESSIBILITY SERVICE
        val actionList = notification.actions?.toList() ?: emptyList()

        HyperAccessibilityService.showNotificationFromApp(
            context = this,
            packageName = pkg,
            appName = appName,
            title = title,
            message = message,
            postTime = sbn.postTime,
            contentIntent = notification.contentIntent,
            actions = actionList // Pass real actions
        )
    }

    private fun isPermanentNonRemovable(notification: Notification): Boolean {
        val flags = notification.flags
        return (flags and Notification.FLAG_ONGOING_EVENT) != 0 ||
            (flags and Notification.FLAG_FOREGROUND_SERVICE) != 0 ||
            (flags and Notification.FLAG_NO_CLEAR) != 0
    }

    private fun isZombieRepeat(fingerprint: String, now: Long, appName: String): Boolean {
        val info = repeatMap[fingerprint] ?: RepeatInfo(0, now, now, 0L).also { repeatMap[fingerprint] = it }
        if (now < info.blockedUntil) return true
        if (now - info.lastSeenAt <= 10000L) {
            info.count += 1
            info.lastSeenAt = now
        } else {
            info.count = 1; info.firstSeenAt = now; info.lastSeenAt = now; info.blockedUntil = 0L
            return false
        }
        if (info.count >= 3 && now - info.firstSeenAt <= 30000L) {
            info.blockedUntil = now + 120000L
            return true
        }
        return false
    }

    private fun extractTitle(notification: Notification): String {
        val extras = notification.extras
        return (extras.getCharSequence(Notification.EXTRA_TITLE) ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG) ?: "").toString().trim()
    }

    private fun extractMessage(notification: Notification): String {
        val extras = notification.extras
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (!messages.isNullOrEmpty()) {
            val last = messages.lastOrNull() as? android.os.Bundle
            val text = last?.getCharSequence("text")?.toString()?.trim()
            if (!text.isNullOrBlank()) return text
        }
        return (extras.getCharSequence(Notification.EXTRA_TEXT) ?: extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT) ?: "").toString().trim()
    }

    private fun getAppName(pkg: String): String {
        return try {
            val info = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (_: Exception) { pkg }
    }
}
