package com.hyperisland.pro.services

import android.accessibilityservice.AccessibilityService
import android.content.Context
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
import android.widget.FrameLayout
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

            service.postShowIsland()
            return true
        }

        fun hideIslandFromApp(context: Context? = null): Boolean {
            val service = instance ?: return false

            service.postHideIsland()
            return true
        }

        fun refreshIslandFromApp(context: Context): Boolean {
            val service = instance ?: return false

            service.postUpdateIsland()
            return true
        }

        fun expandIslandFromApp(context: Context): Boolean {
            val service = instance ?: return false

            service.postExpandIsland()
            return true
        }

        fun collapseIslandFromApp(context: Context): Boolean {
            val service = instance ?: return false

            service.postCollapseIsland()
            return true
        }

        fun toggleExpandFromApp(context: Context): Boolean {
            val service = instance ?: return false

            service.postToggleExpanded()
            return true
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var islandView: FrameLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var islandBackground: GradientDrawable? = null
    private var isExpanded = false

    override fun onServiceConnected() {
        super.onServiceConnected()

        instance = this
        windowManager = getSystemService(WindowManager::class.java)

        if (
            AppSettings.isIslandEnabled(this) &&
            AppSettings.getOverlayEngine(this) == AppSettings.ENGINE_ACCESSIBILITY
        ) {
            postShowIsland()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Phase 2 safe mode:
        // only overlay + direct expand/collapse.
        // No risky frame animation here.
    }

    override fun onInterrupt() = Unit

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
            if (!AppSettings.isIslandEnabled(this)) return@post

            if (islandView == null) {
                showIslandInternal()
            } else {
                updateIslandLayoutInternal()
            }
        }
    }

    private fun postExpandIsland() {
        mainHandler.post {
            if (!AppSettings.isIslandEnabled(this)) return@post

            if (islandView == null) {
                showIslandInternal()
            }

            setExpanded(true)
        }
    }

    private fun postCollapseIsland() {
        mainHandler.post {
            if (!AppSettings.isIslandEnabled(this)) return@post

            setExpanded(false)
        }
    }

    private fun postToggleExpanded() {
        mainHandler.post {
            if (!AppSettings.isIslandEnabled(this)) return@post

            if (islandView == null) {
                showIslandInternal()
            }

            setExpanded(!isExpanded)
        }
    }

    private fun showIslandInternal() {
        if (islandView != null) {
            updateIslandLayoutInternal()
            return
        }

        isExpanded = false

        islandBackground = createIslandBackground(
            cornerRadiusPx = compactCornerRadiusPx()
        )

        val root = FrameLayout(this).apply {
            background = islandBackground
            elevation = dp(30).toFloat()
            alpha = 1f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO

            setOnClickListener {
                postToggleExpanded()
            }
        }

        val params = createLayoutParams()

        islandView = root
        layoutParams = params

        try {
            windowManager?.addView(root, params)
        } catch (e: Exception) {
            islandView = null
            layoutParams = null
            islandBackground = null

            Toast.makeText(
                this,
                "Accessibility overlay failed: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun setExpanded(expanded: Boolean) {
        if (isExpanded == expanded) return

        isExpanded = expanded
        updateIslandLayoutInternal()
    }

    private fun updateIslandLayoutInternal() {
        val root = islandView ?: return
        val params = layoutParams ?: return

        params.width = dp(currentTargetWidthDp())
        params.height = dp(currentTargetHeightDp())
        params.x = dp(AppSettings.getIslandXDp(this))
        params.y = dp(AppSettings.getIslandYDp(this))

        islandBackground?.cornerRadius = if (isExpanded) {
            dp(34).toFloat()
        } else {
            compactCornerRadiusPx().toFloat()
        }

        try {
            windowManager?.updateViewLayout(root, params)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Island update failed: ${e.message}",
                Toast.LENGTH_SHORT
            ).show()
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
        islandBackground = null
        isExpanded = false
    }

    private fun createLayoutParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            dp(currentTargetWidthDp()),
            dp(currentTargetHeightDp()),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = dp(AppSettings.getIslandXDp(this@HyperAccessibilityService))
            y = dp(AppSettings.getIslandYDp(this@HyperAccessibilityService))

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }

            title = "Hyper Island Pro Accessibility Pill"
        }
    }

    private fun createIslandBackground(cornerRadiusPx: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.BLACK)
            cornerRadius = cornerRadiusPx.toFloat()
        }
    }

    private fun currentTargetWidthDp(): Int {
        return if (isExpanded) {
            AppSettings.getIslandExpandedWidthDp(this)
        } else {
            AppSettings.getIslandWidthDp(this)
        }
    }

    private fun currentTargetHeightDp(): Int {
        return if (isExpanded) {
            AppSettings.getIslandExpandedHeightDp(this)
        } else {
            AppSettings.getIslandHeightDp(this)
        }
    }

    private fun compactCornerRadiusPx(): Int {
        return dp(AppSettings.getIslandHeightDp(this) / 2)
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        hideIslandInternal()

        if (instance === this) {
            instance = null
        }

        super.onDestroy()
    }
}
