package com.hyperisland.pro.services

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.accessibility.AccessibilityManager
import com.hyperisland.pro.core.AppSettings

class HyperNotificationListenerService : NotificationListenerService() {

    companion object {
        @Volatile var isConnected: Boolean = false
        @Volatile var lastDebugMessage: String = "Notification listener not connected"
    }

    private data class RepeatInfo(var count: Int, var firstSeenAt: Long, var lastSeenAt: Long, var blockedUntil: Long)
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

        // RE-BINDING HOOK for system stability
        try {
            val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            if (am.isEnabled) { am.interrupt() }
        } catch (_: Exception) {}

        val pkg = sbn.packageName ?: return
        val notification = sbn.notification ?: return

        if (pkg == packageName || !AppSettings.isIslandEnabled(this)) return
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val appName = getAppName(pkg)
        val title = (notification.extras.getCharSequence(Notification.EXTRA_TITLE) ?: "").toString().trim()
        val message = (notification.extras.getCharSequence(Notification.EXTRA_TEXT) ?: "").toString().trim()

        if (title.isBlank() && message.isBlank()) return
        
        // Robust Spam/Permanent Filtering
        if (isPermanentNonRemovable(notification)) return
        
        val fingerprint = "$pkg|${sbn.id}|${sbn.tag ?: ""}|$title|$message"
        val now = System.currentTimeMillis()
        if (isZombieRepeat(fingerprint, now, appName)) return

        lastDebugMessage = "SHOWN: $appName | $title"

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
            actions = actionList
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

    private fun getAppName(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }
}
