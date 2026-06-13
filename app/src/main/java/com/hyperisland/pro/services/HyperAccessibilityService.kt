package com.hyperisland.pro.services

import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
import android.widget.HorizontalScrollView
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
    
    private data class DisplayText(val appName: String, val title: String, val message: String)

    private data class NotificationModel(
        val packageName: String,
        val appName: String,
        val title: String,
        val message: String,
        val postTime: Long,
        val contentIntent: PendingIntent?,
        val actions: List<Notification.Action> = emptyList()
    )

    companion object {
        // ---- Window flag bundles (Opus Style) -----------
        private const val WINDOW_FLAGS_INTERACTIVE =
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or // Render layer shouldn't block
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR or
                WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS

        private const val WINDOW_FLAGS_TOUCH =
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or // Pass touches outside hitbox
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR or
                WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS

        private const val WINDOW_FLAGS_OUTSIDE_WATCHER =
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

        // ---- Constants ----------------------------------
        private const val FLASH_QUEUE_THRESHOLD = 35
        private const val FALLBACK_DEDUP_WINDOW_MS = 1500L

        @Volatile private var instance: HyperAccessibilityService? = null
        @Volatile var lastAccessibilityDebugMessage: String = "Accessibility active"
            private set

        fun isConnected() = instance != null
        fun showIslandFromApp(context: Context) = instance?.apply { postShowIsland() } != null
        fun hideIslandFromApp(context: Context? = null) = instance?.apply { postHideIsland() } != null
        fun refreshIslandFromApp(context: Context) = instance?.apply { postUpdateIsland() } != null
        fun expandIslandFromApp(context: Context) = instance?.apply { postExpandIsland() } != null
        fun collapseIslandFromApp(context: Context) = instance?.apply { postCollapseIsland() } != null
        fun toggleExpandFromApp(context: Context) = instance?.apply { postToggleExpanded() } != null

        fun showNotificationFromApp(context: Context, packageName: String, appName: String, title: String, message: String, postTime: Long, contentIntent: PendingIntent?, actions: List<Notification.Action>): Boolean {
            val service = instance ?: return false
            service.postNotificationEvent("NotificationListener", packageName, appName, title, message, postTime, contentIntent, actions)
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
    private var appNameText: TextView? = null
    private var timeStampText: TextView? = null
    private var titleText: TextView? = null
    private var messageText: TextView? = null
    private var actionFooterScroll: HorizontalScrollView? = null
    private var actionFooterContainer: LinearLayout? = null

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
        if (AppSettings.isIslandEnabled(this)) postShowIsland()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) handleAccessibilityNotificationEvent(event)
    }

    override fun onInterrupt() = Unit

    private fun handleAccessibilityNotificationEvent(event: AccessibilityEvent) {
        if (!AppSettings.isIslandEnabled(this)) return
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isBlank() || pkg == packageName) return
        val rawText = event.text ?: return
        val textItems = rawText.mapNotNull { it?.toString()?.trim() }.filter { it.isNotBlank() }
        if (textItems.isEmpty()) return
        val appName = getAppName(pkg)
        val (t, m) = if (textItems.size >= 2) textItems[0] to textItems.subList(1, textItems.size).joinToString(" • ") else appName to textItems[0]
        postNotificationEvent("AccessibilityFallback", pkg, appName, t, m, System.currentTimeMillis(), null, emptyList())
    }

    private fun postShowIsland() = mainHandler.post { if (visualRoot == null) showIslandInternal() else updateAllToCurrentState() }
    private fun postHideIsland() = mainHandler.post { hideIslandInternal() }
    private fun postUpdateIsland() = mainHandler.post { if (visualRoot == null) showIslandInternal() else updateAllToCurrentState() }
    private fun postExpandIsland() = mainHandler.post { setStageAnimated(IslandStage.STAGE3_FULL, ExpandReason.MANUAL_USER) }
    private fun postCollapseIsland() = mainHandler.post { setStageAnimated(IslandStage.STAGE1_IDLE, expandReason) }
    private fun postToggleExpanded() = mainHandler.post { if (notificationMode && currentStage == IslandStage.STAGE3_FULL) openCurrentNotification() else setStageAnimated(if (currentStage == IslandStage.STAGE3_FULL) IslandStage.STAGE1_IDLE else IslandStage.STAGE3_FULL, ExpandReason.MANUAL_USER) }

    private fun postNotificationEvent(source: String, packageName: String, appName: String, title: String, message: String, postTime: Long, contentIntent: PendingIntent?, actions: List<Notification.Action>) {
        mainHandler.post {
            if (!AppSettings.isIslandEnabled(this)) return@post
            val now = System.currentTimeMillis()
            if (source == "AccessibilityFallback" && now - lastPrimaryEventTime < FALLBACK_DEDUP_WINDOW_MS) return@post
            if (source == "NotificationListener") lastPrimaryEventTime = now
            
            val display = buildDisplayText(appName, title, message)
            val fingerprint = "$packageName|${display.title}|${display.message}"
            if (fingerprint == lastIslandFingerprint && now - lastIslandFingerprintTime < 1000L) { scheduleAutoCollapse(); return@post }
            lastIslandFingerprint = fingerprint; lastIslandFingerprintTime = now

            val model = NotificationModel(packageName, appName, title, message, postTime, contentIntent, actions)
            notificationQueue.add(model)
            processQueue()
        }
    }

    private fun processQueue() {
        if (!AppSettings.isIslandEnabled(this) || notificationQueue.isEmpty()) return
        if (morphAnimator?.isRunning == true) return
        val next = notificationQueue.poll() ?: return
        isProcessingQueue = true
        notificationMode = true
        if (currentStage == IslandStage.STAGE1_IDLE) {
            updateNotificationContent(next)
            if (visualRoot == null) showIslandInternal()
            triggerFluidExpansion()
        } else {
            playFluidTransitionAnimation(next)
        }
    }

    private fun playFluidTransitionAnimation(next: NotificationModel) {
        val isFlash = notificationQueue.size >= FLASH_QUEUE_THRESHOLD
        val exitDur = if (isFlash) 120L else 200L
        val entryDur = if (isFlash) 150L else 300L
        val exitAnimator = ValueAnimator.ofFloat(0f, 1f).apply { duration = exitDur; interpolator = AccelerateInterpolator(); addUpdateListener { contentContainer?.translationY = it.animatedValue as Float * 80f; contentContainer?.alpha = 1f - it.animatedValue as Float } }
        val entryAnimator = ValueAnimator.ofFloat(0f, 1f).apply { duration = entryDur; interpolator = DecelerateInterpolator(); addUpdateListener { contentContainer?.translationY = -80f * (1f - it.animatedValue as Float); contentContainer?.alpha = it.animatedValue as Float } }
        exitAnimator.addListener(onEnd = {
            updateNotificationContent(next)
            contentContainer?.translationY = -80f; contentContainer?.alpha = 0f
            entryAnimator.start()
        })
        entryAnimator.addListener(onEnd = { scheduleAutoCollapse() })
        morphAnimator = exitAnimator; exitAnimator.start()
    }

    private fun updateNotificationContent(model: NotificationModel) {
        val display = buildDisplayText(model.appName, model.title, model.message)
        currentPendingIntent = model.contentIntent; currentPackageName = model.packageName
        appIconView?.setImageDrawable(loadAppIcon(model.packageName))
        appNameText?.text = display.appName; timeStampText?.text = formatNotificationTime(model.postTime)
        titleText?.text = display.title; messageText?.text = display.message
        titleText?.visibility = if (display.title.isBlank()) View.GONE else View.VISIBLE
        messageText?.visibility = if (display.message.isBlank()) View.GONE else View.VISIBLE
        setupActionTiles(model.actions)
        contentContainer?.clearAnimation(); visualRoot?.requestLayout(); visualRoot?.invalidate()
    }

    private fun setupActionTiles(actions: List<Notification.Action>) {
        actionFooterContainer?.removeAllViews()
        if (actions.isEmpty()) { actionFooterScroll?.visibility = View.GONE; return }
        actionFooterScroll?.visibility = View.VISIBLE
        val totalWidthDp = AppSettings.getIslandExpandedWidthDp(this) - 60
        val btnWidth = when { actions.size == 1 -> (totalWidthDp * 0.7f).toInt(); actions.size == 2 -> (totalWidthDp * 0.46f).toInt(); else -> (totalWidthDp * 0.31f).toInt() }
        for (action in actions) {
            val btn = TextView(this).apply {
                text = action.title; setTextColor(Color.WHITE); textSize = 12f; gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(8), 0, dp(8), 0)
                background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(Color.parseColor("#3A3A3C")); cornerRadius = dp(12).toFloat() }
                layoutParams = LinearLayout.LayoutParams(dp(buttonWidth), dp(32)).apply { rightMargin = dp(8) }
                setOnClickListener { try { action.actionIntent.send(); postCollapseIsland() } catch (_: Exception) {} }
            }
            actionFooterContainer?.addView(btn)
        }
    }

    private fun triggerFluidExpansion() {
        morphAnimator?.cancel()
        val startW = dp(AppSettings.getIslandWidthDp(this)); val pingW = dp(AppSettings.getIslandStage2WidthDp(this))
        val targetW = dp(AppSettings.getIslandExpandedWidthDp(this)); val initialH = dp(AppSettings.getIslandHeightDp(this)); val targetH = dp(AppSettings.getIslandExpandedHeightDp(this))
        val startR = dp(AppSettings.getIslandCornerRadiusDp(this)).toFloat(); val targetR = dp(AppSettings.getIslandExpandedCornerRadiusDp(this)).toFloat()
        currentStage = IslandStage.STAGE3_FULL; expandReason = ExpandReason.AUTO_NOTIFICATION
        updateVisualRootStatic(visualRoot!!); updateTouchWindow(targetW, targetH)
        val pingAnimator = ValueAnimator.ofFloat(0f, 1f).apply { duration = 120L; addUpdateListener { updateIslandInternalView(lerp(startW, pingW, it.animatedValue as Float), initialH, startR) } }
        val expandAnimator = ValueAnimator.ofFloat(0f, 1f).apply { duration = 360L; interpolator = fluidInterpolator; addUpdateListener { val t = it.animatedValue as Float; updateIslandInternalView(lerp(pingW, targetW, t), lerp(initialH, targetH, t), lerp(startR, targetR, t)); contentContainer?.visibility = View.VISIBLE; contentContainer?.alpha = t } }
        morphAnimator = AnimatorSet().apply { playSequentially(pingAnimator, expandAnimator); addListener(onEnd = { scheduleAutoCollapse() }); start() }
    }

    private fun updateIslandInternalView(w: Int, h: Int, r: Float) {
        val child = islandLayoutParams ?: return
        child.width = w; child.height = h; islandBackground?.cornerRadius = r
        updateOutlineForIsland(w, h, r); islandView?.layoutParams = child
        applyDynamicScaling(h); visualRoot?.invalidateOutline()
    }

    private fun applyDynamicScaling(heightPx: Int) {
        val heightDp = (heightPx / resources.displayMetrics.density).toInt()
        val isFull = currentStage == IslandStage.STAGE3_FULL
        val growth = if (isFull) (heightDp / 118f).coerceIn(1.0f, 2.0f) else 1.0f
        appIconView?.layoutParams = LinearLayout.LayoutParams(dp(if (isFull) (38 * growth).toInt().coerceAtMost(60) else 38), dp(if (isFull) (38 * growth).toInt().coerceAtMost(60) else 38))
        titleText?.textSize = if (isFull) (16f + (growth - 1f) * 6f).coerceAtMost(22f) else 16f
        messageText?.textSize = if (isFull) (13f + (growth - 1f) * 4f).coerceAtMost(17f) else 13f
        val hasActions = actionFooterScroll?.visibility == View.VISIBLE
        val footerReserved = if (hasActions) 60 else 20
        messageText?.maxLines = if (isFull) ((heightDp - footerReserved - 45) / 22).coerceIn(1, 10) else 2
    }

    private fun setStageAnimated(targetStage: IslandStage, reason: ExpandReason) {
        if (currentStage == targetStage && morphAnimator?.isRunning == true) return
        if (targetStage == IslandStage.STAGE3_FULL && reason == ExpandReason.AUTO_NOTIFICATION) { triggerFluidExpansion(); return }
        morphAnimator?.cancel()
        val curW = islandLayoutParams?.width ?: dp(AppSettings.getIslandWidthDp(this)); val curH = islandLayoutParams?.height ?: dp(AppSettings.getIslandHeightDp(this))
        val curR = islandBackground?.cornerRadius ?: dp(AppSettings.getIslandCornerRadiusDp(this)).toFloat()
        currentStage = targetStage; expandReason = reason
        val targetW = dp(getTargetWidth(targetStage)); val targetH = dp(getTargetHeight(targetStage)); val targetR = getTargetRadius(targetStage).toFloat()
        updateVisualRootStatic(visualRoot!!)
        updateTouchWindow(targetW, targetH)
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (targetStage == IslandStage.STAGE1_IDLE) 280L else 360L; interpolator = fluidInterpolator
            addUpdateListener { val t = it.animatedValue as Float; val w = lerp(curW, targetW, t); val h = lerp(curH, targetH, t); val r = lerp(curR, targetR, t); updateIslandInternalView(w, h, r); if (notificationMode && targetStage == IslandStage.STAGE1_IDLE) contentContainer?.alpha = (1f - (t / 0.3f)).coerceIn(0f, 1f) }
            addListener(onEnd = {
                if (targetStage == IslandStage.STAGE1_IDLE) { contentContainer?.alpha = 0f; contentContainer?.visibility = View.GONE; notificationMode = false; updateVisualRootStatic(visualRoot!!); updateTouchWindow(targetW, targetH) }
                visualRoot?.invalidateOutline()
                if (targetStage == IslandStage.STAGE1_IDLE && notificationQueue.isNotEmpty()) processQueue()
                else isProcessingQueue = false
            })
        }
        morphAnimator = animator; animator.start()
    }

    fun updateAllToCurrentState() {
        if (visualRoot == null) return
        val w = dp(getTargetWidth(currentStage)); val h = dp(getTargetHeight(currentStage)); val r = getTargetRadius(currentStage).toFloat()
        islandLayoutParams?.apply { width = w; height = h }; islandBackground?.cornerRadius = r
        updateOutlineForIsland(w, h, r); updateVisualRootStatic(visualRoot!!); updateTouchWindow(w, h)
        islandView?.layoutParams = islandLayoutParams; visualRoot?.invalidateOutline()
    }

    private fun getTargetWidth(s: IslandStage) = when(s) { IslandStage.STAGE1_IDLE -> AppSettings.getIslandWidthDp(this); IslandStage.STAGE2_PING -> AppSettings.getIslandStage2WidthDp(this); IslandStage.STAGE3_FULL -> AppSettings.getIslandExpandedWidthDp(this) }
    private fun getTargetHeight(s: IslandStage) = when(s) { IslandStage.STAGE1_IDLE -> AppSettings.getIslandHeightDp(this); IslandStage.STAGE2_PING -> AppSettings.getIslandHeightDp(this) + 4; IslandStage.STAGE3_FULL -> AppSettings.getIslandExpandedHeightDp(this) }
    private fun getTargetRadius(s: IslandStage) = if (s == IslandStage.STAGE3_FULL) dp(AppSettings.getIslandExpandedCornerRadiusDp(this)) else dp(AppSettings.getIslandCornerRadiusDp(this))
    private fun buildDisplayText(appName: String, t: String, m: String) = DisplayText(appName.trim().ifBlank { "App" }, t.trim().ifBlank { m.trim() }, if (t.trim().equals(appName.trim(), true)) "" else m.trim())
    private fun scheduleAutoCollapse() { autoCollapseRunnable?.let { mainHandler.removeCallbacks(it) }; autoCollapseRunnable = Runnable { if (notificationQueue.isNotEmpty()) processQueue() else setStageAnimated(IslandStage.STAGE1_IDLE, ExpandReason.AUTO_NOTIFICATION) }.also { mainHandler.postDelayed(it, when { notificationQueue.size == 0 -> 5200L; notificationQueue.size in 1..5 -> 3000L; notificationQueue.size in 6..19 -> 1200L; else -> 800L }) } }
    private fun openCurrentNotification() { try { currentPendingIntent?.send() ?: currentPackageName?.let { pkg -> packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it.addFlags(268435456)) } } } catch (_: Exception) {}; setStageAnimated(IslandStage.STAGE1_IDLE, ExpandReason.AUTO_NOTIFICATION) }

    private fun showIslandInternal() {
        hideIslandInternal()
        val h = dp(AppSettings.getIslandHeightDp(this)); val w = dp(AppSettings.getIslandWidthDp(this)); val r = dp(AppSettings.getIslandCornerRadiusDp(this)).toFloat()
        islandBackground = createIslandBackground(r)
        visualRoot = FrameLayout(this).apply { setBackgroundColor(0) }
        islandView = FrameLayout(this).apply { background = islandBackground; elevation = 0f; clipToOutline = true; setLayerType(View.LAYER_TYPE_HARDWARE, null) }
        
        contentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP; visibility = View.GONE; alpha = 0f; setPadding(dp(18), dp(18), dp(18), dp(18))
            appIconView = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            val textStack = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, 0, 0) }
            val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            appNameText = TextView(context).apply { setTextColor(Color.rgb(0, 150, 255)); typeface = Typeface.DEFAULT_BOLD }
            timeStampText = TextView(context).apply { setTextColor(Color.GRAY); setPadding(dp(8), 0, 0, 0) }
            header.addView(appNameText); header.addView(timeStampText)
            titleText = TextView(context).apply { setTextColor(Color.WHITE); textSize = 16f; typeface = Typeface.DEFAULT_BOLD; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            messageText = TextView(context).apply { setTextColor(Color.rgb(210, 210, 216)); textSize = 13f; ellipsize = TextUtils.TruncateAt.END }
            actionFooterScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; setPadding(0, dp(12), 0, 0)
                actionFooterContainer = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }; addView(actionFooterContainer)
            }
            textStack.addView(header); textStack.addView(titleText); textStack.addView(messageText); textStack.addView(actionFooterScroll)
            addView(appIconView, LinearLayout.LayoutParams(dp(38), dp(38))); addView(textStack, LinearLayout.LayoutParams(0, -2, 1f))
        }
        islandView?.addView(contentContainer, FrameLayout.LayoutParams(-1, -1))
        islandLayoutParams = FrameLayout.LayoutParams(w, h).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL }
        visualRoot?.addView(islandView, islandLayoutParams)
        
        visualParams = createVisualParams(); touchParams = createTouchParams(w, h)
        touchView = FrameLayout(this).apply { isClickable = true; setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_DOWN) { touchStartY = e.rawY; touchStartX = e.rawX } else if (e.action == MotionEvent.ACTION_UP) { if (e.rawY - touchStartY < -dp(24)) postCollapseIsland() else postToggleExpanded() }; true } }
        
        updateOutlineForIsland(w, h, r)
        try { windowManager?.addView(visualRoot, visualParams); windowManager?.addView(touchView, touchParams) } catch (e: Exception) { hideIslandInternal() }
    }

    private fun updateOutlineForIsland(w: Int, h: Int, r: Float) { outlineRect.set(0, 0, w, h); outlineRadius = r }
    
    private fun updateVisualRootStatic(root: FrameLayout) { 
        val winW = if (currentStage == IslandStage.STAGE1_IDLE) dp(getTargetWidth(currentStage)) else -1
        val winH = dp(getTargetHeight(currentStage) + 40)
        visualParams?.apply {
            width = winW
            height = winH
            y = dp(AppSettings.getIslandYDp(this@HyperAccessibilityService))
            flags = WINDOW_FLAGS_INTERACTIVE
        }
        try { windowManager?.updateViewLayout(root, visualParams) } catch (_: Exception) {} 
    }

    private fun updateTouchWindow(widthPx: Int, heightPx: Int) { 
        val touchH = if (currentStage == IslandStage.STAGE1_IDLE) dp(25) else heightPx
        touchParams?.apply {
            width = widthPx
            height = touchH
            x = dp(AppSettings.getIslandXDp(this@HyperAccessibilityService))
            y = dp(AppSettings.getIslandYDp(this@HyperAccessibilityService))
            flags = WINDOW_FLAGS_TOUCH
        }
        try { windowManager?.updateViewLayout(touchView!!, touchParams) } catch (_: Exception) {} 
    }

    private fun hideIslandInternal() { 
        morphAnimator?.cancel(); autoCollapseRunnable?.let { mainHandler.removeCallbacks(it) }; notificationQueue.clear(); isProcessingQueue = false
        try { windowManager?.removeViewImmediate(touchView!!); windowManager?.removeViewImmediate(visualRoot!!) } catch (_: Exception) {}
        visualRoot = null; touchView = null; currentStage = IslandStage.STAGE1_IDLE 
    }
    
    private fun createVisualParams() = WindowManager.LayoutParams(
        dp(AppSettings.getIslandWidthDp(this)), 
        dp(AppSettings.getIslandHeightDp(this) + 40), 
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, 
        WINDOW_FLAGS_INTERACTIVE, 
        PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(AppSettings.getIslandYDp(this@HyperAccessibilityService)); if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = 1; title = "HyperIslandProVisual" }

    private fun createTouchParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, dp(25), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WINDOW_FLAGS_TOUCH, PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; x = dp(AppSettings.getIslandXDp(this@HyperAccessibilityService)) ; y = dp(AppSettings.getIslandYDp(this@HyperAccessibilityService)); if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = 1; title = "HyperIslandProTouch" }

    private fun createIslandBackground(r: Float) = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(Color.BLACK); cornerRadius = r }
    private fun loadAppIcon(pkg: String) = try { packageManager.getApplicationIcon(pkg) } catch (_: Exception) { null }
    private fun getAppName(pkg: String) = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
    private fun formatNotificationTime(t: Long) = if (t <= 0L || System.currentTimeMillis() - t < 60000L) " • now" else " • " + SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(t))
    private fun lerp(s: Int, e: Int, p: Float) = (s + ((e - s) * p)).roundToInt()
    private fun lerp(s: Float, e: Float, p: Float) = s + ((e - s) * p)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun Animator.addListener(onEnd: () -> Unit) {
        addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) = onEnd()
        })
    }

    override fun onDestroy() { hideIslandInternal(); if (instance === this) instance = null; super.onDestroy() }
}
