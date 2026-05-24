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
     * Center morph curve.
     * Visual layer is non-touchable.
     * Touch layer is exact-size and fully invisible.
     */
    private val morphInterpolator = PathInterpolator(0.20f, 0.0f, 0.0f, 1.0f)

    private var windowManager: WindowManager? = null

    private var visualRoot: FrameLayout? = null
    private var visualWindowParams: WindowManager.LayoutParams? = null

    private var islandView: View? = null
    private var islandParams: FrameLayout.LayoutParams? = null
    private var islandBackground: GradientDrawable? = null

    private var touchView: FrameLayout? = null
    private var touchWindowParams: WindowManager.LayoutParams? = null

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
        // Phase 2: stable center morph engine only.
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

            if (visualRoot == null || touchView == null) {
                showIslandInternal()
            } else {
                updateContainerAndIsland(immediate = true)
            }
        }
    }

    private fun postExpandIsland() {
        mainHandler.post {
            if (!AppSettings.isIslandEnabled(this)) return@post

            if (visualRoot == null || touchView == null) {
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

            if (visualRoot == null || touchView == null) {
                showIslandInternal()
            }

            setExpandedAnimated(!isExpanded)
        }
    }

    private fun showIslandInternal() {
        if (visualRoot != null && touchView != null) {
            updateContainerAndIsland(immediate = true)
            return
        }

        hideIslandInternal()
        isExpanded = false

        islandBackground = createIslandBackground(
            cornerRadiusPx = compactCornerRadiusPx()
        )

        val root = FrameLayout(this).apply {
            background = null
            foreground = null
            alpha = 1f
            setWillNotDraw(true)
            clipChildren = false
            clipToPadding = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        val island = View(this).apply {
            background = islandBackground
            elevation = dp(30).toFloat()
            alpha = 1f
            clipToOutline = true
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        val childParams = FrameLayout.LayoutParams(
            dp(AppSettings.getIslandWidthDp(this)),
            dp(AppSettings.getIslandHeightDp(this))
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = 0
        }

        root.addView(island, childParams)

        val touch = FrameLayout(this).apply {
            /*
             * Fully invisible touch layer.
             * It remains clickable but visually transparent.
             */
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

        visualRoot = root
        islandView = island
        islandParams = childParams
        touchView = touch

        val visualParams = createVisualWindowParams()
        val touchParams = createTouchWindowParams()
        visualWindowParams = visualParams
        touchWindowParams = touchParams

        try {
            windowManager?.addView(root, visualParams)
            windowManager?.addView(touch, touchParams)
        } catch (e: Exception) {
            hideIslandInternal()

            Toast.makeText(
                this,
                "Accessibility overlay failed: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun updateContainerAndIsland(immediate: Boolean) {
        val root = visualRoot ?: return
        val visualParams = visualWindowParams ?: return
        val child = islandParams ?: return

        visualParams.width = dp(AppSettings.getIslandExpandedWidthDp(this))
        visualParams.height = dp(AppSettings.getIslandExpandedHeightDp(this))
        visualParams.x = dp(AppSettings.getIslandXDp(this))
        visualParams.y = dp(AppSettings.getIslandYDp(this))

        child.width = dp(currentTargetWidthDp())
        child.height = dp(currentTargetHeightDp())
        child.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        child.topMargin = 0

        islandBackground?.cornerRadius = if (isExpanded) {
            expandedCornerRadiusPx().toFloat()
        } else {
            compactCornerRadiusPx().toFloat()
        }

        try {
            windowManager?.updateViewLayout(root, visualParams)
            islandView?.layoutParams = child
            updateTouchWindowToSizePx(child.width, child.height)
        } catch (e: Exception) {
            if (immediate) {
                Toast.makeText(
                    this,
                    "Island update failed: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun setExpandedAnimated(expanded: Boolean) {
        if (isExpanded == expanded && morphAnimator?.isRunning != true) return

        val root = visualRoot ?: return
        val child = islandParams ?: return

        morphAnimator?.cancel()

        val startExpanded = isExpanded
        isExpanded = expanded

        val startWidth = child.width
        val startHeight = child.height
        val startRadius = islandBackground?.cornerRadius ?: if (startExpanded) {
            expandedCornerRadiusPx().toFloat()
        } else {
            compactCornerRadiusPx().toFloat()
        }

        val targetWidth = if (expanded) {
            dp(AppSettings.getIslandExpandedWidthDp(this))
        } else {
            dp(AppSettings.getIslandWidthDp(this))
        }

        val targetHeight = if (expanded) {
            dp(AppSettings.getIslandExpandedHeightDp(this))
        } else {
            dp(AppSettings.getIslandHeightDp(this))
        }

        val targetRadius = if (expanded) {
            expandedCornerRadiusPx().toFloat()
        } else {
            compactCornerRadiusPx().toFloat()
        }

        updateVisualContainerOnly(root)

        morphAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (expanded) 360L else 280L
            interpolator = morphInterpolator

            addUpdateListener { animator ->
                val t = animator.animatedValue as Float

                child.width = lerp(startWidth, targetWidth, t)
                child.height = lerp(startHeight, targetHeight, t)
                child.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                child.topMargin = 0

                islandBackground?.cornerRadius = lerp(startRadius, targetRadius, t).toFloat()

                islandView?.layoutParams = child

                /*
                 * Touch target follows the actual visible island size.
                 * This prevents invisible expanded rectangles from blocking gestures.
                 */
                updateTouchWindowToSizePx(child.width, child.height)
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

                    child.width = targetWidth
                    child.height = targetHeight
                    child.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    child.topMargin = 0
                    islandBackground?.cornerRadius = targetRadius

                    islandView?.layoutParams = child
                    updateTouchWindowToSizePx(targetWidth, targetHeight)
                }
            })

            start()
        }
    }

    private fun updateVisualContainerOnly(root: FrameLayout) {
        val params = visualWindowParams ?: return

        params.width = dp(AppSettings.getIslandExpandedWidthDp(this))
        params.height = dp(AppSettings.getIslandExpandedHeightDp(this))
        params.x = dp(AppSettings.getIslandXDp(this))
        params.y = dp(AppSettings.getIslandYDp(this))

        try {
            windowManager?.updateViewLayout(root, params)
        } catch (_: Exception) {
            // Ignore.
        }
    }

    private fun updateTouchWindowToSizePx(widthPx: Int, heightPx: Int) {
        val touch = touchView ?: return
        val params = touchWindowParams ?: return

        params.width = widthPx
        params.height = heightPx
        params.x = dp(AppSettings.getIslandXDp(this))
        params.y = dp(AppSettings.getIslandYDp(this))

        try {
            windowManager?.updateViewLayout(touch, params)
        } catch (_: Exception) {
            // Ignore.
        }
    }

    private fun hideIslandInternal() {
        morphAnimator?.cancel()
        morphAnimator = null

        val touch = touchView
        val root = visualRoot

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

        if (root != null) {
            try {
                windowManager?.removeViewImmediate(root)
            } catch (_: Exception) {
                try {
                    windowManager?.removeView(root)
                } catch (_: Exception) {
                    // Already removed.
                }
            }
        }

        visualRoot = null
        visualWindowParams = null
        islandView = null
        islandParams = null
        islandBackground = null
        touchView = null
        touchWindowParams = null
        isExpanded = false
    }

    private fun createVisualWindowParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            dp(AppSettings.getIslandExpandedWidthDp(this)),
            dp(AppSettings.getIslandExpandedHeightDp(this)),
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

            title = "Hyper Island Pro Visual Root"
        }
    }

    private fun createTouchWindowParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            dp(currentTargetWidthDp()),
            dp(currentTargetHeightDp()),
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
