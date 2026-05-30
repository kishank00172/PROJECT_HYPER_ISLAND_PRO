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
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.hyperisland.pro.core.AppSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class HyperAccessibilityService : AccessibilityService() {

    private enum class ExpandReason {
        MANUAL_USER,
        AUTO_NOTIFICATION
    }

    companion object {
        @Volatile
        private var instance: HyperAccessibilityService? = null

        @Volatile
        var lastAccessibilityDebugMessage: String = "Accessibility fallback waiting"
            private set

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
                source = "NotificationListener",
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
    private var appIconView: ImageView? = null
    private var appNameTimeText: TextView? = null
    private var titleText: TextView? = null
    private var messageText: TextView? = null

    private var touchView: FrameLayout? = null
    private var touchParams: WindowManager.LayoutParams? = null

    private var outsideWatcherView: FrameLayout? = null
    private var outsideWatcherParams: WindowManager.LayoutParams? = null

    private var morphAnimator: ValueAnimator? = null
    private var isExpanded = false
    private var expandReason = ExpandReason.MANUAL_USER

    private var notificationMode = false
    private var currentPendingIntent: PendingIntent? = null
    private var currentPackageName: String? = null
    private var autoCollapseRunnable: Runnable? = null

    private var lastIslandFingerprint = ""
    private var lastIslandFingerprintTime = 0L
    private var lastPrimaryEventTime = 0L

    private var touchStartY = 0f
    private var touchStartX = 0f

    private val outlineRect = Rect()
    private var outlineRadius = 0f

    override fun onServiceConnected() {
        super.onServiceConnected()

        instance = this
        windowManager = getSystemService(WindowManager::class.java)
        lastAccessibilityDebugMessage = "Accessibility service connected"

        if (
            AppSettings.isIslandEnabled(this) &&
            AppSettings.getOverlayEngine(this) == AppSettings.ENGINE_ACCESSIBILITY
        ) {
            postShowIsland()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            handleAccessibilityNotificationEvent(event)
        }
    }

    override fun onInterrupt() = Unit

    private fun handleAccessibilityNotificationEvent(event: AccessibilityEvent) {
        if (!AppSettings.isIslandEnabled(this)) return

        val pkg = event.packageName?.toString()?.trim().orEmpty()
        if (pkg.isBlank() || pkg == packageName) return

        val eventText = event.text?.mapNotNull { it?.toString()?.trim() }?.filter { it.isNotBlank() } ?: emptyList()
        if (eventText.isEmpty()) return

        val appName = getAppName(pkg)
        val title: String
        val message: String

        if (eventText.size >= 2) {
            title = eventText.first()
            message = eventText.drop(1).joinToString(" • ")
        } else {
            title = appName
            message = eventText.first()
        }

        postNotificationEvent(
            source = "AccessibilityFallback",
            packageName = pkg,
            appName = appName,
            title = title,
            message = message,
            postTime = System.currentTimeMillis(),
            contentIntent = null
        )
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
            setExpandedAnimated(true, ExpandReason.MANUAL_USER)
        }
    }

    private fun postCollapseIsland() {
        mainHandler.post {
            if (!AppSettings.isIslandEnabled(this)) return@post
            setExpandedAnimated(false, expandReason)
        }
    }

    private fun postToggleExpanded() {
        mainHandler.post {
            if (!AppSettings.isIslandEnabled(this)) return@post
            if (visualRoot == null || touchView == null) showIslandInternal()

            if (notificationMode && isExpanded) {
                openCurrentNotification()
            } else {
                setExpandedAnimated(!isExpanded, ExpandReason.MANUAL_USER)
            }
        }
    }

    private fun postNotificationEvent(
        source: String,
        packageName: String,
        appName: String,
        title: String,
        message: String,
        postTime: Long,
        contentIntent: PendingIntent?
    ) {
        mainHandler.post {
            if (!AppSettings.isIslandEnabled(this)) return@post

            val cleanTitleRaw = title.trim()
            val cleanMessageRaw = message.trim()
            val now = System.currentTimeMillis()

            // 1. PRIMARY LOCK: Prevent Accessibility fallback from overwriting fresh Listener data
            if (source == "AccessibilityFallback" && now - lastPrimaryEventTime < 1500L) {
                return@post
            } else if (source == "NotificationListener") {
                lastPrimaryEventTime = now
            }

            // 2. BLANK OVERWRITE GUARD: Don't let empty events clear visible text
            if (isExpanded && notificationMode && cleanTitleRaw.isBlank() && cleanMessageRaw.isBlank()) {
                return@post
            }

            // 3. AUTO-COLLAPSE RESET: Keep island open as long as notifications arrive
            autoCollapseRunnable?.let { mainHandler.removeCallbacks(it) }

            if (cleanTitleRaw.isBlank() && cleanMessageRaw.isBlank()) return@post

            val display = buildDisplayText(appName, cleanTitleRaw, cleanMessageRaw)
            val fingerprint = "$packageName|${display.title}|${display.message}"

            // 4. DUPLICATE SPAM PROTECTION
            if (fingerprint == lastIslandFingerprint && now - lastIslandFingerprintTime < 1000L) {
                // Still reset timer even if duplicate to keep it open
                scheduleAutoCollapse() 
                return@post
            }

            lastIslandFingerprint = fingerprint
            lastIslandFingerprintTime = now

            if (visualRoot == null || touchView == null) showIslandInternal()

            // 5. VIEW REFRESH GUARD: Force UI thread to redraw
            titleText?.clearAnimation()
            messageText?.clearAnimation()
            
            notificationMode = true
            currentPendingIntent = contentIntent
            currentPackageName = packageName

            appIconView?.setImageDrawable(loadAppIcon(packageName))
            appNameTimeText?.text = "${display.appName} • ${formatNotificationTime(postTime)}"
            titleText?.text = display.title
            messageText?.text = display.message

            titleText?.visibility = if (display.title.isBlank()) View.GONE else View.VISIBLE
            messageText?.visibility = if (display.message.isBlank()) View.GONE else View.VISIBLE

            visualRoot?.requestLayout()
            visualRoot?.invalidate()

            if (!isExpanded) {
                contentContainer?.visibility = View.VISIBLE
                contentContainer?.alpha = 0f
                setExpandedAnimated(true, ExpandReason.AUTO_NOTIFICATION)
            } else {
                // If already expanded, just refresh the content view smoothly
                contentContainer?.animate()?.alpha(1f)?.setDuration(200)?.start()
            }

            scheduleAutoCollapse()
        }
    }

    private fun scheduleAutoCollapse() {
        autoCollapseRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            if (notificationMode) {
                notificationMode = false
                currentPendingIntent = null
                currentPackageName = null
                setExpandedAnimated(false, ExpandReason.AUTO_NOTIFICATION)
            }
        }
        autoCollapseRunnable = runnable
        mainHandler.postDelayed(runnable, 5200L)
    }

    private fun openCurrentNotification() {
        val pending = currentPendingIntent
        val pkg = currentPackageName

        notificationMode = false
        currentPendingIntent = null
        currentPackageName = null
        autoCollapseRunnable?.let { mainHandler.removeCallbacks(it) }
        autoCollapseRunnable = null

        try {
            if (pending != null) {
                pending.send()
            } else if (!pkg.isNullOrBlank()) {
                val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launchIntent)
                }
            }
        } catch (_: Exception) {}

        setExpandedAnimated(false, ExpandReason.AUTO_NOTIFICATION)
    }

    private fun showIslandInternal() {
        if (visualRoot != null && touchView != null) {
            updateAllToCurrentState()
            return
        }

        hideIslandInternal()
        isExpanded = false
        notificationMode = false

        val compactWidth = dp(AppSettings.getIslandWidthDp(this))
        val compactHeight = dp(AppSettings.getIslandHeightDp(this))
        val compactRadius = compactCornerRadiusPx().toFloat()

        islandBackground = createIslandBackground(compactRadius)

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(outlineRect, outlineRadius)
                }
            }
        }

        val island = FrameLayout(this).apply {
            background = islandBackground
            elevation = 0f // Pure AMOLED black
            alpha = 1f
            clipToOutline = true
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }

        val horizontalContent = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            alpha = 0f
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }

        val icon = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }

        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }

        val appLine = TextView(this).apply {
            setTextColor(Color.rgb(0, 150, 255))
            textSize = 12f
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        val title = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        val msg = TextView(this).apply {
            setTextColor(Color.rgb(210, 210, 216))
            textSize = 13f
            includeFontPadding = false
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }

        textColumn.addView(appLine)
        textColumn.addView(title)
        textColumn.addView(msg)
        horizontalContent.addView(icon, LinearLayout.LayoutParams(dp(38), dp(38)))
        horizontalContent.addView(textColumn, LinearLayout.LayoutParams(0, -2, 1f))
        island.addView(horizontalContent, FrameLayout.LayoutParams(-1, -1))

        val childParams = FrameLayout.LayoutParams(compactWidth, compactHeight).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }

        root.addView(island, childParams)

        val touch = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        touchStartY = event.rawY
                        touchStartX = event.rawX
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        val dy = event.rawY - touchStartY
                        val dx = event.rawX - touchStartX
                        if (dy < -dp(24) && abs(dy) > abs(dx)) {
                            postCollapseIsland()
                        } else {
                            postToggleExpanded()
                        }
                        true
                    }
                    else -> true
                }
            }
        }

        visualRoot = root
        visualParams = createVisualParams()
        islandView = island
        islandLayoutParams = childParams
        contentContainer = horizontalContent
        appIconView = icon
        appNameTimeText = appLine
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
        }
    }

    private fun updateAllToCurrentState() {
        val root = visualRoot ?: return
        val vParams = visualParams ?: return
        val child = islandLayoutParams ?: return

        val targetWidth = dp(currentTargetWidthDp())
        val targetHeight = dp(currentTargetHeightDp())
        val targetRadius = if (isExpanded) expandedCornerRadiusPx().toFloat() else compactCornerRadiusPx().toFloat()

        vParams.width = -1
        vParams.height = dp(AppSettings.getIslandExpandedHeightDp(this))
        vParams.y = dp(AppSettings.getIslandYDp(this))

        child.width = targetWidth
        child.height = targetHeight
        
        islandBackground?.cornerRadius = targetRadius
        updateOutlineForIsland(targetWidth, targetHeight, targetRadius)

        try {
            windowManager?.updateViewLayout(root, vParams)
            islandView?.layoutParams = child
            visualRoot?.invalidateOutline()
            updateTouchWindow(targetWidth, targetHeight)
            updateOutsideWatcherForState()
        } catch (_: Exception) {}
    }

    private fun setExpandedAnimated(expanded: Boolean, reason: ExpandReason) {
        if (isExpanded == expanded && morphAnimator?.isRunning != true) return
        val root = visualRoot ?: return
        val child = islandLayoutParams ?: return

        morphAnimator?.cancel()
        val wasExpanded = isExpanded
        isExpanded = expanded
        if (expanded) expandReason = reason

        val startWidth = child.width
        val startHeight = child.height
        val startRadius = islandBackground?.cornerRadius ?: if (wasExpanded) expandedCornerRadiusPx().toFloat() else compactCornerRadiusPx().toFloat()

        val targetWidth = dp(currentTargetWidthDp())
        val targetHeight = dp(currentTargetHeightDp())
        val targetRadius = if (expanded) expandedCornerRadiusPx().toFloat() else compactCornerRadiusPx().toFloat()

        updateTouchWindow(targetWidth, targetHeight)
        updateOutsideWatcherForState()
        updateVisualRootStatic(root)

        morphAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (expanded) 360L else 280L
            interpolator = morphInterpolator
            addUpdateListener { animator ->
                val t = animator.animatedValue as Float
                val w = lerp(startWidth, targetWidth, t)
                val h = lerp(startHeight, targetHeight, t)
                val r = lerp(startRadius, targetRadius, t)

                child.width = w
                child.height = h
                islandBackground?.cornerRadius = r
                updateOutlineForIsland(w, h, r)

                if (expanded && notificationMode) {
                    contentContainer?.alpha = ((t - 0.35f) / 0.65f).coerceIn(0f, 1f)
                } else if (!expanded) {
                    contentContainer?.alpha = (1f - (t / 0.5f)).coerceIn(0f, 1f)
                }

                islandView?.layoutParams = child
                visualRoot?.invalidateOutline()
            }
            addListener(object : android.animation.Animator.AnimatorListener {
                override fun onAnimationStart(a: android.animation.Animator) {}
                override fun onAnimationCancel(a: android.animation.Animator) {}
                override fun onAnimationRepeat(a: android.animation.Animator) {}
                override fun onAnimationEnd(a: android.animation.Animator) {
                    if (!expanded) {
                        contentContainer?.alpha = 0f
                        contentContainer?.visibility = View.GONE
                        notificationMode = false
                    }
                    visualRoot?.invalidateOutline()
                }
            })
            start()
        }
    }

    private fun updateOutsideWatcherForState() {
        if (isExpanded && expandReason == ExpandReason.MANUAL_USER) ensureOutsideWatcher()
        else removeOutsideWatcher()
    }

    private fun ensureOutsideWatcher() {
        if (outsideWatcherView != null) return
        val watcher = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_OUTSIDE) postCollapseIsland()
                false
            }
        }
        val params = createOutsideWatcherParams()
        outsideWatcherView = watcher
        outsideWatcherParams = params
        try { windowManager?.addView(watcher, params) } catch (_: Exception) {}
    }

    private fun removeOutsideWatcher() {
        outsideWatcherView?.let { try { windowManager?.removeViewImmediate(it) } catch (_: Exception) {} }
        outsideWatcherView = null
    }

    private fun updateOutlineForIsland(widthPx: Int, heightPx: Int, radiusPx: Float) {
        val rootWidth = resources.displayMetrics.widthPixels
        val left = ((rootWidth - widthPx) / 2) + dp(AppSettings.getIslandXDp(this))
        outlineRect.set(left, 0, left + widthPx, heightPx)
        outlineRadius = radiusPx
    }

    private fun updateVisualRootStatic(root: FrameLayout) {
        val params = visualParams ?: return
        params.height = dp(AppSettings.getIslandExpandedHeightDp(this))
        params.y = dp(AppSettings.getIslandYDp(this))
        try { windowManager?.updateViewLayout(root, params) } catch (_: Exception) {}
    }

    private fun updateTouchWindow(widthPx: Int, heightPx: Int) {
        val params = touchParams ?: return
        params.width = widthPx
        params.height = heightPx
        params.x = dp(AppSettings.getIslandXDp(this))
        params.y = dp(AppSettings.getIslandYDp(this))
        try { windowManager?.updateViewLayout(touchView!!, params) } catch (_: Exception) {}
    }

    private fun hideIslandInternal() {
        morphAnimator?.cancel()
        autoCollapseRunnable?.let { mainHandler.removeCallbacks(it) }
        removeOutsideWatcher()
        touchView?.let { try { windowManager?.removeViewImmediate(it) } catch (_: Exception) {} }
        visualRoot?.let { try { windowManager?.removeViewImmediate(it) } catch (_: Exception) {} }
        visualRoot = null
        touchView = null
        isExpanded = false
    }

    private fun createVisualParams() = WindowManager.LayoutParams(
        -1, dp(AppSettings.getIslandExpandedHeightDp(this)),
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR or
                WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = dp(AppSettings.getIslandYDp(this@HyperAccessibilityService))
        if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = 1
        title = "HyperIslandProVisual"
    }

    private fun createTouchParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR or WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        x = dp(AppSettings.getIslandXDp(this@HyperAccessibilityService))
        y = dp(AppSettings.getIslandYDp(this@HyperAccessibilityService))
        if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = 1
        title = "HyperIslandProTouch"
    }

    private fun createOutsideWatcherParams() = WindowManager.LayoutParams(
        -1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        title = "HyperIslandProOutside"
    }

    private fun createIslandBackground(r: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(Color.BLACK)
        cornerRadius = r
    }

    private fun loadAppIcon(pkg: String) = try { packageManager.getApplicationIcon(pkg) } catch (_: Exception) { null }
    private fun getAppName(pkg: String) = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
    private fun formatNotificationTime(t: Long) = if (t <= 0L || System.currentTimeMillis() - t < 60000L) "now" else SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(t))

    private fun currentTargetWidthDp() = if (isExpanded) AppSettings.getIslandExpandedWidthDp(this) else AppSettings.getIslandWidthDp(this)
    private fun currentTargetHeightDp() = if (isExpanded) AppSettings.getIslandExpandedHeightDp(this) else AppSettings.getIslandHeightDp(this)
    private fun compactCornerRadiusPx() = dp(AppSettings.getIslandHeightDp(this) / 2)
    private fun expandedCornerRadiusPx(): Int {
        val h = AppSettings.getIslandExpandedHeightDp(this)
        return dp((h / 3).coerceIn(34, 46))
    }

    private fun lerp(s: Int, e: Int, p: Float) = (s + ((e - s) * p)).roundToInt()
    private fun lerp(s: Float, e: Float, p: Float) = s + ((e - s) * p)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        hideIslandInternal()
        if (instance === this) instance = null
        super.onDestroy()
    }
}
