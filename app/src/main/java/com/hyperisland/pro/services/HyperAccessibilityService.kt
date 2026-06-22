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
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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

    private var gridRoot: LinearLayout? = null
    private var iconSection: FrameLayout? = null
    private var contentSection: LinearLayout? = null
    
    private var appIconView: ImageView? = null
    private var headerLine: TextView? = null
    private var titleText: TextView? = null
    private var messageText: TextView? = null
    private var footerActions: LinearLayout? = null
    private var actionScroll: HorizontalScrollView? = null

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

        val textItems = event.text?.mapNotNull { it?.toString()?.trim() }?.filter { it.isNotBlank() } ?: emptyList()
        if (textItems.isEmpty()) return

        val appName = getAppName(pkg)
        val (title, message) = if (textItems.size >= 2) {
            textItems[0] to textItems.drop(1).joinToString(" • ")
        } else {
            appName to textItems[0]
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

    private fun postShowIsland() = mainHandler.post { showIslandInternal() }
    private fun postHideIsland() = mainHandler.post { hideIslandInternal() }
    private fun postUpdateIsland() = mainHandler.post { if (visualRoot == null) showIslandInternal() else updateAllToCurrentState() }
    private fun postExpandIsland() = mainHandler.post { setStageAnimated(IslandStage.STAGE3_FULL, ExpandReason.MANUAL_USER) }
    private fun postCollapseIsland() = mainHandler.post { setStageAnimated(IslandStage.STAGE1_IDLE, expandReason) }

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

            val display = buildDisplayText(appName, title, message)
            if (display.title.isBlank() && display.message.isBlank()) return@post

            val fingerprint = "$packageName|${display.title}|${display.message}"
            if (fingerprint == lastIslandFingerprint && now - lastIslandFingerprintTime < 1000L) {
                scheduleAutoCollapse() 
                return@post
            }

            lastIslandFingerprint = fingerprint
            lastIslandFingerprintTime = now

            if (visualRoot == null) showIslandInternal()

            val model = NotificationModel(packageName, appName, title, message, postTime, contentIntent)
            notificationQueue.add(model)
            
            if (!isProcessingQueue) processNextInQueue() else scheduleAutoCollapse()
        }
    }

    private fun processNextInQueue() {
        val next = notificationQueue.poll() ?: run { isProcessingQueue = false; return }
        isProcessingQueue = true
        notificationMode = true
        
        if (currentStage == IslandStage.STAGE1_IDLE) {
            updateNotificationContent(next)
            triggerFluidExpansion()
            scheduleAutoCollapse()
        } else {
            playFluidTransitionAnimation(next)
        }
    }

    private fun playFluidTransitionAnimation(next: NotificationModel) {
        val exit = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 200L
            interpolator = AccelerateInterpolator()
            addUpdateListener { 
                val t = it.animatedValue as Float
                gridRoot?.alpha = 1f - t
                gridRoot?.translationY = t * 20f
            }
        }
        val entry = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                gridRoot?.alpha = t
                gridRoot?.translationY = -20f * (1f - t)
            }
        }
        exit.addListener(object : android.animation.Animator.AnimatorListener {
            override fun onAnimationStart(a: Animator) {}
            override fun onAnimationCancel(a: Animator) {}
            override fun onAnimationRepeat(a: Animator) {}
            override fun onAnimationEnd(a: Animator) {
                updateNotificationContent(next)
                entry.start()
            }
        })
        entry.addListener(object : android.animation.Animator.AnimatorListener {
            override fun onAnimationEnd(a: Animator) { scheduleAutoCollapse() }
            override fun onAnimationStart(a: Animator) {}
            override fun onAnimationCancel(a: Animator) {}
            override fun onAnimationRepeat(a: Animator) {}
        })
        exit.start()
    }

    private fun updateNotificationContent(model: NotificationModel) {
        currentPendingIntent = model.contentIntent
        currentPackageName = model.packageName
        
        val display = buildDisplayText(model.appName, model.title, model.message)
        appIconView?.setImageDrawable(loadAppIcon(model.packageName))
        headerLine?.text = "${display.appName} • ${formatNotificationTime(model.postTime)}"
        titleText?.text = display.title
        messageText?.text = display.message
        
        titleText?.visibility = if (display.title.isBlank()) View.GONE else View.VISIBLE
        messageText?.visibility = if (display.message.isBlank()) View.GONE else View.VISIBLE
        
        setupActionTiles(model.packageName)
        forceRegionUpdate()
    }

    private fun setupActionTiles(pkg: String) {
        footerActions?.removeAllViews()
        
        val actions = mutableListOf<String>()
        when {
            pkg.contains("whatsapp") || pkg.contains("messaging") -> actions.addAll(listOf("Reply", "Mark as Read"))
            pkg.contains("music") || pkg.contains("spotify") -> actions.addAll(listOf("Previous", "Pause", "Next"))
            else -> actions.add("Dismiss")
        }

        val totalWidthDp = AppSettings.getIslandExpandedWidthDp(this) - 36
        val availableWidthDp = (totalWidthDp * 0.8).toInt()

        val btnWidth = when (actions.size) {
            1 -> (availableWidthDp * 0.7).toInt()
            2 -> (availableWidthDp * 0.46).toInt()
            3 -> (availableWidthDp * 0.31).toInt()
            else -> 90
        }

        actions.forEach { action ->
            val btn = TextView(this).apply {
                text = action
                setTextColor(Color.WHITE)
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dp(12), 0, dp(12), 0)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(Color.parseColor("#222222"))
                    cornerRadius = dp(16).toFloat()
                    setStroke(dp(1), Color.parseColor("#444444"))
                }
                isClickable = true
                setOnClickListener { 
                    val oldText = text
                    text = "✓ $action"
                    setTextColor(Color.GREEN)
                    postDelayed({
                        if (action == "Dismiss") postCollapseIsland()
                        else { text = oldText; setTextColor(Color.WHITE) }
                    }, 600)
                }
            }
            val lp = LinearLayout.LayoutParams(dp(btnWidth), dp(32))
            lp.marginStart = dp(6)
            footerActions?.addView(btn, lp)
        }
    }

    private fun triggerFluidExpansion() {
        morphAnimator?.cancel()
        val startW = dp(AppSettings.getIslandWidthDp(this))
        val pingW = dp(AppSettings.getIslandStage2WidthDp(this))
        val targetW = dp(AppSettings.getIslandExpandedWidthDp(this))
        val initialH = dp(AppSettings.getIslandHeightDp(this))
        val targetH = dp(AppSettings.getIslandExpandedHeightDp(this))
        val startR = dp(AppSettings.getIslandHeightDp(this) / 2).toFloat()
        val targetR = expandedCornerRadiusPx().toFloat()

        currentStage = IslandStage.STAGE3_FULL
        expandReason = ExpandReason.AUTO_NOTIFICATION
        
        val ping = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 100L
            addUpdateListener { updateIslandLayout(lerp(startW, pingW, it.animatedValue as Float), initialH, startR) }
        }
        val expand = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 350L
            interpolator = fluidInterpolator
            addUpdateListener {
                val t = it.animatedValue as Float
                updateIslandLayout(lerp(pingW, targetW, t), lerp(initialH, targetH, t), lerp(startR, targetR, t))
                gridRoot?.alpha = t
                gridRoot?.visibility = View.VISIBLE
            }
        }
        morphAnimator = AnimatorSet().apply { playSequentially(ping, expand); start() }
    }

    private fun setStageAnimated(target: IslandStage, reason: ExpandReason) {
        if (currentStage == target && morphAnimator?.isRunning == true) return
        if (target == IslandStage.STAGE3_FULL && reason == ExpandReason.AUTO_NOTIFICATION) {
            triggerFluidExpansion(); return
        }
        
        morphAnimator?.cancel()
        val startW = islandLayoutParams?.width ?: dp(AppSettings.getIslandWidthDp(this))
        val startH = islandLayoutParams?.height ?: dp(AppSettings.getIslandHeightDp(this))
        val startR = islandBackground?.cornerRadius ?: dp(startH / 2).toFloat()

        currentStage = target; expandReason = reason
        val targetW = dp(getTargetWidth(target)); val targetH = dp(getTargetHeight(target)); val targetR = getTargetRadius(target).toFloat()

        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (target == IslandStage.STAGE1_IDLE) 250L else 350L
            interpolator = fluidInterpolator
            addUpdateListener {
                val t = it.animatedValue as Float
                updateIslandLayout(lerp(startW, targetW, t), lerp(startH, targetH, t), lerp(startR, targetR, t))
                if (target == IslandStage.STAGE1_IDLE) gridRoot?.alpha = 1f - t
            }
            addListener(object : android.animation.Animator.AnimatorListener {
                override fun onAnimationStart(a: Animator) {}
                override fun onAnimationRepeat(a: Animator) {}
                override fun onAnimationCancel(a: Animator) {}
                override fun onAnimationEnd(a: Animator) {
                    if (target == IslandStage.STAGE1_IDLE) { gridRoot?.visibility = View.GONE; notificationMode = false }
                    forceRegionUpdate()
                }
            })
        }
        morphAnimator = anim; anim.start()
    }

    private fun updateIslandLayout(w: Int, h: Int, r: Float) {
        islandLayoutParams?.width = w; islandLayoutParams?.height = h
        islandBackground?.cornerRadius = r
        updateOutlineForIsland(w, h, r)
        islandView?.layoutParams = islandLayoutParams
        visualRoot?.invalidateOutline()
        forceRegionUpdate()
    }

    private fun getTargetWidth(s: IslandStage) = when(s) {
        IslandStage.STAGE1_IDLE -> AppSettings.getIslandWidthDp(this)
        IslandStage.STAGE2_PING -> AppSettings.getIslandStage2WidthDp(this)
        IslandStage.STAGE3_FULL -> AppSettings.getIslandExpandedWidthDp(this)
    }

    private fun getTargetHeight(s: IslandStage) = when(s) {
        IslandStage.STAGE1_IDLE -> AppSettings.getIslandHeightDp(this)
        IslandStage.STAGE2_PING -> AppSettings.getIslandHeightDp(this) + 4
        IslandStage.STAGE3_FULL -> AppSettings.getIslandExpandedHeightDp(this)
    }

    private fun getTargetRadius(s: IslandStage) = dp(getTargetHeight(s) / if (s == IslandStage.STAGE3_FULL) 3 else 2)

    private fun scheduleAutoCollapse() {
        autoCollapseRunnable?.let { mainHandler.removeCallbacks(it) }
        val size = notificationQueue.size
        val delay = when {
            size == 0 -> 5200L
            size in 1..5 -> 3000L
            size in 6..19 -> 1500L
            size in 20..34 -> 800L
            else -> 250L
        }
        autoCollapseRunnable = Runnable {
            if (notificationQueue.isNotEmpty()) processNextInQueue()
            else setStageAnimated(IslandStage.STAGE1_IDLE, ExpandReason.AUTO_NOTIFICATION)
        }
        mainHandler.postDelayed(autoCollapseRunnable!!, delay)
    }

    private fun openCurrentNotification() {
        try { currentPendingIntent?.send() ?: currentPackageName?.let { pkg -> packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } } } catch (_: Exception) {}
        postCollapseIsland()
    }

    private fun showIslandInternal() {
        hideIslandInternal()
        visualParams = createVisualParams()
        visualRoot = FrameLayout(this).apply {
            setBackgroundColor(0)
            viewTreeObserver.addOnComputeInternalInsetsListener { insets ->
                insets.contentInsets.setEmpty(); insets.visibleInsets.setEmpty(); insets.touchableRegion.setEmpty()
                try { insets.javaClass.getMethod("setTouchableInsets", Int::class.javaPrimitiveType).invoke(insets, 3) } catch (_: Exception) {}
                val rect = Rect(); islandView?.getGlobalVisibleRect(rect)
                if (!rect.isEmpty) insets.touchableRegion.set(rect)
            }
        }
        islandBackground = createIslandBackground(dp(AppSettings.getIslandHeightDp(this) / 2).toFloat())
        islandView = FrameLayout(this).apply {
            background = islandBackground; clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(outlineRect, outlineRadius) } }
            gridRoot = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)); visibility = View.GONE; alpha = 0f
                iconSection = FrameLayout(context).apply {
                    appIconView = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                    addView(appIconView, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER))
                }
                contentSection = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0)
                    headerLine = TextView(context).apply { setTextColor(Color.rgb(0, 150, 255)); textSize = 11f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
                    titleText = TextView(context).apply { setTextColor(Color.WHITE); textSize = 15f; typeface = Typeface.DEFAULT_BOLD; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
                    messageText = TextView(context).apply { setTextColor(Color.rgb(200, 200, 200)); textSize = 13f; maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
                    actionScroll = HorizontalScrollView(context).apply {
                        isHorizontalScrollBarEnabled = false
                        footerActions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.START }
                        addView(footerActions)
                    }
                    addView(headerLine); addView(titleText); addView(messageText)
                    addView(actionScroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
                }
                addView(iconSection, LinearLayout.LayoutParams(0, -2, 0.2f)); addView(contentSection, LinearLayout.LayoutParams(0, -2, 0.8f))
            }
            addView(gridRoot, FrameLayout.LayoutParams(-1, -1))
            setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_UP) { if (e.rawY - touchStartY < -dp(24)) postCollapseIsland() else postToggleExpanded() } else if (e.action == MotionEvent.ACTION_DOWN) { touchStartY = e.rawY }; true }
        }
        islandLayoutParams = FrameLayout.LayoutParams(dp(AppSettings.getIslandWidthDp(this)), dp(AppSettings.getIslandHeightDp(this))).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL }
        visualRoot?.addView(islandView, islandLayoutParams)
        updateOutlineForIsland(dp(AppSettings.getIslandWidthDp(this)), dp(AppSettings.getIslandHeightDp(this)), dp(AppSettings.getIslandHeightDp(this) / 2).toFloat())
        try { windowManager?.addView(visualRoot, visualParams) } catch (_: Exception) { hideIslandInternal() }
    }

    private fun forceRegionUpdate() {
        visualRoot?.let { root -> root.requestLayout(); root.invalidate(); root.post { root.requestLayout(); root.parent?.requestLayout() } }
    }

    private fun updateAllToCurrentState() {
        updateIslandLayout(dp(getTargetWidth(currentStage)), dp(getTargetHeight(currentStage)), getTargetRadius(currentStage).toFloat())
    }

    private fun hideIslandInternal() {
        morphAnimator?.cancel(); autoCollapseRunnable?.let { mainHandler.removeCallbacks(it) }
        try { windowManager?.removeViewImmediate(visualRoot!!) } catch (_: Exception) {}
        visualRoot = null; islandView = null; currentStage = IslandStage.STAGE1_IDLE
    }

    private fun createVisualParams() = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, 16777216 or 8 or 512 or 256 or 65536 or 131072 or 4096, -3).apply {
        gravity = Gravity.TOP; y = 0; if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = 1; title = "HyperIslandProVisual"
    }

    private fun createIslandBackground(r: Float) = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(Color.BLACK); cornerRadius = r }
    private fun loadAppIcon(pkg: String) = try { packageManager.getApplicationIcon(pkg) } catch (_: Exception) { null }
    private fun getAppName(pkg: String) = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
    private fun formatNotificationTime(t: Long) = if (t <= 0L || System.currentTimeMillis() - t < 60000L) "now" else SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(t))
    private fun buildDisplayText(appName: String, t: String, m: String): DisplayText {
        val a = appName.trim().ifBlank { "App" }
        var ti = t.trim(); var me = m.trim()
        if (ti.equals(a, true)) { ti = me; me = "" }
        if (ti.isBlank() && me.isNotBlank()) { ti = me; me = "" }
        return DisplayText(appName = a, title = ti, message = me)
    }
    private fun updateOutlineForIsland(w: Int, h: Int, r: Float) {
        val left = ((resources.displayMetrics.widthPixels - w) / 2) + dp(AppSettings.getIslandXDp(this))
        val top = dp(AppSettings.getIslandYDp(this))
        outlineRect.set(left, top, left + w, top + h); outlineRadius = r; islandLayoutParams?.topMargin = top
    }
    private fun expandedCornerRadiusPx() = dp((AppSettings.getIslandExpandedHeightDp(this) / 3).coerceIn(34, 46))
    private fun lerp(s: Int, e: Int, p: Float) = (s + ((e - s) * p)).roundToInt()
    private fun lerp(s: Float, e: Float, p: Float) = s + ((e - s) * p)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    override fun onDestroy() { hideIslandInternal(); if (instance === this) instance = null; super.onDestroy() }
}
