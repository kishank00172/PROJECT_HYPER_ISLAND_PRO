package com.hyperisland.pro.services

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.app.PendingIntent
import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
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

        fun showNotificationFromApp(
            context: Context,
            packageName: String,
            appName: String,
            title: String,
            message: String,
            postTime: Long,
            contentIntent: PendingIntent?
        ): Boolean {
            val service = instance ?: return false

            service.postNotificationEvent(
                packageName = packageName,
                appName = appName,
                title = title,
                message = message,
                postTime = postTime,
                contentIntent = contentIntent
            )

            return true
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val morphInterpolator = PathInterpolator(0.20f, 0.0f, 0.0f, 1.0f)

    private var windowManager: WindowManager? = null

    private var visualRoot: FrameLayout? = null
    private var visualParams: WindowManager.LayoutParams? = null

    private var islandView: FrameLayout? = null
    private var islandLayoutParams: FrameLayout.LayoutParams? = null
    private var islandBackground: GradientDrawable? = null

    private var contentContainer: LinearLayout? = null
    private var appNameText: TextView? = null
    private var titleText: TextView? = null
    private var messageText: TextView? = null

    private var touchView: FrameLayout? = null
    private var touchParams: WindowManager.LayoutParams? = null

    private var outsideWatcherView: FrameLayout? = null
    private var outsideWatcherParams: WindowManager.LayoutParams? = null

    private var morphAnimator: ValueAnimator? = null
    private var isExpanded = false

    private var notificationMode = false
    private var currentPendingIntent: PendingIntent? = null
    private var autoCollapseRunnable: Runnable? = null

    private val outlineRect = Rect()
    private var outlineRadius = 0f

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
        // Phase 3: notification island.
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
            if (visualRoot == null || touchView == null) showIslandInternal()
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
            if (visualRoot == null || touchView == null) showIslandInternal()

            if (notificationMode && isExpanded) {
                openCurrentNotification()
            } else {
                setExpandedAnimated(!isExpanded)
            }
        }
    }

    private fun postNotificationEvent(
        packageName: String,
        appName: String,
        title: String,
        message: String,
        postTime: Long,
        contentIntent: PendingIntent?
    ) {
        mainHandler.post {
            if (!AppSettings.isIslandEnabled(this)) return@post

            if (visualRoot == null || touchView == null) {
                showIslandInternal()
            }

            notificationMode = true
            currentPendingIntent = contentIntent

            appNameText?.text = appName
            titleText?.text = title.ifEmpty { appName }
            messageText?.text = message.ifEmpty { "New notification" }

            contentContainer?.visibility = View.VISIBLE
            contentContainer?.alpha = 0f

            setExpandedAnimated(true)
            scheduleAutoCollapse()
        }
    }

    private fun scheduleAutoCollapse() {
        autoCollapseRunnable?.let { mainHandler.removeCallbacks(it) }

        val runnable = Runnable {
            if (notificationMode) {
                notificationMode = false
                currentPendingIntent = null
                setExpandedAnimated(false)
            }
        }

        autoCollapseRunnable = runnable
        mainHandler.postDelayed(runnable, 5200L)
    }

    private fun openCurrentNotification() {
        val pending = currentPendingIntent

        notificationMode = false
        currentPendingIntent = null
        autoCollapseRunnable?.let { mainHandler.removeCallbacks(it) }
        autoCollapseRunnable = null

        try {
            pending?.send()
        } catch (_: Exception) {
            // Ignore if pending intent is already invalid.
        }

        setExpandedAnimated(false)
    }

    private fun showIslandInternal() {
        if (visualRoot != null && touchView != null) {
            updateAllToCurrentState()
            return
        }

        hideIslandInternal()
        isExpanded = false
        notificationMode = false
        currentPendingIntent = null

        val compactWidth = dp(AppSettings.getIslandWidthDp(this))
        val compactHeight = dp(AppSettings.getIslandHeightDp(this))
        val compactRadius = compactCornerRadiusPx().toFloat()

        islandBackground = createIslandBackground(compactRadius)

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            foreground = null
            alpha = 1f
            clipChildren = false
            clipToPadding = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(outlineRect, outlineRadius)
                }
            }
        }

        val island = FrameLayout(this).apply {
            background = islandBackground
            elevation = dp(30).toFloat()
            alpha = 1f
            clipToOutline = true
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            alpha = 0f
            setPadding(dp(22), dp(14), dp(22), dp(14))
        }

        val app = TextView(this).apply {
            text = ""
            setTextColor(Color.rgb(170, 170, 178))
            textSize = 12f
            includeFontPadding = false
            maxLines = 1
        }

        val title = TextView(this).apply {
            text = ""
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            maxLines = 1
        }

        val msg = TextView(this).apply {
            text = ""
            setTextColor(Color.rgb(210, 210, 216))
            textSize = 13f
            includeFontPadding = false
            maxLines = 2
        }

        content.addView(app)
        content.addView(title)
        content.addView(msg)

        island.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val childParams = FrameLayout.LayoutParams(
            compactWidth,
            compactHeight
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = 0
        }

        root.addView(island, childParams)

        val touch = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            foreground = null
            alpha = 1f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isClickable = true
            setOnClickListener {
                postToggleExpanded()
            }
        }

        visualRoot = root
        visualParams = createVisualParams()
        islandView = island
        islandLayoutParams = childParams
        islandBackground = islandBackground
        contentContainer = content
        appNameText = app
        titleText = title
        messageText = msg
        touchView = touch
        touchParams = createTouchParams(compactWidth, compactHeight)

        updateOutlineForIsland(compactWidth, compactHeight, compactRadius)

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

    private fun updateAllToCurrentState() {
        val root = visualRoot ?: return
        val vParams = visualParams ?: return
        val child = islandLayoutParams ?: return

        val targetWidth = dp(currentTargetWidthDp())
        val targetHeight = dp(currentTargetHeightDp())
        val targetRadius = if (isExpanded) expandedCornerRadiusPx().toFloat() else compactCornerRadiusPx().toFloat()

        vParams.width = WindowManager.LayoutParams.MATCH_PARENT
        vParams.height = dp(AppSettings.getIslandExpandedHeightDp(this))
        vParams.x = 0
        vParams.y = dp(AppSettings.getIslandYDp(this))

        child.width = targetWidth
        child.height = targetHeight
        child.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        child.topMargin = 0

        islandBackground?.cornerRadius = targetRadius
        updateOutlineForIsland(targetWidth, targetHeight, targetRadius)

        try {
            windowManager?.updateViewLayout(root, vParams)
            islandView?.layoutParams = child
            visualRoot?.invalidateOutline()
            updateTouchWindow(targetWidth, targetHeight)
            updateOutsideWatcherForState()
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
        val targetRadius = if (expanded) expandedCornerRadiusPx().toFloat() else compactCornerRadiusPx().toFloat()

        updateTouchWindow(targetWidth, targetHeight)
        updateOutsideWatcherForState()
        updateVisualRootStatic(root)

        if (expanded && notificationMode) {
            contentContainer?.visibility = View.VISIBLE
        }

        morphAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (expanded) 360L else 280L
            interpolator = morphInterpolator

            addUpdateListener { animator ->
                val t = animator.animatedValue as Float

                val width = lerp(startWidth, targetWidth, t)
                val height = lerp(startHeight, targetHeight, t)
                val radius = lerp(startRadius, targetRadius, t)

                child.width = width
                child.height = height
                child.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                child.topMargin = 0

                islandBackground?.cornerRadius = radius
                updateOutlineForIsland(width, height, radius)

                if (expanded && notificationMode) {
                    contentContainer?.alpha = ((t - 0.35f) / 0.65f).coerceIn(0f, 1f)
                } else if (!expanded) {
                    contentContainer?.alpha = (1f - (t / 0.5f)).coerceIn(0f, 1f)
                }

                islandView?.layoutParams = child
                visualRoot?.invalidateOutline()
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

                    updateOutlineForIsland(targetWidth, targetHeight, targetRadius)

                    if (!expanded) {
                        contentContainer?.alpha = 0f
                        contentContainer?.visibility = View.GONE
                        notificationMode = false
                        currentPendingIntent = null
                    } else if (notificationMode) {
                        contentContainer?.alpha = 1f
                        contentContainer?.visibility = View.VISIBLE
                    }

                    islandView?.layoutParams = child
                    visualRoot?.invalidateOutline()
                    updateOutsideWatcherForState()
                }
            })

            start()
        }
    }

    private fun updateOutsideWatcherForState() {
        if (isExpanded) ensureOutsideWatcher() else removeOutsideWatcher()
    }

    private fun ensureOutsideWatcher() {
        if (outsideWatcherView != null) return

        val watcher = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            alpha = 1f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO

            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_OUTSIDE) {
                    postCollapseIsland()
                }
                false
            }
        }

        val params = createOutsideWatcherParams()

        outsideWatcherView = watcher
        outsideWatcherParams = params

        try {
            windowManager?.addView(watcher, params)
        } catch (_: Exception) {
            outsideWatcherView = null
            outsideWatcherParams = null
        }
    }

    private fun removeOutsideWatcher() {
        val watcher = outsideWatcherView ?: return

        try {
            windowManager?.removeViewImmediate(watcher)
        } catch (_: Exception) {
            try {
                windowManager?.removeView(watcher)
            } catch (_: Exception) {
                // Already removed.
            }
        } finally {
            outsideWatcherView = null
            outsideWatcherParams = null
        }
    }

    private fun updateOutlineForIsland(widthPx: Int, heightPx: Int, radiusPx: Float) {
        val rootWidth = resources.displayMetrics.widthPixels
        val left = ((rootWidth - widthPx) / 2) + dp(AppSettings.getIslandXDp(this))
        val top = 0
        val right = left + widthPx
        val bottom = heightPx

        outlineRect.set(left, top, right, bottom)
        outlineRadius = radiusPx
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
        autoCollapseRunnable?.let { mainHandler.removeCallbacks(it) }
        autoCollapseRunnable = null

        removeOutsideWatcher()

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
        contentContainer = null
        appNameText = null
        titleText = null
        messageText = null
        touchView = null
        touchParams = null
        outsideWatcherView = null
        outsideWatcherParams = null
        isExpanded = false
        notificationMode = false
        currentPendingIntent = null
        outlineRect.setEmpty()
        outlineRadius = 0f
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

            title = "Hyper Island Pro Visual OutlineClip"
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

    private fun createOutsideWatcherParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            alpha = 1f
            dimAmount = 0f
            title = "Hyper Island Pro Outside Watcher"
        }
    }

    private fun createIslandBackground(cornerRadiusPx: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.BLACK)
            cornerRadius = cornerRadiusPx
        }
    }

    private fun currentTargetWidthDp(): Int {
        return if (isExpanded) AppSettings.getIslandExpandedWidthDp(this)
        else AppSettings.getIslandWidthDp(this)
    }

    private fun currentTargetHeightDp(): Int {
        return if (isExpanded) AppSettings.getIslandExpandedHeightDp(this)
        else AppSettings.getIslandHeightDp(this)
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
