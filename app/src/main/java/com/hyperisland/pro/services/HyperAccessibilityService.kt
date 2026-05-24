package com.hyperisland.pro.services

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.hyperisland.pro.core.AppSettings
import kotlin.math.roundToInt

class HyperAccessibilityService : AccessibilityService() {

    companion object {
        const val ACTION_ACCESSIBILITY_SHOW =
            "com.hyperisland.pro.action.ACCESSIBILITY_SHOW_ISLAND"
        const val ACTION_ACCESSIBILITY_HIDE =
            "com.hyperisland.pro.action.ACCESSIBILITY_HIDE_ISLAND"
        const val ACTION_ACCESSIBILITY_REFRESH =
            "com.hyperisland.pro.action.ACCESSIBILITY_REFRESH_ISLAND"
        const val ACTION_ACCESSIBILITY_EXPAND =
            "com.hyperisland.pro.action.ACCESSIBILITY_EXPAND_ISLAND"
        const val ACTION_ACCESSIBILITY_COLLAPSE =
            "com.hyperisland.pro.action.ACCESSIBILITY_COLLAPSE_ISLAND"
        const val ACTION_ACCESSIBILITY_TOGGLE_EXPAND =
            "com.hyperisland.pro.action.ACCESSIBILITY_TOGGLE_EXPAND_ISLAND"

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

        fun refreshIslandFromApp(context: Context): Boolean {
            val service = instance

            context.sendBroadcast(
                Intent(ACTION_ACCESSIBILITY_REFRESH).setPackage(context.packageName)
            )

            service?.postUpdateIsland()
            return service != null
        }

        fun expandIslandFromApp(context: Context): Boolean {
            val service = instance

            context.sendBroadcast(
                Intent(ACTION_ACCESSIBILITY_EXPAND).setPackage(context.packageName)
            )

            service?.postExpandIsland()
            return service != null
        }

        fun collapseIslandFromApp(context: Context): Boolean {
            val service = instance

            context.sendBroadcast(
                Intent(ACTION_ACCESSIBILITY_COLLAPSE).setPackage(context.packageName)
            )

            service?.postCollapseIsland()
            return service != null
        }

        fun toggleExpandFromApp(context: Context): Boolean {
            val service = instance

            context.sendBroadcast(
                Intent(ACTION_ACCESSIBILITY_TOGGLE_EXPAND).setPackage(context.packageName)
            )

            service?.postToggleExpanded()
            return service != null
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val islandInterpolator = PathInterpolator(0.18f, 0.0f, 0.0f, 1.0f)

    private var windowManager: WindowManager? = null
    private var islandView: FrameLayout? = null
    private var expandedContent: LinearLayout? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var islandBackground: GradientDrawable? = null
    private var sizeAnimator: ValueAnimator? = null
    private var receiverRegistered = false
    private var isExpanded = false

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_ACCESSIBILITY_SHOW -> postShowIsland()
                ACTION_ACCESSIBILITY_HIDE -> postHideIsland()
                ACTION_ACCESSIBILITY_REFRESH -> postUpdateIsland()
                ACTION_ACCESSIBILITY_EXPAND -> postExpandIsland()
                ACTION_ACCESSIBILITY_COLLAPSE -> postCollapseIsland()
                ACTION_ACCESSIBILITY_TOGGLE_EXPAND -> postToggleExpanded()
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
        // Phase 2: overlay + expand/collapse engine only.
    }

    override fun onInterrupt() = Unit

    private fun registerCommandReceiver() {
        if (receiverRegistered) return

        val filter = IntentFilter().apply {
            addAction(ACTION_ACCESSIBILITY_SHOW)
            addAction(ACTION_ACCESSIBILITY_HIDE)
            addAction(ACTION_ACCESSIBILITY_REFRESH)
            addAction(ACTION_ACCESSIBILITY_EXPAND)
            addAction(ACTION_ACCESSIBILITY_COLLAPSE)
            addAction(ACTION_ACCESSIBILITY_TOGGLE_EXPAND)
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

    private fun postExpandIsland() {
        mainHandler.post {
            if (!AppSettings.isIslandEnabled(this)) return@post
            if (islandView == null) showIslandInternal()
            animateIsland(expand = true)
        }
    }

    private fun postCollapseIsland() {
        mainHandler.post {
            if (!AppSettings.isIslandEnabled(this)) return@post
            animateIsland(expand = false)
        }
    }

    private fun postToggleExpanded() {
        mainHandler.post {
            if (!AppSettings.isIslandEnabled(this)) return@post
            if (islandView == null) showIslandInternal()
            animateIsland(expand = !isExpanded)
        }
    }

    private fun showIslandInternal() {
        if (islandView != null) {
            updateIslandLayoutInternal()
            return
        }

        isExpanded = false
        islandBackground = createIslandBackground(AppSettings.getIslandHeightDp(this))

        val root = FrameLayout(this).apply {
            background = islandBackground
            elevation = dp(30).toFloat()
            alpha = 1f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO

            setOnClickListener {
                animatePress(it)
                postToggleExpanded()
            }
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            alpha = 0f
            visibility = View.GONE
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }

        val title = TextView(this).apply {
            text = "HYPER ISLAND PRO"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            gravity = Gravity.CENTER
        }

        val hint = TextView(this).apply {
            text = "Phase 2 expand/collapse engine"
            setTextColor(Color.rgb(180, 180, 190))
            textSize = 12f
            includeFontPadding = false
            gravity = Gravity.CENTER
        }

        content.addView(
            title,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        content.addView(
            hint,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
        )

        root.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val params = createLayoutParams()

        islandView = root
        expandedContent = content
        layoutParams = params

        try {
            windowManager?.addView(root, params)
        } catch (e: Exception) {
            islandView = null
            expandedContent = null
            layoutParams = null
            islandBackground = null

            Toast.makeText(
                this,
                "Accessibility overlay failed: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun updateIslandLayoutInternal() {
        val root = islandView ?: return
        val params = layoutParams ?: return

        params.width = dp(currentTargetWidthDp())
        params.height = dp(currentTargetHeightDp())
        params.x = dp(AppSettings.getIslandXDp(this))
        params.y = dp(AppSettings.getIslandYDp(this))

        islandBackground?.cornerRadius = targetCornerRadiusPx(isExpanded).toFloat()
        expandedContent?.alpha = if (isExpanded) 1f else 0f
        expandedContent?.visibility = if (isExpanded) View.VISIBLE else View.GONE

        try {
            windowManager?.updateViewLayout(root, params)
        } catch (_: Exception) {
            // Window may already be removed by system.
        }
    }

    private fun animateIsland(expand: Boolean) {
        if (isExpanded == expand && sizeAnimator?.isRunning != true) return

        isExpanded = expand
        val targetWidth = dp(currentTargetWidthDp())
        val targetHeight = dp(currentTargetHeightDp())
        animateToSize(targetWidth, targetHeight, expand)
    }

    private fun animateToSize(targetWidth: Int, targetHeight: Int, expanding: Boolean) {
        val view = islandView ?: return
        val params = layoutParams ?: return

        sizeAnimator?.cancel()

        val startWidth = params.width
        val startHeight = params.height
        val startRadius = islandBackground?.cornerRadius ?: targetCornerRadiusPx(!expanding).toFloat()
        val endRadius = targetCornerRadiusPx(expanding).toFloat()
        val content = expandedContent

        if (expanding) {
            content?.visibility = View.VISIBLE
            content?.alpha = 0f
        }

        sizeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (expanding) 420L else 320L
            interpolator = islandInterpolator

            addUpdateListener { animator ->
                val t = animator.animatedValue as Float

                params.width = lerp(startWidth, targetWidth, t)
                params.height = lerp(startHeight, targetHeight, t)
                params.x = dp(AppSettings.getIslandXDp(this@HyperAccessibilityService))
                params.y = dp(AppSettings.getIslandYDp(this@HyperAccessibilityService))

                islandBackground?.cornerRadius = lerp(startRadius, endRadius, t).toFloat()

                content?.alpha = if (expanding) {
                    ((t - 0.35f) / 0.65f).coerceIn(0f, 1f)
                } else {
                    (1f - (t / 0.55f)).coerceIn(0f, 1f)
                }

                try {
                    windowManager?.updateViewLayout(view, params)
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

                    params.width = targetWidth
                    params.height = targetHeight
                    islandBackground?.cornerRadius = endRadius

                    if (!expanding) {
                        content?.alpha = 0f
                        content?.visibility = View.GONE
                    } else {
                        content?.alpha = 1f
                        content?.visibility = View.VISIBLE
                    }

                    try {
                        windowManager?.updateViewLayout(view, params)
                    } catch (_: Exception) {
                        // Ignore.
                    }
                }
            })

            start()
        }
    }

    private fun hideIslandInternal() {
        sizeAnimator?.cancel()
        sizeAnimator = null

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
        expandedContent = null
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

    private fun createIslandBackground(heightDp: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.BLACK)
            cornerRadius = dp(heightDp / 2).toFloat()
        }
    }

    private fun animatePress(view: View) {
        view.animate()
            .scaleX(0.965f)
            .scaleY(0.965f)
            .setDuration(70)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(120)
                    .start()
            }
            .start()
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

    private fun targetCornerRadiusPx(expanded: Boolean): Int {
        return if (expanded) {
            dp(32)
        } else {
            dp(AppSettings.getIslandHeightDp(this) / 2)
        }
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
        unregisterCommandReceiver()

        if (instance === this) {
            instance = null
        }

        super.onDestroy()
    }
}
