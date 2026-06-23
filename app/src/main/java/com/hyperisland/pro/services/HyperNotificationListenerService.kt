package com.hyperisland.pro.services

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.os.Handler
import android.os.Looper
import com.hyperisland.pro.core.AppSettings

class HyperNotificationListenerService : NotificationListenerService() {

    companion object {
        @Volatile var isConnected: Boolean = false
        @Volatile var lastDebugMessage: String = "Notification listener not connected"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private data class RepeatInfo(var count: Int, var firstSeenAt: Long, var lastSeenAt: Long, var blockedUntil: Long)
    private val repeatMap = HashMap<String, RepeatInfo>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        lastDebugMessage = "Notification listener connected"
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        val notification = sbn.notification ?: return
        if (pkg == packageName || !AppSettings.isIslandEnabled(this)) return

        val appName = getAppName(pkg)
        val title = (notification.extras.getCharSequence(Notification.EXTRA_TITLE) ?: "").toString().trim()
        val message = (notification.extras.getCharSequence(Notification.EXTRA_TEXT) ?: "").toString().trim()

        if (title.isBlank() && message.isBlank()) return

        val actions = mutableListOf<String>()
        notification.actions?.forEach { it.title?.toString()?.let { t -> if (t.isNotBlank()) actions.add(t) } }

        HyperAccessibilityService.showNotificationFromApp(
            context = this,
            packageName = pkg,
            appName = appName,
            title = title,
            message = message,
            postTime = sbn.postTime,
            contentIntent = notification.contentIntent,
            actions = if (actions.isEmpty()) null else actions
        )
    }

    private fun getAppName(pkg: String): String = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
}
