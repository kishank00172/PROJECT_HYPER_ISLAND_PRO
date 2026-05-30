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

    private enum class IslandStage {
        STAGE1_IDLE,    // Normal Pill
        STAGE2_PING,    // Slightly wide, icon only
        STAGE3_FULL     // Fully expanded card
    }

    private enum class ExpandReason {
        MANUAL_USER,
        AUTO_NOTIFICATION
    }

    private data class DisplayText(
        val appName: String,
        val title: String,
        val message: String
    )

    companion object {
        @Volatile
        private var instance: HyperAccessibilityService? = null
        @Volatile
        var lastAccessibilityDebugMessage: String = "Accessibility fallback waiting"
            private set

        fun isConnected() = instance != null
        fun showIslandFromApp(context: Context) = instance?.apply { postShowIsland() } != null
        fun hideIslandFromApp(context: Context? = null) = instance?.apply { postHideIsland() } != null
        fun refreshIslandFromApp(context: Context) = instance?.apply { postUpdateIsland() } != null
        fun expandIslandFromApp(context: Context) = instance?.apply { postExpandIsland() } != null
        fun collapseIslandFromApp(context: Context) = instance?.apply { postCollapseIsland() } != null
        fun toggleExpandFromApp(context: Context) = instance?.apply { postToggleExpanded() } != null

        fun showNotificationFromApp(context: Context, packageName: String, appName: String, title: String, message: String, postTime: Long, contentIntent: PendingIntent?): Boolean {
            return instance?.apply { postNotificationEvent("NotificationListener", packageName, appName, title, message, postTime, contentIntent) } != null
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
        if (AppSettings.isIslandEnabled(this) && AppSettings.getOverlayEngine(this) == AppSettings.ENGINE_ACCESSIBILITY) postShowIsland()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) handleAccessibilityNotificationEvent(event)
    }

    override fun onInterrupt() = Unit

    private fun handleAccessibilityNotificationEvent(event: AccessibilityEvent) {
        if (!AppSettings.isIslandEnabled(this)) return
        val pkg = event.packageName?.toString().orEmpty()
        if (pkg.isBlank() || pkg == packageName) return
        val eventText = event.text?.mapNotNull { it?.toString() }?.filter { it.isNotBlank() } ?: emptyList()
        if (eventText.isEmpty()) return
        val appName = getAppName(pkg)
        val (title, message) = if (eventText.size >= 2) eventText[0] to eventText.drop(1).joinToString(" • ") else appName to eventText[0]
        postNotificationEvent("AccessibilityFallback", pkg, appName, title, message, System.currentTimeMillis(), null)
    }

    private fun postShowIsland() = mainHandler.post { showIslandInternal() }
    private fun postHideIsland() = mainHandler.post { hideIslandInternal(); AppSettings.setIslandEnabled(this, false) }
    private fun postUpdateIsland() = mainHandler.post { if (visualRoot == null) showIslandInternal() else updateAllToCurrentState() }
    private fun postExpandIsland() = mainHandler.post { setStageAnimated(IslandStage.STAGE3_FULL, ExpandReason.MANUAL_USER) }
    private fun postCollapseIsland() = mainHandler.post { setStageAnimated(IslandStage.STAGE1_IDLE, expandReason) }
    private fun postToggleExpanded() = mainHandler.post { if (notificationMode && currentStage == IslandStage.STAGE3_FULL) openCurrentNotification() else setStageAnimated(if (currentStage == IslandStage.STAGE3_FULL) IslandStage.STAGE1_IDLE else IslandStage.STAGE3_FULL, ExpandReason.MANUAL_USER) }

    private fun postNotificationEvent(source: String, packageName: String, appName: String, title: String, message: String, postTime: Long, contentIntent: PendingIntent?) {
        mainHandler.post {
            if (!AppSettings.isIslandEnabled(this)) return@post
            val now = System.currentTimeMillis()
            if (source == "AccessibilityFallback" && now - lastPrimaryEventTime < 1500L) return@post
            if (source == "NotificationListener") lastPrimaryEventTime = now
            if (currentStage == IslandStage.STAGE3_FULL && title.isBlank() && message.isBlank()) return@post
            
            autoCollapseRunnable?.let { mainHandler.removeCallbacks(it) }
            val display = buildDisplayText(appName, title, message)
            val fingerprint = "$packageName|${display.title}|${display.message}"
            if (fingerprint == lastIslandFingerprint && now - lastIslandFingerprintTime < 1000L) { scheduleAutoCollapse(); return@post }
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

            // START 3-STAGE SEQUENCE
            if (currentStage == IslandStage.STAGE1_IDLE) {
                // Morph to Stage 2 (Ping) first
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

    private fun setStageAnimated(targetStage: IslandStage, reason: ExpandReason) {
        if (currentStage == targetStage && morphAnimator?.isRunning != true) return
        val root = visualRoot ?: return
        val child = islandLayoutParams ?: return
        morphAnimator?.cancel()
        
        val startWidth = child.width
        val startHeight = child.height
        val startRadius = islandBackground?.cornerRadius ?: dp(AppSettings.getIslandHeightDp(this)/2).toFloat()
        
        currentStage = targetStage
        expandReason = reason
        
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
                child.width = lerp(startWidth, targetWidth, t)
                child.height = lerp(startHeight, targetHeight, t)
                val r = lerp(startRadius, targetRadius, t)
                islandBackground?.cornerRadius = r
                updateOutlineForIsland(child.width, child.height, r)
                
                if (notificationMode) {
                    if (targetStage == IslandStage.STAGE3_FULL) contentContainer?.alpha = ((t - 0.4f) / 0.6f).coerceIn(0f, 1f)
                    else if (targetStage == IslandStage.STAGE1_IDLE) contentContainer?.alpha = (1f - (t / 0.3f)).coerceIn(0f, 1f)
                }

                islandView?.layoutParams = child
                visualRoot?.invalidateOutline()
            }
            addListener(object : android.animation.Animator.AnimatorListener {
                override fun onAnimationStart(a: android.animation.Animator) { if (targetStage == IslandStage.STAGE3_FULL) contentContainer?.visibility = View.VISIBLE }
                override fun onAnimationEnd(a: android.animation.Animator) { if (targetStage == IslandStage.STAGE1_IDLE) { contentContainer?.visibility = View.GONE; notificationMode = false } }
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
        IslandStage.STAGE2_PING -> AppSettings.getIslandHeightDp(this) + 4 // Slightly taller for ping
        IslandStage.STAGE3_FULL -> AppSettings.getIslandExpandedHeightDp(this)
    }

    private fun getTargetRadius(stage: IslandStage) = dp(getTargetHeight(stage) / if (stage == IslandStage.STAGE3_FULL) 3 else 2)

    private fun showIslandInternal() {
        if (visualRoot != null) return
        val compactWidth = dp(AppSettings.getIslandWidthDp(this))
        val compactHeight = dp(AppSettings.getIslandHeightDp(this))
        islandBackground = createIslandBackground(dp(compactHeight/2).toFloat())
        
        visualRoot = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(outlineRect, outlineRadius) } }
            clipToOutline = true
        }
        islandView = FrameLayout(this).apply {
            background = islandBackground
            clipToOutline = true
        }
        contentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(18), dp(12), dp(18), dp(12))
            appIconView = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
            val textCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
            appNameTimeText = TextView(context).apply { setTextColor(Color.rgb(0, 150, 255)); textSize = 12f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            titleText = TextView(context).apply { setTextColor(Color.WHITE); textSize = 16f; typeface = Typeface.DEFAULT_BOLD; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
            messageText = TextView(context).apply { setTextColor(Color.rgb(210, 210, 216)); textSize = 13f; maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
            textCol.addView(appNameTimeText); textCol.addView(titleText); textCol.addView(messageText)
            addView(appIconView, LinearLayout.LayoutParams(dp(38), dp(38)))
            addView(textCol, LinearLayout.LayoutParams(0, -2, 1f))
        }
        islandView?.addView(contentContainer, FrameLayout.LayoutParams(-1, -1))
        islandLayoutParams = FrameLayout.LayoutParams(compactWidth, compactHeight).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL }
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
        visualParams = createVisualParams(); touchParams = createTouchParams(compactWidth, compactHeight)
        updateOutlineForIsland(compactWidth, compactHeight, dp(compactHeight/2).toFloat())
        try { windowManager?.addView(visualRoot, visualParams); windowManager?.addView(touchView, touchParams) } catch (_: Exception) {}
    }

    private fun updateAllToCurrentState() {
        val child = islandLayoutParams ?: return
        val w = dp(getTargetWidth(currentStage)); val h = dp(getTargetHeight(currentStage))
        val r = getTargetRadius(currentStage).toFloat()
        child.width = w; child.height = h
        islandBackground?.cornerRadius = r
        updateOutlineForIsland(w, h, r)
        try { windowManager?.updateViewLayout(visualRoot!!, visualParams!!.apply { y = dp(AppSettings.getIslandYDp(this@HyperAccessibilityService)) })
            islandView?.layoutParams = child; visualRoot?.invalidateOutline(); updateTouchWindow(w, h) } catch (_: Exception) {}
    }

    private fun scheduleAutoCollapse() { autoCollapseRunnable?.let { mainHandler.removeCallbacks(it) }; autoCollapseRunnable = Runnable { if (notificationMode) setStageAnimated(IslandStage.STAGE1_IDLE, ExpandReason.AUTO_NOTIFICATION) }.also { mainHandler.postDelayed(it, 5200L) } }
    private fun openCurrentNotification() { try { currentPendingIntent?.send() ?: currentPackageName?.let { pkg -> packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it.addFlags(268435456)) } } } catch (_: Exception) {}; setStageAnimated(IslandStage.STAGE1_IDLE, ExpandReason.AUTO_NOTIFICATION) }
    private fun updateOutsideWatcherForState() { if (currentStage == IslandStage.STAGE3_FULL && expandReason == ExpandReason.MANUAL_USER) ensureOutsideWatcher() else removeOutsideWatcher() }
    private fun ensureOutsideWatcher() { if (outsideWatcherView != null) return; outsideWatcherView = FrameLayout(this).apply { setBackgroundColor(0); setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_DOWN || e.action == MotionEvent.ACTION_OUTSIDE) postCollapseIsland(); false } }; try { windowManager?.addView(outsideWatcherView, createOutsideWatcherParams()) } catch (_: Exception) {} }
    private fun removeOutsideWatcher() { try { windowManager?.removeViewImmediate(outsideWatcherView!!) } catch (_: Exception) {}; outsideWatcherView = null }
    private fun updateOutlineForIsland(w: Int, h: Int, r: Float) { val rootW = resources.displayMetrics.widthPixels; val left = ((rootW - w) / 2) + dp(AppSettings.getIslandXDp(this)); outlineRect.set(left, 0, left + w, h); outlineRadius = r }
    private fun updateVisualRootStatic(root: FrameLayout) { try { windowManager?.updateViewLayout(root, visualParams!!.apply { y = dp(AppSettings.getIslandYDp(this@HyperAccessibilityService)) }) } catch (_: Exception) {} }
    private fun updateTouchWindow(w: Int, h: Int) { try { windowManager?.updateViewLayout(touchView!!, touchParams!!.apply { width = w; height = h; x = dp(AppSettings.getIslandXDp(this@HyperAccessibilityService)); y = dp(AppSettings.getIslandYDp(this@HyperAccessibilityService)) }) } catch (_: Exception) {} }
    private fun hideIslandInternal() { morphAnimator?.cancel(); autoCollapseRunnable?.let { mainHandler.removeCallbacks(it) }; removeOutsideWatcher(); try { windowManager?.removeViewImmediate(touchView!!); windowManager?.removeViewImmediate(visualRoot!!) } catch (_: Exception) {}; visualRoot = null; touchView = null; currentStage = IslandStage.STAGE1_IDLE }
    private fun createVisualParams() = WindowManager.LayoutParams(-1, dp(AppSettings.getIslandExpandedHeightDp(this)), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, 16777216 or 8 or 512 or 256 or 65536 or 131072, -3).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(AppSettings.getIslandYDp(this@HyperAccessibilityService)); if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = 1; title = "HyperIslandProVisual" }
    private fun createTouchParams(w: Int, h: Int) = WindowManager.LayoutParams(w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, 16777216 or 8 or 512 or 256 or 65536 or 131072, -3).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; x = dp(AppSettings.getIslandXDp(this@HyperAccessibilityService)); y = dp(AppSettings.getIslandYDp(this@HyperAccessibilityService)); if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = 1; title = "HyperIslandProTouch" }
    private fun createOutsideWatcherParams() = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, 16777216 or 8 or 4096 or 512 or 256, -3).apply { gravity = Gravity.TOP or Gravity.START; title = "HyperIslandProOutside" }
    private fun createIslandBackground(r: Float) = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(Color.BLACK); cornerRadius = r }
    private fun loadAppIcon(pkg: String) = try { packageManager.getApplicationIcon(pkg) } catch (_: Exception) { null }
    private fun getAppName(pkg: String) = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
    private fun formatNotificationTime(t: Long) = if (t <= 0L || System.currentTimeMillis() - t < 60000L) "now" else SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(t))
    private fun buildDisplayText(appName: String, t: String, m: String): DisplayText { val a = appName.trim().ifBlank { "App" }; var ti = t.trim(); var me = m.trim(); if (ti.equals(a, true)) { ti = me; me = "" }; if (ti.isBlank() && me.isNotBlank()) { ti = me; me = "" }; return DisplayText(a, ti, me) }
    private fun lerp(s: Int, e: Int, p: Float) = (s + ((e - s) * p)).roundToInt()
    private fun lerp(s: Float, e: Float, p: Float) = s + ((e - s) * p)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() { hideIslandInternal(); if (instance === this) instance = null; super.onDestroy() }
}
