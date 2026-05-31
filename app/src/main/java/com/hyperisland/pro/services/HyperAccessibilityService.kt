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

    private enum class IslandStage { STAGE1_IDLE, STAGE2_PING, STAGE3_FULL }
    private enum class ExpandReason { MANUAL_USER, AUTO_NOTIFICATION }
    
    private data class DisplayText(
        val appName: String,
        val title: String,
        val message: String
    )

    companion object {
        @Volatile private var instance: HyperAccessibilityService? = null
        
        @Volatile var lastAccessibilityDebugMessage: String = "Accessibility active"
            private set

        fun isConnected(): Boolean = instance != null

        fun showIslandFromApp(context: Context): Boolean {
            val service = instance ?: return false
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
    private var currentStage = IslandStage.STAGE1_IDLE
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

        if (AppSettings.isIslandEnabled(this)) {
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

        lastAccessibilityDebugMessage = "Fallback captured: $appName"

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

    private fun postShowIsland() = mainHandler.post { 
        showIslandInternal() 
    }
    
    private fun postHideIsland() = mainHandler.post { 
        hideIslandInternal()
    }

    private fun postUpdateIsland() = mainHandler.post { 
        if (visualRoot == null) showIslandInternal() else updateAllToCurrentState() 
    }

    private fun postExpandIsland() = mainHandler.post { 
        setStageAnimated(IslandStage.STAGE3_FULL, ExpandReason.MANUAL_USER) 
    }

    private fun postCollapseIsland() = mainHandler.post { 
        setStageAnimated(IslandStage.STAGE1_IDLE, expandReason) 
    }

    private fun postToggleExpanded() = mainHandler.post { 
        if (notificationMode && currentStage == IslandStage.STAGE3_FULL) {
            openCurrentNotification()
        } else {
            setStageAnimated(if (currentStage == IslandStage.STAGE3_FULL) IslandStage.STAGE1_IDLE else IslandStage.STAGE3_FULL, ExpandReason.MANUAL_USER) 
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
            val now = System.currentTimeMillis()

            if (source == "AccessibilityFallback" && now - lastPrimaryEventTime < 1500L) return@post
            if (source == "NotificationListener") lastPrimaryEventTime = now

            if (currentStage == IslandStage.STAGE3_FULL && title.isBlank() && message.isBlank()) return@post
            
            autoCollapseRunnable?.let { mainHandler.removeCallbacks(it) }

            val display = buildDisplayText(appName, title, message)
            val fingerprint = "$packageName|${display.title}|${display.message}"

            if (fingerprint == lastIslandFingerprint && now - lastIslandFingerprintTime < 1000L) {
                scheduleAutoCollapse() 
                return@post
            }

            lastIslandFingerprint = fingerprint
            lastIslandFingerprintTime = now

            if (visualRoot == null) showIslandInternal()

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

            if (currentStage == IslandStage.STAGE1_IDLE) {
                setStageAnimated(IslandStage.STAGE2_PING, ExpandReason.AUTO_NOTIFICATION)
                mainHandler.postDelayed({
                    if (notificationMode) setStageAnimated(IslandStage.STAGE3_FULL, ExpandReason.AUTO_NOTIFICATION)
                }, 800L)
            } else {
                contentContainer?.animate()?.alpha(1f)?.setDuration(200)?.start()
            }
            scheduleAutoCollapse()
        }
    }

    private fun buildDisplayText(appName: String, rawTitle: String, rawMessage: String): DisplayText {
        val cleanApp = appName.trim().ifBlank { "App" }
        var title = rawTitle.trim()
        var message = rawMessage.trim()

        if (title.equals(cleanApp, ignoreCase = true)) {
            title = message
            message = ""
        }

        if (title.isBlank() && message.isNotBlank()) {
            title = message
            message = ""
        }

        return DisplayText(appName = cleanApp, title = title, message = message)
    }

    private fun scheduleAutoCollapse() {
        autoCollapseRunnable?.let { mainHandler.removeCallbacks(it) }
        val runnable = Runnable {
            if (notificationMode) {
                notificationMode = false
                currentPendingIntent = null
                currentPackageName = null
                setStageAnimated(IslandStage.STAGE1_IDLE, ExpandReason.AUTO_NOTIFICATION)
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

        setStageAnimated(IslandStage.STAGE1_IDLE, ExpandReason.AUTO_NOTIFICATION)
    }

    private fun showIslandInternal() {
        hideIslandInternal()

        val compactWidth = dp(AppSettings.getIslandWidthDp(this))
        val compactHeight = dp(AppSettings.getIslandHeightDp(this))
        islandBackground = createIslandBackground(dp(compactHeight / 2).toFloat())

        visualRoot = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(outlineRect, outlineRadius)
                }
            }
        }

        islandView = FrameLayout(this).apply {
            background = islandBackground
            elevation = 0f 
            clipToOutline = true
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }

        contentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            alpha = 0f
            setPadding(dp(18), dp(12), dp(18), dp(12))

            appIconView = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
            }

            val textColumn = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), 0, 0, 0)
            }

            appNameTimeText = TextView(context).apply {
                setTextColor(Color.rgb(0, 150, 255))
                textSize = 12f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }

            titleText = TextView(context).apply {
                setTextColor(Color.WHITE)
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }

            messageText = TextView(context).apply {
                setTextColor(Color.rgb(210, 210, 216))
                textSize = 13f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }

            textColumn.addView(appNameTimeText)
            textColumn.addView(titleText)
            textColumn.addView(messageText)

            addView(appIconView, LinearLayout.LayoutParams(dp(38), dp(38)))
            addView(textColumn, LinearLayout.LayoutParams(0, WindowManager.LayoutParams.WRAP_CONTENT, 1f))
        }

        islandView?.addView(contentContainer, FrameLayout.LayoutParams(-1, -1))
        islandLayoutParams = FrameLayout.LayoutParams(compactWidth, compactHeight).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }
        visualRoot?.addView(islandView, islandLayoutParams)

        touchView = FrameLayout(this).apply {
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

        visualParams = createVisualParams()
        touchParams = createTouchParams(compactWidth, compactHeight)

        updateOutlineForIsland(compactWidth, compactHeight, dp(compactHeight / 2).toFloat())

        try {
            windowManager?.addView(visualRoot, visualParams)
            windowManager?.addView(touchView, touchParams)
        } catch (e: Exception) {
            hideIslandInternal()
        }
    }

    private fun updateAllToCurrentState() {
        val root = visualRoot ?: return
        val child = islandLayoutParams ?: return

        val targetWidth = dp(getTargetWidth(currentStage))
        val targetHeight = dp(getTargetHeight(currentStage))
        val targetRadius = getTargetRadius(currentStage).toFloat()

        child.width = targetWidth
        child.height = targetHeight
        
        islandBackground?.cornerRadius = targetRadius
        updateOutlineForIsland(targetWidth, targetHeight, targetRadius)

        try {
            updateVisualRootStatic(root)
            islandView?.layoutParams = child
            visualRoot?.invalidateOutline()
            updateTouchWindow(targetWidth, targetHeight)
            updateOutsideWatcherForState()
        } catch (_: Exception) {}
    }

    private fun setStageAnimated(targetStage: IslandStage, reason: ExpandReason) {
        if (currentStage == targetStage && morphAnimator?.isRunning != true) return
        val root = visualRoot ?: return
        val child = islandLayoutParams ?: return

        morphAnimator?.cancel()
        val wasExpandedStage = currentStage
        currentStage = targetStage
        expandReason = reason

        val startWidth = child.width
        val startHeight = child.height
        val startRadius = islandBackground?.cornerRadius ?: dp(AppSettings.getIslandHeightDp(this) / 2).toFloat()

        val targetWidth = dp(getTargetWidth(targetStage))
        val targetHeight = dp(getTargetHeight(targetStage))
        val targetRadius = getTargetRadius(targetStage).toFloat()

        updateTouchWindow(targetWidth, targetHeight)
        updateOutsideWatcherForState()
        updateVisualRootStatic(root)

        morphAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (targetStage == IslandStage.STAGE1_IDLE) 280L else 360L
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

                if (notificationMode) {
                    if (targetStage == IslandStage.STAGE3_FULL) {
                        contentContainer?.alpha = ((t - 0.4f) / 0.6f).coerceIn(0f, 1f)
                    } else if (targetStage == IslandStage.STAGE1_IDLE) {
                        contentContainer?.alpha = (1f - (t / 0.3f)).coerceIn(0f, 1f)
                    }
                }

                islandView?.layoutParams = child
                visualRoot?.invalidateOutline()
            }
            addListener(object : android.animation.Animator.AnimatorListener {
                override fun onAnimationStart(a: android.animation.Animator) {
                    if (targetStage == IslandStage.STAGE3_FULL) contentContainer?.visibility = View.VISIBLE
                }
                override fun onAnimationEnd(a: android.animation.Animator) {
                    if (targetStage == IslandStage.STAGE1_IDLE) {
                        contentContainer?.alpha = 0f
                        contentContainer?.visibility = View.GONE
                        notificationMode = false
                    }
                    visualRoot?.invalidateOutline()
                }
                override fun onAnimationCancel(a: android.animation.Animator) {}
                override fun onAnimationRepeat(a: android.animation.Animator) {}
            })
            start()
        }
    }

    private fun getTargetWidth(stage: IslandStage) = when(stage) {
        IslandStage.STAGE1_IDLE -> AppSettings.getIslandWidthDp(this)
        IslandStage.STAGE2_PING -> AppSettings.getIslandStage2WidthDp(this)
        IslandStage.STAGE3_FULL -> AppSettings.getIslandExpandedWidthDp(this)
    }

    private fun getTargetHeight(stage: IslandStage) = when(stage) {
        IslandStage.STAGE1_IDLE -> AppSettings.getIslandHeightDp(this)
        IslandStage.STAGE2_PING -> AppSettings.getIslandHeightDp(this) + 4
        IslandStage.STAGE3_FULL -> AppSettings.getIslandExpandedHeightDp(this)
    }

    private fun getTargetRadius(stage: IslandStage) = dp(getTargetHeight(stage) / if (stage == IslandStage.STAGE3_FULL) 3 else 2)

    private fun updateOutsideWatcherForState() {
        if (currentStage == IslandStage.STAGE3_FULL && expandReason == ExpandReason.MANUAL_USER) ensureOutsideWatcher()
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
        visualParams?.apply { 
            height = dp(AppSettings.getIslandExpandedHeightDp(this@HyperAccessibilityService) + 20)
            y = dp(AppSettings.getIslandYDp(this@HyperAccessibilityService)) 
        }
        try { windowManager?.updateViewLayout(root, visualParams) } catch (_: Exception) {}
    }

    private fun updateTouchWindow(widthPx: Int, heightPx: Int) {
        val touchH = if (currentStage == IslandStage.STAGE1_IDLE) dp(25) else heightPx
        touchParams?.apply { 
            width = widthPx; height = touchH
            x = dp(AppSettings.getIslandXDp(this@HyperAccessibilityService))
            y = dp(AppSettings.getIslandYDp(this@HyperAccessibilityService))
        }
        try { windowManager?.updateViewLayout(touchView!!, touchParams) } catch (_: Exception) {}
    }

    private fun hideIslandInternal() {
        morphAnimator?.cancel()
        autoCollapseRunnable?.let { mainHandler.removeCallbacks(it) }
        removeOutsideWatcher()
        touchView?.let { try { windowManager?.removeViewImmediate(it) } catch (_: Exception) {} }
        visualRoot?.let { try { windowManager?.removeViewImmediate(it) } catch (_: Exception) {} }
        visualRoot = null
        touchView = null
        currentStage = IslandStage.STAGE1_IDLE
    }

    private fun createVisualParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, dp(AppSettings.getIslandExpandedHeightDp(this) + 20),
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR or
                WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = dp(AppSettings.getIslandYDp(this@HyperAccessibilityService))
        if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        title = "HyperIslandProVisual"
    }

    private fun createTouchParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, dp(25), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR or WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        x = dp(AppSettings.getIslandXDp(this@HyperAccessibilityService))
        y = dp(AppSettings.getIslandYDp(this@HyperAccessibilityService))
        if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        title = "HyperIslandProTouch"
    }

    private fun createOutsideWatcherParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
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
    private fun lerp(s: Int, e: Int, p: Float) = (s + ((e - s) * p)).roundToInt()
    private fun lerp(s: Float, e: Float, p: Float) = s + ((e - s) * p)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        hideIslandInternal()
        if (instance === this) instance = null
        super.onDestroy()
    }
}
