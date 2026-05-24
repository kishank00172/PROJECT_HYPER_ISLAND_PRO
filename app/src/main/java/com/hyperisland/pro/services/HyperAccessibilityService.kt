package com.hyperisland.pro.services

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
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
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.Toast
import com.hyperisland.pro.core.AppSettings
import kotlin.math.roundToInt

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

    /*
     * Final Phase 2 architecture:
     *
     * 1. Visual window:
     *    - exact island size
     *    - black rounded background
     *    - NOT_TOUCHABLE
     *
     * 2. Touch window:
     *    - exact island size
     *    - fully invisible
     *    - touchable
     *
     * No large transparent max-size visual container.
     * This removes the grey rectangle artifact.
     */
    private val morphInterpolator = PathInterpolator(0.20f, 0.0f, 0.0f, 1.0f)

    private var windowManager: WindowManager? = null

    private var visualView: View? = null
    private var visualParams: WindowManager.LayoutParams? = null
    private var visualBackground: GradientDrawable? = null

    private var touchView: FrameLayout? = null
    private var touchParams: WindowManager.LayoutParams? = null

    private var morphAnimator: ValueAnimator? = null
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
        // Phase 2: exact-size visual + exact-size touch island.
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

            if (visualView == null || touchView == null) {
                showIslandInternal()
            } else {
                updateWindowsToCurrentSize()
            }
        }
    }

    private fun postExpandIsland() {
        mainHandler.post {
            if (!AppSettings.isIslandEnabled(this)) return@post

            if (visualView == null || touchView == null) {
                showIslandInternal()
            }

            setExpandedAnimated(true)
        }
    }

    private fun postCollapseIsland() {
        mainHandler.post {
            if (!AppSettings.isIslandEnabled(this)) return@post
            setExpandedAnimated(false)
        }
    }

    private fun postToggleExpanded() {
        mainHandler.post {
            if (!AppSettings.isIslandEnabled(this)) return@post

            if (visualView == null || touchView == null) {
                showIslandInternal()
            }

            setExpandedAnimated(!isExpanded)
        }
    }

    private fun showIslandInternal() {
        if (visualView != null && touchView != null) {
            updateWindowsToCurrentSize()
            return
        }

        hideIslandInternal()
        isExpanded = false

        val widthPx = dp(AppSettings.getIslandWidthDp(this))
        val heightPx = dp(AppSettings.getIslandHeightDp(this))
        val radiusPx = compactCornerRadiusPx()

        visualBackground = createIslandBackground(radiusPx)

        val visual = View(this).apply {
            background = visualBackground
            elevation = dp(30).toFloat()
            alpha = 1f
            clipToOutline = true
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        val touch = FrameLayout(this).apply {
            background = null
            foreground = null
            alpha = 0f
            setWillNotDraw(true)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isClickable = true
            setOnClickListener {
                postToggleExpanded()
            }
        }

        val vParams = createVisualParams(widthPx, heightPx)
        val tParams = createTouchParams(widthPx, heightPx)

        visualView = visual
        touchView = touch
        visualParams = vParams
        touchParams = tParams

        try {
            windowManager?.addView(visual, vParams)
            windowManager?.addView(touch, tParams)
        } catch (e: Exception) {
            hideIslandInternal()

            Toast.makeText(
                this,
                "Accessibility overlay failed: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun updateWindowsToCurrentSize() {
        val widthPx = dp(currentTargetWidthDp())
        val heightPx = dp(currentTargetHeightDp())
        val radiusPx = if (isExpanded) {
            expandedCornerRadiusPx()
        } else {
            compactCornerRadiusPx()
        }

        updateWindowsToSize(widthPx, heightPx, radiusPx)
    }

    private fun setExpandedAnimated(expanded: Boolean) {
        if (isExpanded == expanded && morphAnimator?.isRunning != true) return

        val vParams = visualParams ?: return
        val tParams = touchParams ?: return
        val visual = visualView ?: return
        val touch = touchView ?: return

        morphAnimator?.cancel()

        val wasExpanded = isExpanded
        isExpanded = expanded

        val startWidth = vParams.width
        val startHeight = vParams.height
        val startRadius = visualBackground?.cornerRadius ?: if (wasExpanded) {
            expandedCornerRadiusPx().toFloat()
        } else {
            compactCornerRadiusPx().toFloat()
        }

        val targetWidth = dp(currentTargetWidthDp())
        val targetHeight = dp(currentTargetHeightDp())
        val targetRadius = if (expanded) {
            expandedCornerRadiusPx().toFloat()
        } else {
            compactCornerRadiusPx().toFloat()
        }

        morphAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (expanded) 360L else 280L
            interpolator = morphInterpolator

            addUpdateListener { animator ->
                val t = animator.animatedValue as Float

                val w = lerp(startWidth, targetWidth, t)
                val h = lerp(startHeight, targetHeight, t)
                val r = lerp(startRadius, targetRadius, t).toFloat()

                vParams.width = w
                vParams.height = h
                vParams.x = dp(AppSettings.getIslandXDp(this@HyperAccessibilityService))
                vParams.y = dp(AppSettings.getIslandYDp(this@HyperAccessibilityService))

                tParams.width = w
                tParams.height = h
                tParams.x = vParams.x
                tParams.y = vParams.y

                visualBackground?.cornerRadius = r

                try {
                    windowManager?.updateViewLayout(visual, vParams)
                    windowManager?.updateViewLayout(touch, tParams)
                } catch (_: Exception) {
                    cancel()
                }
            }

            addListener(object : android.animation.Animator.AnimatorListener {
                private var cancelled = false

                override fun onAnimationStart(animation: android.animation.Animator) {
                    cancelled = false
                }

                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                }

                override fun onAnimationRepeat(animation: android.animation.Animator) = Unit

                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (cancelled) return

                    updateWindowsToSize(
                        widthPx = targetWidth,
                        heightPx = targetHeight,
                        cornerRadiusPx = targetRadius.roundToInt()
                    )
                }
            })

            start()
        }
    }

    private fun updateWindowsToSize(
        widthPx: Int,
        heightPx: Int,
        cornerRadiusPx: Int
    ) {
        val visual = visualView ?: return
        val touch = touchView ?: return
        val vParams = visualParams ?: return
        val tParams = touchParams ?: return

        vParams.width = widthPx
        vParams.height = heightPx
        vParams.x = dp(AppSettings.getIslandXDp(this))
        vParams.y = dp(AppSettings.getIslandYDp(this))

        tParams.width = widthPx
        tParams.height = heightPx
        tParams.x = vParams.x
        tParams.y = vParams.y

        visualBackground?.cornerRadius = cornerRadiusPx.toFloat()

        try {
            windowManager?.updateViewLayout(visual, vParams)
            windowManager?.updateViewLayout(touch, tParams)
        } catch (_: Exception) {
            // Ignore.
        }
    }

    private fun hideIslandInternal() {
        morphAnimator?.cancel()
        morphAnimator = null

        val touch = touchView
        val visual = visualView

        if (touch != null) {
            try {
                windowManager?.removeViewImmediate(touch)
            } catch (_: Exception) {
                try {
                    windowManager?.removeView(touch)
                } catch (_: Exception) {
                    // Already removed.
                }
            }
        }

        if (visual != null) {
            try {
                windowManager?.removeViewImmediate(visual)
            } catch (_: Exception) {
                try {
                    windowManager?.removeView(visual)
                } catch (_: Exception) {
                    // Already removed.
                }
            }
        }

        visualView = null
        visualParams = null
        visualBackground = null
        touchView = null
        touchParams = null
        isExpanded = false
    }

    private fun createVisualParams(widthPx: Int, heightPx: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            widthPx,
            heightPx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = dp(AppSettings.getIslandXDp(this@HyperAccessibilityService))
            y = dp(AppSettings.getIslandYDp(this@HyperAccessibilityService))
            dimAmount = 0f

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }

            title = "Hyper Island Pro Visual Island"
        }
    }

    private fun createTouchParams(widthPx: Int, heightPx: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            widthPx,
            heightPx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = dp(AppSettings.getIslandXDp(this@HyperAccessibilityService))
            y = dp(AppSettings.getIslandYDp(this@HyperAccessibilityService))
            dimAmount = 0f

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }

            title = "Hyper Island Pro Touch Target"
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

    private fun expandedCornerRadiusPx(): Int {
        val expandedHeight = AppSettings.getIslandExpandedHeightDp(this)
        val radiusDp = (expandedHeight / 3).coerceIn(34, 46)
        return dp(radiusDp)
    }

    private fun lerp(start: Int, end: Int, progress: Float): Int {
        return (start + ((end - start) * progress)).roundToInt()
    }

    private fun lerp(start: Float, end: Float, progress: Float): Float {
        return start + ((end - start) * progress)
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
