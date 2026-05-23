package com.hyperisland.pro.services

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.hyperisland.pro.core.AppSettings

class HyperAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        private var instance: HyperAccessibilityService? = null

        fun isConnected(): Boolean {
            return instance != null
        }

        fun showIslandFromApp(context: Context): Boolean {
            val service = instance ?: return false
            AppSettings.setIslandEnabled(service, true)
            AppSettings.setOverlayEngine(service, AppSettings.ENGINE_ACCESSIBILITY)
            service.showIsland()
            return true
        }

        fun hideIslandFromApp(): Boolean {
            val service = instance ?: return false
            service.hideIsland()
            AppSettings.setIslandEnabled(service, false)
            AppSettings.setOverlayEngine(service, AppSettings.ENGINE_NONE)
            return true
        }

        fun refreshIslandFromApp(): Boolean {
            val service = instance ?: return false
            if (AppSettings.isIslandEnabled(service)) {
                service.updateIslandLayout()
            }
            return true
        }
    }

    private var windowManager: WindowManager? = null
    private var islandView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(WindowManager::class.java)

        if (
            AppSettings.isIslandEnabled(this) &&
            AppSettings.getOverlayEngine(this) == AppSettings.ENGINE_ACCESSIBILITY
        ) {
            showIsland()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Phase 1.1: overlay engine only.
        // Later phases will use events for notification panel awareness and lockscreen/window detection.
    }

    override fun onInterrupt() = Unit

    private fun showIsland() {
        if (islandView != null) {
            updateIslandLayout()
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
            // Window may already be removed by system.
        }
    }

    private fun hideIsland() {
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
        hideIsland()
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }
}
