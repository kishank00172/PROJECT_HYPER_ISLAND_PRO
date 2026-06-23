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
import android.graphics.Region
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
import java.lang.reflect.Proxy

class HyperAccessibilityService : AccessibilityService() {

    private enum class IslandStage { STAGE1_IDLE, STAGE2_PING, STAGE3_FULL }
    private enum class ExpandReason { MANUAL_USER, AUTO_NOTIFICATION }
    
    private data class DisplayText(val appName: String, val title: String, val message: String)
    private data class NotificationModel(
        val packageName: String, val appName: String, val title: String, val message: String,
        val postTime: Long, val contentIntent: PendingIntent?, val actions: List<Notification.Action>
    )

    companion object {
        private const val WINDOW_FLAGS_MASTER = 16777216 or 8 or 512 or 256 or 65536 or 131072 or 4096
        @Volatile private var instance: HyperAccessibilityService? = null
        fun isConnected(): Boolean = instance != null
        fun showIslandFromApp(context: Context) = instance?.run { postShowIsland(); true } ?: false
        fun hideIslandFromApp(context: Context? = null) = instance?.run { postHideIsland(); true } ?: false
        fun refreshIslandFromApp(context: Context) = instance?.run { postUpdateIsland(); true } ?: false
        fun expandIslandFromApp(context: Context) = instance?.run { postExpandIsland(); true } ?: false
        fun collapseIslandFromApp(context: Context) = instance?.run { postCollapseIsland(); true } ?: false
        fun toggleExpandFromApp(context: Context) = instance?.run { postToggleExpanded(); true } ?: false
        fun showNotificationFromApp(context: Context, packageName: String, appName: String, title: String, message: String, postTime: Long, contentIntent: PendingIntent?, actions: List<Notification.Action>) =
            instance?.run { postNotificationEvent("NotificationListener", packageName, appName, title, message, postTime, contentIntent, actions); true } ?: false
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
    private val outlineRect = Rect()
    private var outlineRadius = 0f

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(WindowManager::class.java)
        if (AppSettings.isIslandEnabled(this)) postShowIsland()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            val pkg = event.packageName?.toString().orEmpty()
            if (pkg.isBlank() || pkg == packageName || !AppSettings.isIslandEnabled(this)) return
            val textItems = event.text?.mapNotNull { it?.toString()?.trim() }?.filter { it.isNotBlank() } ?: return
            if (textItems.isEmpty()) return
            val appName = getAppName(pkg)
            val (title, message) = if (textItems.size >= 2) textItems[0] to textItems.drop(1).joinToString(" • ") else appName to textItems[0]
            postNotificationEvent("AccessibilityFallback", pkg, appName, title, message, System.currentTimeMillis(), null, emptyList())
        }
    }

    override fun onInterrupt() = Unit

    private fun postShowIsland() = mainHandler.post { showIslandInternal() }
    private fun postHideIsland() = mainHandler.post { hideIslandInternal() }
    private fun postUpdateIsland() = mainHandler.post { if (visualRoot == null) showIslandInternal() else updateAllToCurrentState() }
    private fun postExpandIsland() = mainHandler.post { setStageAnimated(IslandStage.STAGE3_FULL, ExpandReason.MANUAL_USER) }
    private fun postCollapseIsland() = mainHandler.post { setStageAnimated(IslandStage.STAGE1_IDLE, expandReason) }
    private fun postToggleExpanded() = mainHandler.post { 
        if (notificationMode && currentStage == IslandStage.STAGE3_FULL) openCurrentNotification()
        else setStageAnimated(if (currentStage == IslandStage.STAGE3_FULL) IslandStage.STAGE1_IDLE else IslandStage.STAGE3_FULL, ExpandReason.MANUAL_USER)
    }

    private fun postNotificationEvent(source: String, packageName: String, appName: String, title: String, message: String, postTime: Long, contentIntent: PendingIntent?, actions: List<Notification.Action>) {
        mainHandler.post {
            if (!AppSettings.isIslandEnabled(this)) return@post
            val now = System.currentTimeMillis()
            if (source == "AccessibilityFallback" && now - lastPrimaryEventTime < 1500L) return@post
            if (source == "NotificationListener") lastPrimaryEventTime = now
            val display = buildDisplayText(appName, title, message)
            val fingerprint = "$packageName|${display.title}|${display.message}"
            if (fingerprint == lastIslandFingerprint && now - lastIslandFingerprintTime < 1000L) { scheduleAutoCollapse(); return@post }
            lastIslandFingerprint = fingerprint; lastIslandFingerprintTime = now
            if (visualRoot == null) showIslandInternal()
            notificationQueue.add(NotificationModel(packageName, appName, title, message, postTime, contentIntent, actions))
            if (!isProcessingQueue) processNextInQueue() else scheduleAutoCollapse()
        }
    }

    private fun processNextInQueue() {
        val next = notificationQueue.poll() ?: run { isProcessingQueue = false; return }
        isProcessingQueue = true; notificationMode = true
        if (currentStage == IslandStage.STAGE1_IDLE) { updateNotificationContent(next); triggerFluidExpansion(); scheduleAutoCollapse() }
        else playFluidTransitionAnimation(next)
    }

    private fun playFluidTransitionAnimation(next: NotificationModel) {
        val isFlash = notificationQueue.size >= 35
        val exit = ValueAnimator.ofFloat(0f, 1f).apply { duration = if (isFlash) 120L else 200L; interpolator = AccelerateInterpolator(); addUpdateListener { gridRoot?.alpha = 1f - it.animatedValue as Float; gridRoot?.translationY = it.animatedValue as Float * 20f } }
        val entry = ValueAnimator.ofFloat(0f, 1f).apply { duration = if (isFlash) 150L else 250L; interpolator = DecelerateInterpolator(); addUpdateListener { gridRoot?.alpha = it.animatedValue as Float; gridRoot?.translationY = -20f * (1f - it.animatedValue as Float) } }
        exit.addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(a: Animator) { updateNotificationContent(next); entry.start() } })
        entry.addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(a: Animator) { scheduleAutoCollapse() } })
        exit.start()
    }

    private fun updateNotificationContent(model: NotificationModel) {
        currentPendingIntent = model.contentIntent; currentPackageName = model.packageName
        val display = buildDisplayText(model.appName, model.title, model.message)
        appIconView?.setImageDrawable(loadAppIcon(model.packageName))
        headerLine?.text = "${display.appName} • ${formatNotificationTime(model.postTime)}"
        titleText?.text = display.title; messageText?.text = display.message
        titleText?.visibility = if (display.title.isBlank()) View.GONE else View.VISIBLE
        messageText?.visibility = if (display.message.isBlank()) View.GONE else View.VISIBLE
        setupActionTiles(model.actions); forceRegionUpdate()
    }

    private fun setupActionTiles(actions: List<Notification.Action>) {
        footerActions?.removeAllViews()
        if (actions.isEmpty()) { actionScroll?.visibility = View.GONE; return }
        actionScroll?.visibility = View.VISIBLE
        val availableWidthDp = ((AppSettings.getIslandExpandedWidthDp(this) - 36) * 0.8).toInt()
        val btnWidth = when (actions.size) { 1 -> (availableWidthDp * 0.7).toInt(); 2 -> (availableWidthDp * 0.46).toInt(); 3 -> (availableWidthDp * 0.31).toInt(); else -> 90 }
        actions.forEach { action ->
            val btn = TextView(this).apply {
                text = action.title; setTextColor(Color.WHITE); textSize = 11f; gravity = Gravity.CENTER; setPadding(dp(10), 0, dp(10), 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(Color.parseColor("#222222")); cornerRadius = dp(14).toFloat(); setStroke(dp(1), Color.parseColor("#444444")) }
                isClickable = true
                setOnClickListener { 
                    val oldText = text; text = "✓ $oldText"; setTextColor(Color.GREEN)
                    postDelayed({ try { action.actionIntent.send(); postCollapseIsland() } catch (_: Exception) { text = oldText; setTextColor(Color.WHITE) } }, 500)
                }
            }
            footerActions?.addView(btn, LinearLayout.LayoutParams(dp(btnWidth), dp(32)).apply { marginStart = dp(6) })
        }
    }

    private fun triggerFluidExpansion() {
        morphAnimator?.cancel()
        val startW = dp(AppSettings.getIslandWidthDp(this)); val pingW = dp(AppSettings.getIslandStage2WidthDp(this)); val targetW = dp(AppSettings.getIslandExpandedWidthDp(this))
        val startH = dp(AppSettings.getIslandHeightDp(this)); val targetH = dp(AppSettings.getIslandExpandedHeightDp(this))
        val startR = dp(AppSettings.getIslandCornerRadiusDp(this)).toFloat(); val targetR = dp(AppSettings.getIslandExpandedCornerRadiusDp(this)).toFloat()
        currentStage = IslandStage.STAGE3_FULL; expandReason = ExpandReason.AUTO_NOTIFICATION
        val ping = ValueAnimator.ofFloat(0f, 1f).apply { duration = 100L; addUpdateListener { updateIslandLayout(lerpEven(startW, pingW, it.animatedValue as Float), startH, startR) } }
        val expand = ValueAnimator.ofFloat(0f, 1f).apply { duration = 350L; interpolator = fluidInterpolator; addUpdateListener { val t = it.animatedValue as Float; updateIslandLayout(lerpEven(pingW, targetW, t), lerpEven(startH, targetH, t), lerp(startR, targetR, t)); gridRoot?.alpha = t; gridRoot?.visibility = View.VISIBLE } }
        val set = AnimatorSet().apply { playSequentially(ping, expand); addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(a: Animator) { scheduleAutoCollapse() } }) }
        morphAnimator = set; set.start()
    }

    private fun setStageAnimated(target: IslandStage, reason: ExpandReason) {
        if (currentStage == target && morphAnimator?.isRunning == true) return
        if (target == IslandStage.STAGE3_FULL && reason == ExpandReason.AUTO_NOTIFICATION) { triggerFluidExpansion(); return }
        morphAnimator?.cancel()
        val curW = islandLayoutParams?.width ?: dp(AppSettings.getIslandWidthDp(this)); val curH = islandLayoutParams?.height ?: dp(AppSettings.getIslandHeightDp(this)); val curR = islandBackground?.cornerRadius ?: dp(AppSettings.getIslandCornerRadiusDp(this)).toFloat()
        currentStage = target; expandReason = reason
        val targetW = dp(getTargetWidth(target)); val targetH = dp(getTargetHeight(target)); val targetR = dp(getTargetRadius(target)).toFloat()
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (target == IslandStage.STAGE1_IDLE) 250L else 350L; interpolator = fluidInterpolator
            addUpdateListener { val t = it.animatedValue as Float; updateIslandLayout(lerpEven(curW, targetW, t), lerpEven(curH, targetH, t), lerp(curR, targetR, t)); if (target == IslandStage.STAGE1_IDLE) gridRoot?.alpha = 1f - t }
            addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(a: Animator) { if (target == IslandStage.STAGE1_IDLE) { gridRoot?.visibility = View.GONE; notificationMode = false }; isProcessingQueue = false } })
        }
        morphAnimator = anim; anim.start()
    }

    private fun updateIslandLayout(w: Int, h: Int, r: Float) {
        islandLayoutParams?.width = w; islandLayoutParams?.height = h; islandBackground?.cornerRadius = r
        outlineRadius = r; islandView?.layoutParams = islandLayoutParams; visualRoot?.invalidateOutline(); forceRegionUpdate()
    }

    private fun getTargetWidth(s: IslandStage) = when(s) { IslandStage.STAGE1_IDLE -> AppSettings.getIslandWidthDp(this); IslandStage.STAGE2_PING -> AppSettings.getIslandStage2WidthDp(this); IslandStage.STAGE3_FULL -> AppSettings.getIslandExpandedWidthDp(this) }
    private fun getTargetHeight(s: IslandStage) = when(s) { IslandStage.STAGE1_IDLE -> AppSettings.getIslandHeightDp(this); IslandStage.STAGE2_PING -> AppSettings.getIslandHeightDp(this) + 4; IslandStage.STAGE3_FULL -> AppSettings.getIslandExpandedHeightDp(this) }
    private fun getTargetRadius(s: IslandStage) = if (s == IslandStage.STAGE3_FULL) AppSettings.getIslandExpandedCornerRadiusDp(this) else AppSettings.getIslandCornerRadiusDp(this)

    private fun scheduleAutoCollapse() {
        autoCollapseRunnable?.let { mainHandler.removeCallbacks(it) }
        val size = notificationQueue.size
        val delay = when { size == 0 -> 5200L; size in 1..5 -> 3000L; size in 6..19 -> 1500L; size in 20..34 -> 800L; else -> 250L }
        autoCollapseRunnable = Runnable { if (notificationQueue.isNotEmpty()) processNextInQueue() else setStageAnimated(IslandStage.STAGE1_IDLE, ExpandReason.AUTO_NOTIFICATION) }.also { mainHandler.postDelayed(it, delay) }
    }

    private fun openCurrentNotification() { try { currentPendingIntent?.send() ?: currentPackageName?.let { pkg -> packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } } } catch (_: Exception) {}; postCollapseIsland() }

    private fun showIslandInternal() {
        hideIslandInternal()
        visualParams = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WINDOW_FLAGS_MASTER, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP; y = 0; if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = 1; title = "HyperIslandProVisual" }
        visualRoot = FrameLayout(this).apply { setBackgroundColor(0)
            try {
                val observer = viewTreeObserver
                val onComputeInternalInsetsListenerClass = Class.forName("android.view.ViewTreeObserver\$OnComputeInternalInsetsListener")
                val addListenerMethod = observer.javaClass.getMethod("addOnComputeInternalInsetsListener", onComputeInternalInsetsListenerClass)
                val proxy = Proxy.newProxyInstance(onComputeInternalInsetsListenerClass.classLoader, arrayOf(onComputeInternalInsetsListenerClass)) { _, method, args ->
                    if (method.name == "onComputeInternalInsets") {
                        val info = args[0]
                        info.javaClass.getMethod("setTouchableInsets", Int::class.javaPrimitiveType).invoke(info, 3)
                        val region = info.javaClass.getField("touchableRegion").get(info) as Region
                        val rect = Rect(); islandView?.getGlobalVisibleRect(rect)
                        if (!rect.isEmpty) region.set(rect)
                    }
                    null
                }
                addListenerMethod.invoke(observer, proxy)
            } catch (_: Exception) {}
        }
        islandBackground = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(Color.BLACK); cornerRadius = dp(AppSettings.getIslandCornerRadiusDp(this)).toFloat() }
        islandView = FrameLayout(this).apply {
            background = islandBackground; clipToOutline = true; outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, outlineRadius) } }
            gridRoot = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10)); visibility = View.GONE; alpha = 0f
                val iconSec = FrameLayout(context).apply { appIconView = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }; addView(appIconView, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER)) }
                val contentSec = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0)
                    headerLine = TextView(context).apply { setTextColor(Color.rgb(0, 150, 255)); textSize = 11f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
                    titleText = TextView(context).apply { setTextColor(Color.WHITE); textSize = 15f; typeface = Typeface.DEFAULT_BOLD; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
                    messageText = TextView(context).apply { setTextColor(Color.rgb(200, 200, 200)); textSize = 13f; maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
                    actionScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; footerActions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }; addView(footerActions) }
                    addView(headerLine); addView(titleText); addView(messageText); addView(actionScroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
                }
                addView(iconSec, LinearLayout.LayoutParams(0, -2, 0.2f)); addView(contentSec, LinearLayout.LayoutParams(0, -2, 0.8f))
            }
            addView(gridRoot, FrameLayout.LayoutParams(-1, -1))
            setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_UP) { if (e.rawY - touchStartY < -dp(24)) postCollapseIsland() else postToggleExpanded() } else if (e.action == MotionEvent.ACTION_DOWN) { touchStartY = e.rawY }; true }
        }
        islandLayoutParams = FrameLayout.LayoutParams(dp(AppSettings.getIslandWidthDp(this)), dp(AppSettings.getIslandHeightDp(this))).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL }
        visualRoot?.addView(islandView, islandLayoutParams)
        try { windowManager?.addView(visualRoot, visualParams) } catch (_: Exception) { hideIslandInternal() }
    }

    private fun forceRegionUpdate() { visualRoot?.post { visualRoot?.requestLayout(); visualRoot?.parent?.requestLayout() } }
    fun updateAllToCurrentState() { val w = dp(getTargetWidth(currentStage)); val h = dp(getTargetHeight(currentStage)); val r = dp(getTargetRadius(currentStage)).toFloat(); updateIslandLayout(w, h, r) }
    private fun hideIslandInternal() { morphAnimator?.cancel(); autoCollapseRunnable?.let { mainHandler.removeCallbacks(it) }; try { windowManager?.removeViewImmediate(visualRoot!!) } catch (_: Exception) {}; visualRoot = null; currentStage = IslandStage.STAGE1_IDLE }
    private fun loadAppIcon(pkg: String) = try { packageManager.getApplicationIcon(pkg) } catch (_: Exception) { null }
    private fun getAppName(pkg: String) = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
    private fun formatNotificationTime(t: Long) = if (t <= 0L || System.currentTimeMillis() - t < 60000L) "now" else SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(t))
    private fun buildDisplayText(appName: String, t: String, m: String): DisplayText { val a = appName.trim().ifBlank { "App" }; var ti = t.trim(); var me = m.trim(); if (ti.equals(a, true)) { ti = me; me = "" }; if (ti.isBlank() && me.isNotBlank()) { ti = me; me = "" }; return DisplayText(a, ti, me) }
    private fun lerpEven(s: Int, e: Int, p: Float): Int { val v = (s + ((e - s) * p)).roundToInt(); return if (v % 2 != 0) v + 1 else v }
    private fun lerp(s: Float, e: Float, p: Float) = s + ((e - s) * p)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    override fun onDestroy() { hideIslandInternal(); if (instance === this) instance = null; super.onDestroy() }
}
