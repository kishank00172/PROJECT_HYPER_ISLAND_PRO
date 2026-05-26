package com.hyperisland.pro.services

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.hyperisland.pro.core.AppSettings

class HyperNotificationListenerService : NotificationListenerService() {

    private var lastKey: String = ""
    private var lastTime: Long = 0L

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (!AppSettings.isIslandEnabled(this)) return
        if (sbn.packageName == packageName) return

        val notification = sbn.notification ?: return

        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()

        val message = when {
            bigText.isNotEmpty() -> bigText
            text.isNotEmpty() -> text
            else -> ""
        }

        if (title.isEmpty() && message.isEmpty()) return

        val appName = getAppName(sbn.packageName)

        val duplicateKey = "${sbn.packageName}|$title|$message"
        val now = System.currentTimeMillis()

        if (duplicateKey == lastKey && now - lastTime < 2500L) {
            return
        }

        lastKey = duplicateKey
        lastTime = now

        HyperAccessibilityService.showNotificationFromApp(
            context = this,
            packageName = sbn.packageName,
            appName = appName,
            title = title,
            message = message,
            postTime = sbn.postTime,
            contentIntent = notification.contentIntent
        )
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
