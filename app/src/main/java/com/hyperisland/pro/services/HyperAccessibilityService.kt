package com.hyperisland.pro.services

import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.AnimatorSet
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
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.hyperisland.pro.core.AppSettings
import java.text.SimpleDateFormat
import java.util.ArrayDeque
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

    private data class NotificationModel(
        val packageName: String,
        val appName: String,
        val title: String,
        val message: String,
        val postTime: Long,
        val contentIntent: PendingIntent?
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
    private val fluidInterpolator = PathInterpolator(0.2f, 1f, 0.2f, 1f)
    private val notificationQueue = ArrayDeque<NotificationModel>()
    private var isProcessingQueue = false

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

    private var morphAnimator: Animator? = null
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

        val textItems = mutableListOf<String>()
        val rawText = event.text
        if (rawText != null) {
            for (t in rawText) {
                val s = t?.toString()?.trim()
                if (!s.isNullOrBlank()) {
                    textItems.add(s)
                }
            }
        }
        
        if (textItems.isEmpty()) return

        val appName = getAppName(pkg)
        var finalTitle = ""
        var finalMessage = ""

        if (textItems.size >= 2) {
            finalTitle = textItems[0]
            val subList = textItems.subList(1, textItems.size)
            finalMessage = subList.joinToString(" • ")
        } else {
            finalTitle = appName
            finalMessage = textItems[0]
        }

        lastAccessibilityDebugMessage = "Fallback captured: $appName"

        postNotificationEvent(
            source = "AccessibilityFallback",
            packageName = pkg,
            appName = appName,
            title = finalTitle,
            message = finalMessage,
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
            val target = if (currentStage == IslandStage.STAGE3_FULL) IslandStage.STAGE1_IDLE else IslandStage.STAGE3_FULL
            setStageAnimated(target, ExpandReason.MANUAL_USER) 
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
                triggerFluidExpansion()
            } else {
                contentContainer?.animate()?.alpha(1f)?.setDuration(200)?.start()
            }
            scheduleAutoCollapse()
        }
    }

    private fun triggerFluidExpansion() {
        if (morphAnimator?.isRunning == true) morphAnimator?.cancel()
        
        val startW = dp(AppSettings.getIslandWidthDp(this))
        val pingW = dp(AppSettings.getIslandStage2WidthDp(this))
        val targetW = dp(AppSettings.getIslandExpandedWidthDp(this))
        val initialH = dp(AppSettings.getIslandHeightDp(this))
        val targetH = dp(AppSettings.getIslandExpandedHeightDp(this))
        val startR = dp(AppSettings.getIslandHeightDp(this) / 2).toFloat()
        val targetR = expandedCornerRadiusPx().toFloat()

        currentStage = IslandStage.STAGE3_FULL
        expandReason = ExpandReason.AUTO_NOTIFICATION
        updateOutsideWatcherForState()
        visualRoot?.let { updateVisualRootStatic(it) }
        updateTouchWindow(targetW, targetH)

        val pingAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 120L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                val w = lerp(startW, pingW, t)
                updateIslandInternalView(w, initialH, startR)
            }
        }

        val expandAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 360L
            interpolator = fluidInterpolator
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                val w = lerp(pingW, targetW, t)
                val h = lerp(initialH, targetH, t)
                val r = lerp(startR, targetR, t)
                updateIslandInternalView(w, h, r)
                contentContainer?.visibility = View.VISIBLE
                contentContainer?.alpha = t
            }
        }

        morphAnimator = AnimatorSet().apply {
            playSequentially(pingAnimator, expandAnimator)
            start()
        }
    }

    private fun processNextInQueue() {
        val next = notificationQueue.poll() ?: run {
            isProcessingQueue = false
            return
        }

        isProcessingQueue = true
        notificationMode = true
        
        if (currentStage == IslandStage.STAGE1_IDLE) {
            updateNotificationContent(next)
            if (visualRoot == null) showIslandInternal()
            triggerFluidExpansion()
            scheduleAutoCollapse()
        } else {
            playFluidTransitionAnimation(next)
        }
    }

    private fun playFluidTransitionAnimation(next: NotificationModel) {
        val exitAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250L
            interpolator = AccelerateInterpolator()
            addUpdateListener { animator ->
                val t = animator.animatedValue as Float
                contentContainer?.translationY = t * 60f
                val scale = 1f - (t * 0.2f)
                contentContainer?.scaleX = scale; contentContainer?.scaleY = scale
                contentContainer?.alpha = 1f - t
            }
        }

        val entryAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300L
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val t = animator.animatedValue as Float
                contentContainer?.translationY = -60f * (1f - t)
                val scale = 0.8f + (t * 0.2f)
                contentContainer?.scaleX = scale; contentContainer?.scaleY = scale
                contentContainer?.alpha = t
            }
        }

        exitAnimator.addListener(object : android.animation.Animator.AnimatorListener {
            override fun onAnimationStart(a: android.animation.Animator) {}
            override fun onAnimationCancel(a: android.animation.Animator) {}
            override fun onAnimationRepeat(a: android.animation.Animator) {}
            override fun onAnimationEnd(a: android.animation.Animator) {
                updateNotificationContent(next)
                contentContainer?.translationY = -60f
                contentContainer?.alpha = 0f
                entryAnimator.start()
            }
        })
        
        entryAnimator.addListener(object : android.animation.Animator.AnimatorListener {
            override fun onAnimationEnd(a: android.animation.Animator) { scheduleAutoCollapse() }
            override fun onAnimationStart(a: android.animation.Animator) {}
            override fun onAnimationCancel(a: android.animation.Animator) {}
            override fun onAnimationRepeat(a: android.animation.Animator) {}
        })

        exitAnimator.start()
    }

    private fun updateNotificationContent(model: NotificationModel) {
        val display = buildDisplayText(model.appName, model.title, model.message)
        currentPendingIntent = model.contentIntent
        currentPackageName = model.packageName
        
        appIconView?.setImageDrawable(loadAppIcon(model.packageName))
        appNameTimeText?.text = "${display.appName} • ${formatNotificationTime(model.postTime)}"
        titleText?.text = display.title
        messageText?.text = display.message
        
        titleText?.visibility = if (display.title.isBlank()) View.GONE else View.VISIBLE
        messageText?.visibility = if (display.message.isBlank()) View.GONE else View.VISIBLE
        
        contentContainer?.clearAnimation()
        visualRoot?.requestLayout()
        visualRoot?.invalidate()
    }

    private fun updateIslandInternalView(w: Int, h: Int, r: Float) {
        val child = islandLayoutParams ?: return
        child.width = w
        child.height = h
        islandBackground?.cornerRadius = r
        updateOutlineForIsland(w, h, r)
        islandView?.layoutParams = child
        visualRoot?.invalidateOutline()
    }

    private fun setStageAnimated(targetStage: IslandStage, reason: ExpandReason) {
        if (currentStage == targetStage && morphAnimator?.isRunning == true) return
        if (targetStage == IslandStage.STAGE3_FULL && reason == ExpandReason.AUTO_NOTIFICATION) {
            triggerFluidExpansion()
            return
        }
        
        morphAnimator?.cancel()
        val startW = islandLayoutParams?.width ?: dp(AppSettings.getIslandWidthDp(this))
        val startH = islandLayoutParams?.height ?: dp(AppSettings.getIslandHeightDp(this))
        val startR = islandBackground?.cornerRadius ?: dp(AppSettings.getIslandHeightDp(this) / 2).toFloat()

        currentStage = targetStage
        expandReason = reason
        val targetW = dp(getTargetWidth(targetStage))
        val targetH = dp(getTargetHeight(targetStage))
        val targetR = getTargetRadius(targetStage).toFloat()

        if (targetStage != IslandStage.STAGE1_IDLE) updateTouchWindow(targetW, targetH)
        visualRoot?.let { updateVisualRootStatic(it) }

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (targetStage == IslandStage.STAGE1_IDLE) 280L else 360L
            interpolator = fluidInterpolator
            addUpdateListener { animator ->
                val t = animator.animatedValue as Float
                val w = lerp(startW, targetW, t)
                val h = lerp(startH, targetH, t)
                val r = lerp(startR, targetR, t)

                updateIslandInternalView(w, h, r)

                if (notificationMode && targetStage == IslandStage.STAGE1_IDLE) {
                    contentContainer?.alpha = (1f - (t / 0.3f)).coerceIn(0f, 1f)
                }
            }
            addListener(object : android.animation.Animator.AnimatorListener {
                override fun onAnimationStart(a: android.animation.Animator) {}
                override fun onAnimationEnd(a: android.animation.Animator) {
                    if (targetStage == IslandStage.STAGE1_IDLE) {
                        contentContainer?.alpha = 0f
                        contentContainer?.visibility = View.GONE
                        notificationMode = false
                        updateTouchWindow(targetW, targetH)
                    }
                    visualRoot?.invalidateOutline()
                    updateOutsideWatcherForState()
                }
                override fun onAnimationCancel(a: android.animation.Animator) {}
                override fun onAnimationRepeat(a: android.animation.Animator) {}
            })
        }
        morphAnimator = animator
        animator.start()
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

    private fun buildDisplayText(appName: String, rawTitle: String, rawMessage: String): DisplayText {
        val cleanApp = appName.trim().ifBlank { "App" }
        var title = rawTitle.trim()
        var message = rawMessage.trim()
        if (title.equals(cleanApp, ignoreCase = true)) { title = message; message = "" }
        if (title.isBlank() && message.isNotBlank()) { title = message; message = "" }
        return DisplayText(appName = cleanApp, title = title, message = message)
    }

    private fun scheduleAutoCollapse() {
        autoCollapseRunnable?.let { mainHandler.removeCallbacks(it) }
        
        val queueSize = notificationQueue.size
        val delay = when {
            queueSize == 0 -> 5200L
            queueSize in 1..5 -> 3000L
            queueSize in 6..19 -> 1500L
            else -> 800L
        }

        val runnable = Runnable {
            if (notificationQueue.isNotEmpty()) processNextInQueue()
            else { isProcessingQueue = false; notificationMode = false; setStageAnimated(IslandStage.STAGE1_IDLE, ExpandReason.AUTO_NOTIFICATION) }
        }
        autoCollapseRunnable = runnable
        mainHandler.postDelayed(runnable, delay)
    }

    private fun openCurrentNotification() {
        try { currentPendingIntent?.send() ?: currentPackageName?.let { pkg -> packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it.addFlags(268435456)) } } } catch (_: Exception) {}
        setStageAnimated(IslandStage.STAGE1_IDLE, ExpandReason.AUTO_NOTIFICATION)
    }

    private fun showIslandInternal() {
        hideIslandInternal()
        val h = dp(AppSettings.getIslandHeightDp(this))
        islandBackground = createIslandBackground(dp(h / 2).toFloat())

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

            appIconView = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            val textColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
            appNameTimeText = TextView(context).apply { setTextColor(Color.rgb(0, 150, 255)); textSize = 12f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            titleText = TextView(context).apply { setTextColor(Color.WHITE); textSize = 16f; typeface = Typeface.DEFAULT_BOLD; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            messageText = TextView(context).apply { setTextColor(Color.rgb(210, 210, 216)); textSize = 13f; maxLines = 2; ellipsize = TextUtils.TruncateAt.END }

            textColumn.addView(appNameTimeText); textColumn.addView(titleText); textColumn.addView(messageText)
            addView(appIconView, LinearLayout.LayoutParams(dp(38), dp(38)))
            addView(textColumn, LinearLayout.LayoutParams(0, -2, 1f))
        }

        islandView?.addView(contentContainer, FrameLayout.LayoutParams(-1, -1))
        islandLayoutParams = FrameLayout.LayoutParams(dp(AppSettings.getIslandWidthDp(this)), h).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL }
        visualRoot?.addView(islandView, islandLayoutParams)

        touchView = FrameLayout(this).apply {
            isClickable = true
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) { touchStartY = event.rawY; touchStartX = event.rawX }
                else if (event.action == MotionEvent.ACTION_UP) {
                    if (event.rawY - touchStartY < -dp(24)) postCollapseIsland() else postToggleExpanded()
                }
                true
            }
        }

        visualParams = createVisualParams()
        touchParams = createTouchParams(dp(AppSettings.getIslandWidthDp(this)), h)
        updateOutlineForIsland(dp(AppSettings.getIslandWidthDp(this)), h, dp(h / 2).toFloat())

        try {
            windowManager?.addView(visualRoot, visualParams)
            windowManager?.addView(touchView, touchParams)
        } catch (e: Exception) {
            hideIslandInternal()
        }
    }

    private fun updateAllToCurrentState() {
        val w = dp(getTargetWidth(currentStage)); val h = dp(getTargetHeight(currentStage)); val r = getTargetRadius(currentStage).toFloat()
        updateIslandInternalView(w, h, r)
        visualRoot?.let { updateVisualRootStatic(it) }
        updateTouchWindow(w, h)
    }

    private fun updateOutsideWatcherForState() {
        if (currentStage == IslandStage.STAGE3_FULL && expandReason == ExpandReason.MANUAL_USER) ensureOutsideWatcher()
        else removeOutsideWatcher()
    }

    private fun ensureOutsideWatcher() {
        if (outsideWatcherView != null) return
        outsideWatcherView = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_OUTSIDE) postCollapseIsland()
                false
            }
        }
        try { windowManager?.addView(outsideWatcherView, createOutsideWatcherParams()) } catch (_: Exception) {}
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

    private fun updateTouchWindow(w: Int, h: Int) {
        val touchH = if (currentStage == IslandStage.STAGE1_IDLE) dp(25) else h
        try { windowManager?.updateViewLayout(touchView!!, touchParams!!.apply { width = w; height = touchH; x = dp(AppSettings.getIslandXDp(this@HyperAccessibilityService)); y = dp(AppSettings.getIslandYDp(this@HyperAccessibilityService)) }) } catch (_: Exception) {}
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
