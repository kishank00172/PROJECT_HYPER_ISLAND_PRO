package com.hyperisland.pro.services

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.hyperisland.pro.core.AppSettings

class HyperNotificationListenerService : NotificationListenerService() {
    companion object {
        @Volatile var isConnected: Boolean = false
        @Volatile var lastDebugMessage: String = "Notification listener not connected"
    }

    override fun onListenerConnected() { super.onListenerConnected(); isConnected = true }
    override fun onListenerDisconnected() { super.onListenerDisconnected(); isConnected = false }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return
        val notification = sbn.notification ?: return
        if (pkg == packageName || !AppSettings.isIslandEnabled(this)) return

        val title = (notification.extras.getCharSequence(Notification.EXTRA_TITLE) ?: "").toString().trim()
        val message = (notification.extras.getCharSequence(Notification.EXTRA_TEXT) ?: "").toString().trim()
        if (title.isBlank() && message.isBlank()) return

        val actionList = notification.actions?.toList() ?: emptyList()
        HyperAccessibilityService.showNotificationFromApp(this, pkg, getAppName(pkg), title, message, sbn.postTime, notification.contentIntent, actionList)
    }

    private fun getAppName(pkg: String): String = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
}
