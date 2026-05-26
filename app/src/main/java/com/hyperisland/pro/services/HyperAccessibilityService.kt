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

        fun isConnected(): Boolean = instance != null

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
     * Phase 2.3 Gemini patch:
     * Visual window = MATCH_PARENT transparent, NOT_TOUCHABLE.
     * Touch window = exact-size transparent hitbox.
     *
     * Patch focus:
     * - Touch window has explicit Color.TRANSPARENT background
     * - Touch window alpha remains 1f
     * - No hardware acceleration flag on touch window
     */
    private val morphInterpolator = PathInterpolator(0.20f, 0.0f, 0.0f, 1.0f)

    private var windowManager: WindowManager? = null

    private var visualRoot: FrameLayout? = null
    private var visualParams: WindowManager.LayoutParams? = null

    private var islandView: View? = null
    private var islandLayoutParams: FrameLayout.LayoutParams? = null
    private var islandBackground: GradientDrawable? = null

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
        // Phase 2.3: visual static window + separate touch hitbox.
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
                updateAllToCurrentState()
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
            updateAllToCurrentState()
            return
        }

        hideIslandInternal()
        isExpanded = false

        islandBackground = createIslandBackground(compactCornerRadiusPx())

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
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

        val compactWidth = dp(AppSettings.getIslandWidthDp(this))
        val compactHeight = dp(AppSettings.getIslandHeightDp(this))

        val childParams = FrameLayout.LayoutParams(
            compactWidth,
            compactHeight
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = 0
        }

        root.addView(island, childParams)

        val touch = FrameLayout(this).apply {
            /*
             * Gemini tint fix:
             * Explicit transparent background, alpha 1f.
             * Not background=null and not alpha=0f.
             */
            setBackgroundColor(Color.TRANSPARENT)
            foreground = null
            alpha = 1f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isClickable = true
            setOnClickListener {
                postToggleExpanded()
            }
        }

        val vParams = createVisualParams()
        val tParams = createTouchParams(compactWidth, compactHeight)

        visualRoot = root
        visualParams = vParams
        islandView = island
        islandLayoutParams = childParams
        touchView = touch
        touchParams = tParams

        try {
            windowManager?.addView(root, vParams)
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

    private fun updateAllToCurrentState() {
        val root = visualRoot ?: return
        val vParams = visualParams ?: return
        val child = islandLayoutParams ?: return

        val targetWidth = dp(currentTargetWidthDp())
        val targetHeight = dp(currentTargetHeightDp())
        val targetRadius = if (isExpanded) expandedCornerRadiusPx() else compactCornerRadiusPx()

        vParams.width = WindowManager.LayoutParams.MATCH_PARENT
        vParams.height = dp(AppSettings.getIslandExpandedHeightDp(this))
        vParams.x = 0
        vParams.y = dp(AppSettings.getIslandYDp(this))

        child.width = targetWidth
        child.height = targetHeight
        child.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        child.topMargin = 0

        islandBackground?.cornerRadius = targetRadius.toFloat()

        try {
            windowManager?.updateViewLayout(root, vParams)
            islandView?.layoutParams = child
            updateTouchWindow(targetWidth, targetHeight)
        } catch (_: Exception) {
            // Ignore.
        }
    }

    private fun setExpandedAnimated(expanded: Boolean) {
        if (isExpanded == expanded && morphAnimator?.isRunning != true) return

        val root = visualRoot ?: return
        val child = islandLayoutParams ?: return

        morphAnimator?.cancel()

        val wasExpanded = isExpanded
        isExpanded = expanded

        val startWidth = child.width
        val startHeight = child.height
        val startRadius = islandBackground?.cornerRadius ?: if (wasExpanded) {
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

        updateTouchWindow(targetWidth, targetHeight)
        updateVisualRootStatic(root)

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
                }
            })

            start()
        }
    }

    private fun updateVisualRootStatic(root: FrameLayout) {
        val params = visualParams ?: return

        params.width = WindowManager.LayoutParams.MATCH_PARENT
        params.height = dp(AppSettings.getIslandExpandedHeightDp(this))
        params.x = 0
        params.y = dp(AppSettings.getIslandYDp(this))

        try {
            windowManager?.updateViewLayout(root, params)
        } catch (_: Exception) {
            // Ignore.
        }
    }

    private fun updateTouchWindow(widthPx: Int, heightPx: Int) {
        val touch = touchView ?: return
        val params = touchParams ?: return

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
        visualParams = null
        islandView = null
        islandLayoutParams = null
        islandBackground = null
        touchView = null
        touchParams = null
        isExpanded = false
    }

    private fun createVisualParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
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
            x = 0
            y = dp(AppSettings.getIslandYDp(this@HyperAccessibilityService))
            alpha = 1f
            dimAmount = 0f

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }

            title = "Hyper Island Pro Visual FullWidth"
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
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = dp(AppSettings.getIslandXDp(this@HyperAccessibilityService))
            y = dp(AppSettings.getIslandYDp(this@HyperAccessibilityService))
            alpha = 1f
            dimAmount = 0f

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }

            title = "Hyper Island Pro Touch Hitbox"
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
