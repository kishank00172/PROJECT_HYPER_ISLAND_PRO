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
import kotlin.math.abs
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
     * Phase 2.4:
     * Single exact-size Accessibility Overlay window.
     *
     * Why:
     * - no large transparent window = no tinted rectangle
     * - no second touch window = less compositor desync
     * - touch area equals visible island size
     *
     * Optimization:
     * - throttle WindowManager resize updates
     * - only update if size changed enough
     * - shorter duration
     * - no elevation/shadow during morph
     */
    private val morphInterpolator = PathInterpolator(0.22f, 0.0f, 0.0f, 1.0f)

    private var windowManager: WindowManager? = null
    private var islandView: FrameLayout? = null
    private var islandParams: WindowManager.LayoutParams? = null
    private var islandBackground: GradientDrawable? = null
    private var morphAnimator: ValueAnimator? = null

    private var isExpanded = false

    private var lastAppliedWidth = -1
    private var lastAppliedHeight = -1
    private var lastAppliedRadius = -1f
    private var lastFrameTimeMs = 0L

    /*
     * Lower values = smoother but more compositor load.
     * Higher values = less jitter but less smooth.
     */
    private val minPxDeltaForUpdate = 3
    private val minFrameGapMs = 16L // ~60fps cap, but skips duplicate tiny updates

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
        // Phase 2.4: single exact-size low-jitter morph engine.
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
                updateIslandToCurrentSize(force = true)
            }
        }
    }

    private fun postExpandIsland() {
        mainHandler.post {
            if (!AppSettings.isIslandEnabled(this)) return@post

            if (islandView == null) {
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

            if (islandView == null) {
                showIslandInternal()
            }

            setExpandedAnimated(!isExpanded)
        }
    }

    private fun showIslandInternal() {
        if (islandView != null) {
            updateIslandToCurrentSize(force = true)
            return
        }

        hideIslandInternal()

        isExpanded = false

        val widthPx = dp(AppSettings.getIslandWidthDp(this))
        val heightPx = dp(AppSettings.getIslandHeightDp(this))
        val radiusPx = compactCornerRadiusPx()

        islandBackground = createIslandBackground(radiusPx)

        val view = FrameLayout(this).apply {
            background = islandBackground
            elevation = dp(24).toFloat()
            alpha = 1f
            clipToOutline = true
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isClickable = true

            setOnClickListener {
                postToggleExpanded()
            }
        }

        val params = createIslandParams(widthPx, heightPx)

        islandView = view
        islandParams = params

        lastAppliedWidth = widthPx
        lastAppliedHeight = heightPx
        lastAppliedRadius = radiusPx.toFloat()
        lastFrameTimeMs = 0L

        try {
            windowManager?.addView(view, params)
        } catch (e: Exception) {
            islandView = null
            islandParams = null
            islandBackground = null

            Toast.makeText(
                this,
                "Accessibility overlay failed: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun updateIslandToCurrentSize(force: Boolean) {
        val widthPx = dp(currentTargetWidthDp())
        val heightPx = dp(currentTargetHeightDp())
        val radiusPx = if (isExpanded) {
            expandedCornerRadiusPx()
        } else {
            compactCornerRadiusPx()
        }

        applyWindowSize(
            widthPx = widthPx,
            heightPx = heightPx,
            radiusPx = radiusPx.toFloat(),
            force = force
        )
    }

    private fun setExpandedAnimated(expanded: Boolean) {
        if (isExpanded == expanded && morphAnimator?.isRunning != true) return

        val params = islandParams ?: return
        val view = islandView ?: return

        morphAnimator?.cancel()

        val wasExpanded = isExpanded
        isExpanded = expanded

        val startWidth = params.width
        val startHeight = params.height
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

        /*
         * Drop elevation during morph to reduce compositor cost.
         */
        view.elevation = 0f
        lastFrameTimeMs = 0L

        morphAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (expanded) 250L else 210L
            interpolator = morphInterpolator

            addUpdateListener { animator ->
                val t = animator.animatedValue as Float

                val width = lerp(startWidth, targetWidth, t)
                val height = lerp(startHeight, targetHeight, t)

                /*
                 * Radius update is cheap enough but still throttled by applyWindowSize.
                 */
                val radius = lerp(startRadius, targetRadius, t)

                applyWindowSize(
                    widthPx = width,
                    heightPx = height,
                    radiusPx = radius,
                    force = false
                )
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

                    applyWindowSize(
                        widthPx = targetWidth,
                        heightPx = targetHeight,
                        radiusPx = targetRadius,
                        force = true
                    )

                    islandView?.elevation = dp(24).toFloat()
                }
            })

            start()
        }
    }

    private fun applyWindowSize(
        widthPx: Int,
        heightPx: Int,
        radiusPx: Float,
        force: Boolean
    ) {
        val view = islandView ?: return
        val params = islandParams ?: return

        val now = System.currentTimeMillis()

        val widthChanged = abs(widthPx - lastAppliedWidth) >= minPxDeltaForUpdate
        val heightChanged = abs(heightPx - lastAppliedHeight) >= minPxDeltaForUpdate
        val radiusChanged = abs(radiusPx - lastAppliedRadius) >= 1.5f
        val enoughTime = (now - lastFrameTimeMs) >= minFrameGapMs

        if (!force && (!enoughTime || (!widthChanged && !heightChanged && !radiusChanged))) {
            return
        }

        params.width = widthPx
        params.height = heightPx
        params.x = dp(AppSettings.getIslandXDp(this))
        params.y = dp(AppSettings.getIslandYDp(this))

        islandBackground?.cornerRadius = radiusPx

        try {
            windowManager?.updateViewLayout(view, params)

            lastAppliedWidth = widthPx
            lastAppliedHeight = heightPx
            lastAppliedRadius = radiusPx
            lastFrameTimeMs = now
        } catch (_: Exception) {
            // Ignore update race.
        }
    }

    private fun hideIslandInternal() {
        morphAnimator?.cancel()
        morphAnimator = null

        val view = islandView

        if (view != null) {
            try {
                windowManager?.removeViewImmediate(view)
            } catch (_: Exception) {
                try {
                    windowManager?.removeView(view)
                } catch (_: Exception) {
                    // Already removed.
                }
            }
        }

        islandView = null
        islandParams = null
        islandBackground = null
        isExpanded = false
        lastAppliedWidth = -1
        lastAppliedHeight = -1
        lastAppliedRadius = -1f
        lastFrameTimeMs = 0L
    }

    private fun createIslandParams(
        widthPx: Int,
        heightPx: Int
    ): WindowManager.LayoutParams {
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
            alpha = 1f
            dimAmount = 0f

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }

            title = "Hyper Island Pro Exact Island"
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
