package com.hyperisland.pro.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import com.hyperisland.pro.MainActivity
import com.hyperisland.pro.R
import com.hyperisland.pro.core.AppSettings

class IslandOverlayService : Service() {

    companion object {
        const val ACTION_SHOW = "com.hyperisland.pro.action.SHOW_ISLAND"
        const val ACTION_HIDE = "com.hyperisland.pro.action.HIDE_ISLAND"
        const val ACTION_REFRESH = "com.hyperisland.pro.action.REFRESH_ISLAND"
        private const val CHANNEL_ID = "hyper_island_service_channel"
        private const val NOTIFICATION_ID = 1
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // MANDATORY: Create channel and start foreground IMMEDIATELY
        // This prevents the ForegroundServiceDidNotStartInTimeException crash
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        // GHOST FIX: If Accessibility Service is already running, we don't need this overlay.
        if (isAccessibilityServiceEnabled(this)) {
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_HIDE -> {
                stopForeground(true)
                stopSelf()
            }
            ACTION_REFRESH -> {
                HyperAccessibilityService.refreshIslandFromApp(this)
            }
        }
        return START_STICKY
    }

    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(context, HyperAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(":").any { it.equals(expected, ignoreCase = true) }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setContentTitle("Hyper Island Pro").setContentText("Background service active").setSmallIcon(R.drawable.ic_island_logo).setContentIntent(pendingIntent).setOngoing(true).build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Hyper Island Service", NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
