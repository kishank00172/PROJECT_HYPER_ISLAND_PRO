package com.hyperisland.pro.services

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.hyperisland.pro.core.AppSettings

class HyperAccessibilityService : AccessibilityService() {

    companion object {
        const val ACTION_ACCESSIBILITY_SHOW =
            "com.hyperisland.pro.action.ACCESSIBILITY_SHOW_ISLAND"
        const val ACTION_ACCESSIBILITY_HIDE =
            "com.hyperisland.pro.action.ACCESSIBILITY_HIDE_ISLAND"
        const val ACTION_ACCESSIBILITY_REFRESH =
            "com.hyperisland.pro.action.ACCESSIBILITY_REFRESH_ISLAND"

        @Volatile
        private var instance: HyperAccessibilityService? = null

        fun isConnected(): Boolean {
            return instance != null
        }

        fun showIslandFromApp(context: Context): Boolean {
            val service = instance ?: return false

            AppSettings.setIslandEnabled(service, true)
            AppSettings.setOverlayEngine(service, AppSettings.ENGINE_ACCESSIBILITY)

            service.postShowIsland()

            context.sendBroadcast(
                Intent(ACTION_ACCESSIBILITY_SHOW).setPackage(context.packageName)
            )

            return true
        }

        fun hideIslandFromApp(context: Context? = null): Boolean {
            var handled = false

            instance?.let { service ->
                service.postHideIsland()
                handled = true
            }

            context?.sendBroadcast(
                Intent(ACTION_ACCESSIBILITY_HIDE).setPackage(context.packageName)
            )

            return handled
        }

        fun refreshIslandFromApp(): Boolean {
            val service = instance ?: return false
            service.postUpdateIsland()
            return true
        }

        fun refreshIslandFromApp(context: Context): Boolean {
            val service = instance

            context.sendBroadcast(
                Intent(ACTION_ACCESSIBILITY_REFRESH).setPackage(context.packageName)
            )

            service?.postUpdateIsland()
            return service != null
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var islandView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var receiverRegistered = false

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_ACCESSIBILITY_SHOW -> postShowIsland()
                ACTION_ACCESSIBILITY_HIDE -> postHideIsland()
                ACTION_ACCESSIBILITY_REFRESH -> postUpdateIsland()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        instance = this
        windowManager = getSystemService(WindowManager::class.java)

        registerCommandReceiver()

        if (
            AppSettings.isIslandEnabled(this) &&
            AppSettings.getOverlayEngine(this) == AppSettings.ENGINE_ACCESSIBILITY
        ) {
            postShowIsland()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Phase 1.1: overlay engine only.
        // Later: notification panel awareness, lockscreen/window detection.
    }

    override fun onInterrupt() = Unit

    private fun registerCommandReceiver() {
        if (receiverRegistered) return

        val filter = IntentFilter().apply {
            addAction(ACTION_ACCESSIBILITY_SHOW)
            addAction(ACTION_ACCESSIBILITY_HIDE)
            addAction(ACTION_ACCESSIBILITY_REFRESH)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(commandReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(commandReceiver, filter)
            }
            receiverRegistered = true
        } catch (_: Exception) {
            receiverRegistered = false
        }
    }

    private fun unregisterCommandReceiver() {
        if (!receiverRegistered) return

        try {
            unregisterReceiver(commandReceiver)
        } catch (_: Exception) {
            // Already unregistered.
        } finally {
            receiverRegistered = false
        }
    }

    private fun postShowIsland() {
        mainHandler.post {
            AppSettings.setIslandEnabled(this, true)
            AppSettings.setOverlayEngine(this, AppSettings.ENGINE_ACCESSIBILITY)
            showIslandInternal()
        }
    }

    private fun postHideIsland() {
        mainHandler.post {
            hideIslandInternal()
            AppSettings.setIslandEnabled(this, false)
            AppSettings.setOverlayEngine(this, AppSettings.ENGINE_NONE)
        }
    }

    private fun postUpdateIsland() {
        mainHandler.post {
            if (AppSettings.isIslandEnabled(this)) {
                if (islandView == null) {
                    showIslandInternal()
                } else {
                    updateIslandLayoutInternal()
                }
            }
        }
    }

    private fun showIslandInternal() {
        if (islandView != null) {
            updateIslandLayoutInternal()
            return
        }

        val view = View(this).apply {
            background = createIslandBackground()
            elevation = dp(30).toFloat()
            alpha = 1f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO

            setOnClickListener {
                it.animate()
                    .scaleX(0.94f)
                    .scaleY(0.94f)
                    .setDuration(70)
                    .withEndAction {
                        it.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(110)
                            .start()
                    }
                    .start()

                Toast.makeText(
                    this@HyperAccessibilityService,
                    "Accessibility island tapped",
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

            Toast.makeText(
                this,
                "Accessibility overlay failed: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun updateIslandLayoutInternal() {
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
            // Window may already be removed by system.
        }
    }

    private fun hideIslandInternal() {
        val view = islandView

        if (view != null) {
            try {
                windowManager?.removeViewImmediate(view)
            } catch (_: Exception) {
                try {
                    windowManager?.removeView(view)
                } catch (_: Exception) {
                    // Already removed or not attached.
                }
            }
        }

        islandView = null
        layoutParams = null
    }

    private fun createLayoutParams(): WindowManager.LayoutParams {
        val width = dp(AppSettings.getIslandWidthDp(this))
        val height = dp(AppSettings.getIslandHeightDp(this))
        val offsetX = dp(AppSettings.getIslandXDp(this))
        val offsetY = dp(AppSettings.getIslandYDp(this))

        return WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
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

            title = "Hyper Island Pro Accessibility Pill"
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

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        hideIslandInternal()
        unregisterCommandReceiver()

        if (instance === this) {
            instance = null
        }

        super.onDestroy()
    }
}
