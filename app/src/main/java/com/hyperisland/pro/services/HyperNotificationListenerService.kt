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

    private var lastKey: String = ""
    private var lastTime: Long = 0L

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        lastDebugMessage = "Notification listener connected"

        try {
            Toast.makeText(this, "Hyper Island notification listener connected", Toast.LENGTH_SHORT).show()
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

        if (pkg == packageName) {
            lastDebugMessage = "Ignored own app notification"
            return
        }

        if (!AppSettings.isIslandEnabled(this)) {
            lastDebugMessage = "Ignored notification because island is OFF"
            return
        }

        val notification = sbn.notification ?: return

        // Ignore group summaries, but allow normal ongoing notifications for now.
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) {
            lastDebugMessage = "Ignored group summary from $pkg"
            return
        }

        val title = extractTitle(notification)
        val message = extractMessage(notification)

        if (title.isBlank() && message.isBlank()) {
            lastDebugMessage = "Ignored empty notification from $pkg"
            return
        }

        val appName = getAppName(pkg)

        val duplicateKey = "$pkg|$title|$message"
        val now = System.currentTimeMillis()

        if (duplicateKey == lastKey && now - lastTime < 2500L) {
            lastDebugMessage = "Ignored duplicate notification from $appName"
            return
        }

        lastKey = duplicateKey
        lastTime = now

        lastDebugMessage = "Received notification: $appName | $title | $message"

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

    private fun extractTitle(notification: Notification): String {
        val extras = notification.extras

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
        if (!title.isNullOrBlank()) return title

        val titleBig = extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()?.trim()
        if (!titleBig.isNullOrBlank()) return titleBig

        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim()
        if (!subText.isNullOrBlank()) return subText

        return ""
    }

    private fun extractMessage(notification: Notification): String {
        val extras = notification.extras

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
