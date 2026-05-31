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
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.hyperisland.pro.core.AppSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class HyperAccessibilityService : AccessibilityService() {

    private enum class IslandStage { STAGE1_IDLE, STAGE2_PING, STAGE3_FULL }
    private enum class ExpandReason { MANUAL_USER, AUTO_NOTIFICATION }
    
    private data class DisplayText(val appName: String, val title: String, val message: String)

    companion object {
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
        fun showNotificationFromApp(context: Context, packageName: String, appName: String, title: String, message: String, postTime: Long, contentIntent: PendingIntent?) = 
            instance?.apply { postNotificationEvent("NotificationListener", packageName, appName, title, message, postTime, contentIntent) } != null
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val fluidInterpolator = PathInterpolator(0.2f, 1f, 0.2f, 1f) // Ultra-premium curve
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
        val eventText = event.text?.mapNotNull { it?.toString() }?.filter { it.isNotBlank() } ?: return
        if (eventText.isEmpty()) return
        val appName = getAppName(pkg)
        val (t, m) = if (eventText.size >= 2) eventText[0] to eventText.drop(1).joinToString(" • ") else appName to eventText[0]
        postNotificationEvent("AccessibilityFallback", pkg, appName, t, m, System.currentTimeMillis(), null)
    }

    private fun postShowIsland() = mainHandler.post { showIslandInternal() }
    private fun postHideIsland() = mainHandler.post { hideIslandInternal() }
    private fun postUpdateIsland() = mainHandler.post { if (visualRoot == null) showIslandInternal() else updateAllToCurrentState() }
    private fun postExpandIsland() = mainHandler.post { setStageAnimated(IslandStage.STAGE3_FULL, ExpandReason.MANUAL_USER) }
    private fun postCollapseIsland() = mainHandler.post { setStageAnimated(IslandStage.STAGE1_IDLE, expandReason) }
    private fun postToggleExpanded() = mainHandler.post { if (notificationMode && currentStage == IslandStage.STAGE3_FULL) openCurrentNotification() else setStageAnimated(if (currentStage == IslandStage.STAGE3_FULL) IslandStage.STAGE1_IDLE else IslandStage.STAGE3_FULL, ExpandReason.MANUAL_USER) }

    private fun postNotificationEvent(source: String, pkg: String, appName: String, t: String, m: String, time: Long, intent: PendingIntent?) {
        mainHandler.post {
            if (!AppSettings.isIslandEnabled(this)) return@post
            val now = System.currentTimeMillis()
            if (source == "AccessibilityFallback" && now - lastPrimaryEventTime < 1500L) return@post
            if (source == "NotificationListener") lastPrimaryEventTime = now
            if (currentStage == IslandStage.STAGE3_FULL && t.isBlank() && m.isBlank()) return@post
            
            autoCollapseRunnable?.let { mainHandler.removeCallbacks(it) }
            val disp = buildDisplayText(appName, t, m)
            val print = "$pkg|${disp.title}|${disp.message}"
            if (print == lastIslandFingerprint && now - lastIslandFingerprintTime < 1000L) { scheduleAutoCollapse(); return@post }
            lastIslandFingerprint = print; lastIslandFingerprintTime = now

            if (visualRoot == null) showIslandInternal()
            notificationMode = true; currentPendingIntent = intent; currentPackageName = pkg
            appIconView?.setImageDrawable(loadAppIcon(pkg))
            appNameTimeText?.text = "${disp.appName} • ${formatNotificationTime(time)}"
            titleText?.text = disp.title; messageText?.text = disp.message
            titleText?.visibility = if (disp.title.isBlank()) View.GONE else View.VISIBLE
            messageText?.visibility = if (disp.message.isBlank()) View.GONE else View.VISIBLE

            visualRoot?.requestLayout(); visualRoot?.invalidate()

            if (currentStage == IslandStage.STAGE1_IDLE) {
                triggerFluidExpansion() // START FLUID PIPELINE
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
        updateVisualRootStatic(visualRoot!!)

        // STAGE 1 TO 2: Quick Elastic Pop (120ms)
        val pingAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 120L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                val w = lerp(startW, pingW, t)
                updateIslandLayout(w, initialH, startR)
            }
        }

        // STAGE 2 TO 3: Smooth Card Expansion (360ms)
        val expandAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 360L
            interpolator = fluidInterpolator
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                val w = lerp(pingW, targetW, t)
                val h = lerp(initialH, targetH, t)
                val r = lerp(startR, targetR, t)
                updateIslandLayout(w, h, r)
                contentContainer?.visibility = View.VISIBLE
                contentContainer?.alpha = t
            }
        }

        morphAnimator = AnimatorSet().apply {
            playSequentially(pingAnimator, expandAnimator)
            start()
        }
    }

    private fun updateIslandLayout(w: Int, h: Int, r: Float) {
        val child = islandLayoutParams ?: return
        child.width = w; child.height = h
        islandBackground?.cornerRadius = r
        updateOutlineForIsland(w, h, r)
        islandView?.layoutParams = child
        visualRoot?.invalidateOutline()
        updateTouchWindow(w, h)
    }

    private fun setStageAnimated(targetStage: IslandStage, reason: ExpandReason) {
        if (currentStage == targetStage && morphAnimator?.isRunning == true) return
        if (targetStage == IslandStage.STAGE3_FULL && reason == ExpandReason.AUTO_NOTIFICATION) {
            triggerFluidExpansion()
            return
        }
        
        val root = visualRoot ?: return
        val child = islandLayoutParams ?: return
        morphAnimator?.cancel()
        
        val startW = child.width; val startH = child.height
        val startR = islandBackground?.cornerRadius ?: dp(AppSettings.getIslandHeightDp(this)/2).toFloat()
        
        currentStage = targetStage
        expandReason = reason
        val targetW = dp(getTargetWidth(targetStage)); val targetH = dp(getTargetHeight(targetStage))
        val targetR = getTargetRadius(targetStage).toFloat()

        updateTouchWindow(targetW, targetH); updateVisualRootStatic(root)

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (targetStage == IslandStage.STAGE1_IDLE) 280L else 360L
            interpolator = fluidInterpolator
            addUpdateListener { anim ->
                val t = anim.animatedValue as Float
                val w = lerp(startW, targetW, t); val h = lerp(startH, targetH, t)
                val r = lerp(startR, targetR, t)
                updateIslandLayout(w, h, r)
                if (notificationMode) {
                    if (targetStage == IslandStage.STAGE1_IDLE) contentContainer?.alpha = (1f - (t / 0.3f)).coerceIn(0f, 1f)
                }
            }
            addListener(object : android.animation.Animator.AnimatorListener {
                override fun onAnimationStart(a: android.animation.Animator) {}
                override fun onAnimationEnd(a: android.animation.Animator) { 
                    if (targetStage == IslandStage.STAGE1_IDLE) { contentContainer?.visibility = View.GONE; notificationMode = false }
                    updateOutsideWatcherForState() 
                }
                override fun onAnimationCancel(a: android.animation.Animator) {}
                override fun onAnimationRepeat(a: android.animation.Animator) {}
            })
        }
        morphAnimator = animator
        animator.start()
    }

    private fun getTargetWidth(s: IslandStage) = when(s) { IslandStage.STAGE1_IDLE -> AppSettings.getIslandWidthDp(this); IslandStage.STAGE2_PING -> AppSettings.getIslandStage2WidthDp(this); IslandStage.STAGE3_FULL -> AppSettings.getIslandExpandedWidthDp(this) }
    private fun getTargetHeight(s: IslandStage) = when(s) { IslandStage.STAGE1_IDLE -> AppSettings.getIslandHeightDp(this); IslandStage.STAGE2_PING -> AppSettings.getIslandHeightDp(this) + 4; IslandStage.STAGE3_FULL -> AppSettings.getIslandExpandedHeightDp(this) }
    private fun getTargetRadius(s: IslandStage) = dp(getTargetHeight(s) / if (s == IslandStage.STAGE3_FULL) 3 else 2)

    private fun showIslandInternal() {
        if (visualRoot != null) return
        val w = dp(AppSettings.getIslandWidthDp(this)); val h = dp(AppSettings.getIslandHeightDp(this))
        islandBackground = createIslandBackground(dp(h/2).toFloat())
        visualRoot = FrameLayout(this).apply { setBackgroundColor(0); outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(outlineRect, outlineRadius) } }; clipToOutline = true }
        islandView = FrameLayout(this).apply { background = islandBackground; clipToOutline = true; elevation = 0f }
        contentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE; setPadding(dp(18), dp(12), dp(18), dp(12))
            appIconView = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
            appNameTimeText = TextView(context).apply { setTextColor(Color.rgb(0, 150, 255)); textSize = 12f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            titleText = TextView(context).apply { setTextColor(Color.WHITE); textSize = 16f; typeface = Typeface.DEFAULT_BOLD; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            messageText = TextView(context).apply { setTextColor(Color.rgb(210, 210, 216)); textSize = 13f; maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
            col.addView(appNameTimeText); col.addView(titleText); col.addView(messageText); addView(appIconView, LinearLayout.LayoutParams(dp(38), dp(38))); addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        }
        islandView?.addView(contentContainer, FrameLayout.LayoutParams(-1, -1))
        islandLayoutParams = FrameLayout.LayoutParams(w, h).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL }
        visualRoot?.addView(islandView, islandLayoutParams)
        touchView = FrameLayout(this).apply { isClickable = true; setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_DOWN) { touchStartY = e.rawY; touchStartX = e.rawX } else if (e.action == MotionEvent.ACTION_UP) { if (e.rawY - touchStartY < -dp(24)) postCollapseIsland() else postToggleExpanded() }; true } }
        visualParams = createVisualParams(); touchParams = createTouchParams(w, h)
        updateOutlineForIsland(w, h, dp(h/2).toFloat())
        try { windowManager?.addView(visualRoot, visualParams); windowManager?.addView(touchView, touchParams) } catch (_: Exception) {}
    }

    private fun updateAllToCurrentState() {
        val child = islandLayoutParams ?: return
        val w = dp(getTargetWidth(currentStage)); val h = dp(getTargetHeight(currentStage)); val r = getTargetRadius(currentStage).toFloat()
        updateIslandLayout(w, h, r); updateVisualRootStatic(visualRoot!!); updateTouchWindow(w, h)
    }

    private fun scheduleAutoCollapse() { autoCollapseRunnable?.let { mainHandler.removeCallbacks(it) }; autoCollapseRunnable = Runnable { if (notificationMode) setStageAnimated(IslandStage.STAGE1_IDLE, ExpandReason.AUTO_NOTIFICATION) }.also { mainHandler.postDelayed(it, 5200L) } }
    private fun openCurrentNotification() { try { currentPendingIntent?.send() ?: currentPackageName?.let { pkg -> packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it.addFlags(268435456)) } } } catch (_: Exception) {}; setStageAnimated(IslandStage.STAGE1_IDLE, ExpandReason.AUTO_NOTIFICATION) }
    private fun updateOutsideWatcherForState() { if (currentStage == IslandStage.STAGE3_FULL && expandReason == ExpandReason.MANUAL_USER) ensureOutsideWatcher() else removeOutsideWatcher() }
    private fun ensureOutsideWatcher() { if (outsideWatcherView != null) return; outsideWatcherView = FrameLayout(this).apply { setBackgroundColor(0); setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_DOWN || e.action == MotionEvent.ACTION_OUTSIDE) postCollapseIsland(); false } }; try { windowManager?.addView(outsideWatcherView, WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, 16777216 or 8 or 4096 or 512 or 256, -3).apply { gravity = Gravity.TOP or Gravity.START; title = "HyperIslandProOutside" }) } catch (_: Exception) {} }
    private fun removeOutsideWatcher() { try { windowManager?.removeViewImmediate(outsideWatcherView!!) } catch (_: Exception) {}; outsideWatcherView = null }
    private fun updateOutlineForIsland(w: Int, h: Int, r: Float) { val rootW = resources.displayMetrics.widthPixels; val left = ((rootW - w) / 2) + dp(AppSettings.getIslandXDp(this)); outlineRect.set(left, 0, left + w, h); outlineRadius = r }
    private fun updateVisualRootStatic(root: FrameLayout) { visualParams?.apply { height = dp(AppSettings.getIslandExpandedHeightDp(this@HyperAccessibilityService) + 20); y = dp(AppSettings.getIslandYDp(this@HyperAccessibilityService)) }; try { windowManager?.updateViewLayout(root, visualParams) } catch (_: Exception) {} }
    private fun updateTouchWindow(w: Int, h: Int) { val touchH = if (currentStage == IslandStage.STAGE1_IDLE) dp(20) else h; try { windowManager?.updateViewLayout(touchView!!, touchParams!!.apply { width = w; height = touchH; x = dp(AppSettings.getIslandXDp(this@HyperAccessibilityService)); y = dp(AppSettings.getIslandYDp(this@HyperAccessibilityService)) }) } catch (_: Exception) {} }
    private fun hideIslandInternal() { morphAnimator?.cancel(); autoCollapseRunnable?.let { mainHandler.removeCallbacks(it) }; removeOutsideWatcher(); try { windowManager?.removeViewImmediate(touchView!!); windowManager?.removeViewImmediate(visualRoot!!) } catch (_: Exception) {}; visualRoot = null; touchView = null; currentStage = IslandStage.STAGE1_IDLE }
    private fun createVisualParams() = WindowManager.LayoutParams(-1, dp(AppSettings.getIslandExpandedHeightDp(this) + 20), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, 16777216 or 8 or 512 or 256 or 65536 or 131072, -3).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(AppSettings.getIslandYDp(this@HyperAccessibilityService)); if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = 1; title = "HyperIslandProVisual" }
    private fun createTouchParams(w: Int, h: Int) = WindowManager.LayoutParams(w, dp(20), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, 16777216 or 8 or 512 or 256 or 65536 or 131072, -3).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; x = dp(AppSettings.getIslandXDp(this@HyperAccessibilityService)); y = dp(AppSettings.getIslandYDp(this@HyperAccessibilityService)); if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = 1; title = "HyperIslandProTouch" }
    private fun createOutsideWatcherParams() = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, 16777216 or 8 or 4096 or 512 or 256, -3).apply { gravity = Gravity.TOP or Gravity.START; title = "HyperIslandProOutside" }
    private fun createIslandBackground(r: Float) = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(Color.BLACK); cornerRadius = r }
    private fun loadAppIcon(pkg: String) = try { packageManager.getApplicationIcon(pkg) } catch (_: Exception) { null }
    private fun getAppName(pkg: String) = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
    private fun formatNotificationTime(t: Long) = if (t <= 0L || System.currentTimeMillis() - t < 60000L) "now" else SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(t))
    private fun buildDisplayText(appName: String, t: String, m: String) = DisplayText(appName.trim().ifBlank { "App" }, t.trim().ifBlank { m.trim() }, if (t.trim().equals(appName.trim(), true)) "" else m.trim())
    private fun lerp(s: Int, e: Int, p: Float) = (s + ((e - s) * p)).roundToInt()
    private fun lerp(s: Float, e: Float, p: Float) = s + ((e - s) * p)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    override fun onDestroy() { hideIslandInternal(); if (instance === this) instance = null; super.onDestroy() }
}
