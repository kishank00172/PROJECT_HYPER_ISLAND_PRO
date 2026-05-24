package com.hyperisland.pro.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.hyperisland.pro.MainActivity
import com.hyperisland.pro.R
import com.hyperisland.pro.core.AppSettings

class IslandOverlayService : Service() {

    companion object {
        const val ACTION_SHOW = "com.hyperisland.pro.action.SHOW_ISLAND"
        const val ACTION_HIDE = "com.hyperisland.pro.action.HIDE_ISLAND"
        const val ACTION_REFRESH = "com.hyperisland.pro.action.REFRESH_ISLAND"

        /*
         * Important:
         * This action stops only the fallback application overlay service.
         * It must NOT mark the whole island as OFF, because Accessibility Overlay
         * may already be running as the primary engine.
         */
        const val ACTION_STOP_FALLBACK_ONLY =
            "com.hyperisland.pro.action.STOP_FALLBACK_ONLY"

        private const val CHANNEL_ID = "hyper_island_overlay"
        private const val NOTIFICATION_ID = 1001

        @Volatile
        var isRunning: Boolean = false
            private set
    }

    private var windowManager: WindowManager? = null
    private var islandView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        windowManager = getSystemService(WindowManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_FALLBACK_ONLY -> {
                removeIsland()
                stopForeground(STOP_FOREGROUND_REMOVE)
                cancelForegroundNotification()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_HIDE -> {
                AppSettings.setIslandEnabled(this, false)
                AppSettings.setOverlayEngine(this, AppSettings.ENGINE_NONE)
                removeIsland()
                stopForeground(STOP_FOREGROUND_REMOVE)
                cancelForegroundNotification()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_REFRESH -> {
                if (AppSettings.isIslandEnabled(this)) {
                    ensureForeground()
                    if (islandView == null) {
                        showIsland()
                    } else {
                        updateIslandLayout()
                    }
                }
            }

            ACTION_SHOW, null -> {
                AppSettings.setIslandEnabled(this, true)
                AppSettings.setOverlayEngine(this, AppSettings.ENGINE_APPLICATION)
                ensureForeground()
                showIsland()
            }
        }

        return START_STICKY
    }

    private fun ensureForeground() {
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {
            // Do not crash if notification permission/system policy blocks display.
        }
    }

    private fun showIsland() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Overlay permission missing", Toast.LENGTH_SHORT).show()
            return
        }

        if (islandView != null) {
            updateIslandLayout()
            return
        }

        val view = View(this).apply {
            background = createIslandBackground()
            elevation = dp(24).toFloat()
            alpha = 1f

            setOnClickListener {
                Toast.makeText(
                    this@IslandOverlayService,
                    "Application overlay pill tapped",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }

        val params = createLayoutParams()
        islandView = view
        layoutParams = params

        try {
            windowManager?.addView(view, params)
        } catch (e: Exception) {
            islandView = null
            layoutParams = null
            Toast.makeText(this, "Overlay failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateIslandLayout() {
        val view = islandView ?: return
        val params = layoutParams ?: return

        params.width = dp(AppSettings.getIslandWidthDp(this))
        params.height = dp(AppSettings.getIslandHeightDp(this))
        params.x = dp(AppSettings.getIslandXDp(this))
        params.y = dp(AppSettings.getIslandYDp(this))

        view.background = createIslandBackground()

        try {
            windowManager?.updateViewLayout(view, params)
        } catch (_: Exception) {
            // Ignore update race.
        }
    }

    private fun removeIsland() {
        val view = islandView ?: return

        try {
            windowManager?.removeView(view)
        } catch (_: Exception) {
            // Already removed.
        } finally {
            islandView = null
            layoutParams = null
        }
    }

    private fun createLayoutParams(): WindowManager.LayoutParams {
        val width = dp(AppSettings.getIslandWidthDp(this))
        val height = dp(AppSettings.getIslandHeightDp(this))
        val offsetX = dp(AppSettings.getIslandXDp(this))
        val offsetY = dp(AppSettings.getIslandYDp(this))

        return WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = offsetX
            y = offsetY

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }

            title = "Hyper Island Pro Application Pill"
        }
    }

    private fun createIslandBackground(): GradientDrawable {
        val height = AppSettings.getIslandHeightDp(this)

        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.BLACK)
            cornerRadius = dp(height / 2).toFloat()
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = android.app.PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_island_logo)
            .setContentTitle("HYPER ISLAND PRO")
            .setContentText("Fallback application overlay is running")
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Hyper Island Overlay",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps fallback application overlay service alive."
            setShowBadge(false)
        }

        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun cancelForegroundNotification() {
        try {
            getSystemService(NotificationManager::class.java)
                .cancel(NOTIFICATION_ID)
        } catch (_: Exception) {
            // Ignore.
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        removeIsland()
        stopForeground(STOP_FOREGROUND_REMOVE)
        cancelForegroundNotification()
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
