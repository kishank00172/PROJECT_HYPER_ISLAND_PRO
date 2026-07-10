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
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
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
import kotlin.math.sin
import java.lang.reflect.Proxy

class HyperAccessibilityService : AccessibilityService() {

    private enum class IslandStage { STAGE1_IDLE, STAGE2_PING, STAGE3_FULL }
    private enum class ExpandReason { MANUAL_USER, AUTO_NOTIFICATION }
    
    private data class DisplayText(val appName: String, val title: String, val message: String)
    private data class NotificationModel(
        val packageName: String, val appName: String, val title: String, val message: String,
        val postTime: Long, val contentIntent: PendingIntent?, val actions: List<Notification.Action>
    )

    private val expandInterpolator = PathInterpolator(0.34f, 1.56f, 0.64f, 1.0f) 
    private val morphInterpolator = PathInterpolator(0.25f, 0.46f, 0.45f, 0.94f) 
    private val collapseInterpolator = PathInterpolator(0.55f, 0.0f, 0.1f, 1.0f)

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
    private val notificationQueue = ArrayDeque<NotificationModel>()
    private var isProcessingQueue = false
    private var isShadeOpen = false
    private var isReplyMode = false
    
    private var windowManager: WindowManager? = null
    private var visualRoot: FrameLayout? = null
    private var visualParams: WindowManager.LayoutParams? = null
    private var islandView: FrameLayout? = null
    private var islandLayoutParams: FrameLayout.LayoutParams? = null
    private var islandBackground: GradientDrawable? = null
    
    private var gridRoot: LinearLayout? = null
    private var appIconView: ImageView? = null
    private var headerLine: TextView? = null
    private var appNameText: TextView? = null
    private var timeStampText: TextView? = null
    private var titleText: TextView? = null
    private var messageText: TextView? = null
    private var footerActions: LinearLayout? = null
    private var actionScroll: HorizontalScrollView? = null

    // Reply UI
    private var replyBar: LinearLayout? = null
    private var replyEditText: EditText? = null
    private var sendButton: TextView? = null

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
    private val outlineRect = Rect()
    private var outlineRadius = 0f

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(WindowManager::class.java)
        if (AppSettings.isIslandEnabled(this)) postShowIsland()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            val shadeOpen = checkNotificationShadeState()
            if (shadeOpen != isShadeOpen) {
                isShadeOpen = shadeOpen
                visualRoot?.animate()?.alpha(if (isShadeOpen) 0f else 1f)?.setDuration(if (isShadeOpen) 120 else 200)?.start()
            }
        }
        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            val pkg = event.packageName?.toString().orEmpty()
            if (pkg.isBlank() || pkg == packageName || !AppSettings.isIslandEnabled(this)) return
            val rawText = event.text ?: return
            val textItems = rawText.mapNotNull { it?.toString()?.trim() }.filter { it.isNotBlank() }
            if (textItems.isEmpty()) return
            val appName = getAppName(pkg)
            val (t, m) = if (textItems.size >= 2) textItems[0] to textItems.drop(1).joinToString(" • ") else appName to textItems[0]
            postNotificationEvent("AccessibilityFallback", pkg, appName, t, m, System.currentTimeMillis(), null, emptyList())
        }
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (isReplyMode && event?.keyCode == KeyEvent.KEYCODE_BACK) {
            exitReplyMode()
            return true
        }
        return super.onKeyEvent(event)
    }

    private fun checkNotificationShadeState(): Boolean {
        val windowList = windows ?: return false
        val screenHeight = resources.displayMetrics.heightPixels
        return windowList.any { it.type == AccessibilityWindowInfo.TYPE_SYSTEM && it.getBoundsInScreen(outlineRect).let { rect -> outlineRect.height() > screenHeight * 0.35f } && it.root?.packageName == "com.android.systemui" }
    }

    override fun onInterrupt() = Unit

    private fun postShowIsland() = mainHandler.post { showIslandInternal() }
    private fun postHideIsland() = mainHandler.post { hideIslandInternal() }
    private fun postUpdateIsland() = mainHandler.post { if (visualRoot == null) showIslandInternal() else updateAllToCurrentState() }
    private fun postExpandIsland() = mainHandler.post { setStageAnimated(IslandStage.STAGE3_FULL, ExpandReason.MANUAL_USER) }
    private fun postCollapseIsland() = mainHandler.post { if (isReplyMode) exitReplyMode() else setStageAnimated(IslandStage.STAGE1_IDLE, expandReason) }
    private fun postToggleExpanded() = mainHandler.post { if (notificationMode && currentStage == IslandStage.STAGE3_FULL) openCurrentNotification() else setStageAnimated(if (currentStage == IslandStage.STAGE3_FULL) IslandStage.STAGE1_IDLE else IslandStage.STAGE3_FULL, ExpandReason.MANUAL_USER) }

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
            notificationQueue.add(NotificationModel(packageName, appName, title, message, postTime, contentIntent, actions))
            if (!isProcessingQueue) processNextInQueue() else scheduleAutoCollapse()
        }
    }

    private fun processNextInQueue() {
        if (isReplyMode) return
        val next = notificationQueue.poll() ?: run { isProcessingQueue = false; return }
        isProcessingQueue = true; notificationMode = true
        if (currentStage == IslandStage.STAGE1_IDLE) { updateNotificationContent(next); triggerFluidExpansion(); scheduleAutoCollapse() }
        else playFluidTransitionAnimation(next)
    }

    private fun playFluidTransitionAnimation(next: NotificationModel) {
        val isFlash = notificationQueue.size >= 35
        val exit = ValueAnimator.ofFloat(0f, 1f).apply { duration = if (isFlash) 120L else 200L; interpolator = AccelerateInterpolator(); addUpdateListener { gridRoot?.alpha = 1f - it.animatedValue as Float; gridRoot?.translationY = it.animatedValue as Float * 20f } }
        val entry = ValueAnimator.ofFloat(0f, 1f).apply { duration = if (isFlash) 150L else 300L; interpolator = morphInterpolator; addUpdateListener { gridRoot?.alpha = it.animatedValue as Float; gridRoot?.translationY = -20f * (1f - it.animatedValue as Float) } }
        exit.addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(a: Animator) { updateNotificationContent(next); entry.start() } })
        entry.addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(a: Animator) { scheduleAutoCollapse() } })
        exit.start()
    }

    private fun updateNotificationContent(model: NotificationModel) {
        currentPendingIntent = model.contentIntent; currentPackageName = model.packageName
        appIconView?.setImageDrawable(loadAppIcon(model.packageName))
        appNameText?.text = model.appName; timeStampText?.text = formatNotificationTime(model.postTime)
        titleText?.text = model.title; messageText?.text = model.message
        titleText?.visibility = if (model.title.isBlank()) View.GONE else View.VISIBLE
        messageText?.visibility = if (model.message.isBlank()) View.GONE else View.VISIBLE
        setupActionTiles(model.actions); forceRegionUpdate()
    }

    private fun setupActionTiles(actions: List<Notification.Action>) {
        footerActions?.removeAllViews()
        if (actions.isEmpty()) { actionScroll?.visibility = View.GONE; return }
        actionScroll?.visibility = View.VISIBLE
        val totalWidthDp = AppSettings.getIslandExpandedWidthDp(this) - 56
        val availableWidthDp = (totalWidthDp * 0.8).toInt()
        val btnWidth = when (actions.size) { 1 -> (availableWidthDp * 0.70).toInt(); 2 -> (availableWidthDp * 0.46).toInt(); else -> (availableWidthDp * 0.31).toInt() }
        actions.forEach { action ->
            val btn = TextView(this).apply {
                text = action.title; setTextColor(Color.WHITE); textSize = 11f; gravity = Gravity.CENTER; setPadding(dp(12), 0, dp(12), 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                background = createIslandBackground(dp(16).toFloat()).apply { setColor(Color.parseColor("#222222")); setStroke(dp(1), Color.parseColor("#444444")) }
                isClickable = true
                setOnClickListener { 
                    if (action.title.toString().contains("Reply", true)) enterReplyMode()
                    else {
                        val oldT = text; text = "✓ $oldT"; setTextColor(Color.GREEN)
                        postDelayed({ try { action.actionIntent.send(); postCollapseIsland() } catch (_: Exception) { text = oldT; setTextColor(Color.WHITE) } }, 500)
                    }
                }
            }
            footerActions?.addView(btn, LinearLayout.LayoutParams(dp(btnWidth), dp(32)).apply { marginStart = dp(6) })
        }
    }

    private fun enterReplyMode() {
        if (isReplyMode) return
        isReplyMode = true
        autoCollapseRunnable?.let { mainHandler.removeCallbacks(it) }
        
        // Morph: Fade out other buttons and morph input
        actionScroll?.animate()?.alpha(0f)?.setDuration(200)?.setListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) { actionScroll?.visibility = View.GONE }
        })?.start()

        replyBar?.visibility = View.VISIBLE
        replyBar?.alpha = 0f
        replyBar?.translationY = dp(10).toFloat()
        replyBar?.animate()?.alpha(1f)?.translationY(0f)?.setDuration(300)?.setStartDelay(100)?.start()

        val p = visualParams ?: return
        p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        
        visualRoot?.postDelayed({
            windowManager?.updateViewLayout(visualRoot, p)
            replyEditText?.requestFocus()
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(replyEditText, InputMethodManager.SHOW_FORCED)
        }, 50)
    }

    private fun exitReplyMode() {
        isReplyMode = false
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(replyEditText?.windowToken, 0)
        
        replyBar?.animate()?.alpha(0f)?.setDuration(200)?.setListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) { 
                replyBar?.visibility = View.GONE
                actionScroll?.visibility = View.VISIBLE
                actionScroll?.animate()?.alpha(1f)?.setDuration(200)?.start()
            }
        })?.start()

        val p = visualParams ?: return
        p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        visualRoot?.postDelayed({
            windowManager?.updateViewLayout(visualRoot, p)
            if (notificationQueue.isNotEmpty()) processNextInQueue() else postCollapseIsland()
        }, 150)
    }

    private fun triggerFluidExpansion() {
        morphAnimator?.cancel()
        val startW = dp(AppSettings.getIslandWidthDp(this)); val pingW = dp(AppSettings.getIslandStage2WidthDp(this)); val targetW = dp(AppSettings.getIslandExpandedWidthDp(this))
        val startH = dp(AppSettings.getIslandHeightDp(this)); val targetH = dp(AppSettings.getIslandExpandedHeightDp(this))
        val startR = dp(AppSettings.getIslandCornerRadiusDp(this)).toFloat(); val targetR = dp(AppSettings.getIslandExpandedCornerRadiusDp(this)).toFloat()
        currentStage = IslandStage.STAGE3_FULL; expandReason = ExpandReason.AUTO_NOTIFICATION
        val ping = ValueAnimator.ofFloat(0f, 1f).apply { duration = 100L; addUpdateListener { updateIslandLayout(lerpEven(startW, pingW, it.animatedValue as Float), startH, startR) } }
        val expand = ValueAnimator.ofFloat(0f, 1f).apply { duration = 380L; interpolator = expandInterpolator; addUpdateListener { val t = it.animatedValue as Float; updateIslandLayout(lerpEven(pingW, targetW, t), lerpEven(startH, targetH, t), lerp(startR, targetR, t)); gridRoot?.alpha = t; gridRoot?.visibility = View.VISIBLE; islandView?.scaleY = 1f - (0.04f * sin(t * Math.PI).toFloat()) } }
        val set = AnimatorSet().apply { playSequentially(ping, expand); addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(a: Animator) { scheduleAutoCollapse(); updateOutsideWatcherForState() } }) }
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
            duration = if (target == IslandStage.STAGE1_IDLE) 300L else 450L
            interpolator = if (target == IslandStage.STAGE1_IDLE) collapseInterpolator else expandInterpolator
            addUpdateListener { val t = it.animatedValue as Float; updateIslandLayout(lerpEven(curW, targetW, t), lerpEven(curH, targetH, t), lerp(curR, targetR, t)); if (notificationMode && target == IslandStage.STAGE1_IDLE) gridRoot?.alpha = 1f - t; islandView?.scaleY = 1f - (0.04f * sin(t * Math.PI).toFloat()) }
            addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(a: Animator) { if (target == IslandStage.STAGE1_IDLE) { gridRoot?.visibility = View.GONE; notificationMode = false }; updateOutsideWatcherForState(); isProcessingQueue = false } })
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
        if (isReplyMode) return
        autoCollapseRunnable?.let { mainHandler.removeCallbacks(it) }
        val size = notificationQueue.size
        val delay = when { size == 0 -> 5200L; size in 1..5 -> 3000L; size in 6..19 -> 1500L; size in 20..34 -> 800L; else -> 250L }
        autoCollapseRunnable = Runnable { if (notificationQueue.isNotEmpty()) processNextInQueue() else setStageAnimated(IslandStage.STAGE1_IDLE, ExpandReason.AUTO_NOTIFICATION) }.also { mainHandler.postDelayed(it, delay) }
    }

    private fun openCurrentNotification() { if (isReplyMode) return; try { currentPendingIntent?.send() ?: currentPackageName?.let { pkg -> packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } } } catch (_: Exception) {}; postCollapseIsland() }

    private fun showIslandInternal() {
        hideIslandInternal()
        val h = dp(AppSettings.getIslandHeightDp(this)); val w = dp(AppSettings.getIslandWidthDp(this)); val r = dp(AppSettings.getIslandCornerRadiusDp(this)).toFloat()
        visualParams = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WINDOW_FLAGS_MASTER, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP; y = 0; if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = 1; windowAnimations = 0; title = "HyperIslandProVisual" }
        visualRoot = FrameLayout(this).apply { setBackgroundColor(0)
            try {
                val observer = viewTreeObserver
                val listenerClass = Class.forName("android.view.ViewTreeObserver\$OnComputeInternalInsetsListener")
                val proxy = Proxy.newProxyInstance(listenerClass.classLoader, arrayOf(listenerClass)) { _, method, args ->
                    if (method.name == "onComputeInternalInsets") {
                        val info = args[0]
                        info.javaClass.getMethod("setTouchableInsets", Int::class.javaPrimitiveType).invoke(info, 3)
                        val region = info.javaClass.getField("touchableRegion").get(info) as Region
                        val rect = Rect(); islandView?.getGlobalVisibleRect(rect)
                        if (!rect.isEmpty) region.set(rect)
                    }
                    null
                }
                observer.javaClass.getMethod("addOnComputeInternalInsetsListener", listenerClass).invoke(observer, proxy)
            } catch (_: Exception) {}
        }
        islandBackground = createIslandBackground(r)
        islandView = FrameLayout(this).apply {
            background = islandBackground; clipToOutline = true; outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, outlineRadius) } }
            gridRoot = LinearLayout(this@HyperAccessibilityService).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(20)); visibility = View.GONE; alpha = 0f; weightSum = 1f
                val iconSec = FrameLayout(context).apply { appIconView = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }; addView(appIconView, FrameLayout.LayoutParams(dp(38), dp(38), Gravity.CENTER)) }
                val contentSec = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, 0, 0)
                    val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                    appNameText = TextView(context).apply { setTextColor(Color.rgb(0, 150, 255)); textSize = 11f; typeface = Typeface.DEFAULT_BOLD }
                    timeStampText = TextView(context).apply { setTextColor(Color.GRAY); textSize = 10f; setPadding(dp(6), 0, 0, 0) }
                    header.addView(appNameText); header.addView(timeStampText)
                    headerLine = appNameText
                    titleText = TextView(context).apply { setTextColor(Color.WHITE); textSize = 15.5f; typeface = Typeface.DEFAULT_BOLD; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
                    messageText = TextView(context).apply { setTextColor(Color.rgb(200, 200, 200)); textSize = 13f; maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
                    actionScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; footerActions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }; addView(footerActions) }
                    
                    // Reply Bar UI
                    replyBar = LinearLayout(context).apply { 
                        orientation = LinearLayout.HORIZONTAL; visibility = View.GONE; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0)
                        val inputBg = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(Color.parseColor("#1A1A1A")); cornerRadius = dp(12).toFloat(); setStroke(dp(1), Color.parseColor("#333333")) }
                        replyEditText = EditText(context).apply { hint = "Type a reply..."; setHintTextColor(Color.GRAY); setTextColor(Color.WHITE); textSize = 13f; background = inputBg; setPadding(dp(12), dp(8), dp(12), dp(8)); layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f) }
                        sendButton = TextView(context).apply { text = "SEND"; setTextColor(Color.rgb(0, 150, 255)); typeface = Typeface.DEFAULT_BOLD; setPadding(dp(12), 0, 0, 0); setOnClickListener { exitReplyMode() } }
                        addView(replyEditText); addView(sendButton)
                    }

                    addView(header); addView(titleText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(1) }); addView(messageText, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(1) }); addView(actionScroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }); addView(replyBar)
                }
                addView(iconSec, LinearLayout.LayoutParams(0, -2, 0.2f)); addView(contentSec, LinearLayout.LayoutParams(0, -2, 0.8f))
            }
            addView(gridRoot, FrameLayout.LayoutParams(-1, -1))
            setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_UP) { if (e.rawY - touchStartY < -dp(24)) postCollapseIsland() else if (abs(e.rawY - touchStartY) < dp(10)) postToggleExpanded() } else if (e.action == MotionEvent.ACTION_DOWN) { touchStartY = e.rawY }; true }
        }
        islandLayoutParams = FrameLayout.LayoutParams(w, h).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL }
        visualRoot?.addView(islandView, islandLayoutParams); updateOutlineForIsland(w, h, r)
        try { windowManager?.addView(visualRoot, visualParams) } catch (_: Exception) { hideIslandInternal() }
    }

    private fun updateOutsideWatcherForState() { if (currentStage == IslandStage.STAGE3_FULL && expandReason == ExpandReason.MANUAL_USER && !isReplyMode) ensureOutsideWatcher() else removeOutsideWatcher() }
    private fun ensureOutsideWatcher() { if (outsideWatcherView != null) return; outsideWatcherView = FrameLayout(this).apply { setBackgroundColor(0); setOnTouchListener { _, event -> if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_OUTSIDE) postCollapseIsland() ; false } }; try { windowManager?.addView(outsideWatcherView, WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, 16777216 or 8 or 4096 or 512 or 256, -3).apply { gravity = Gravity.TOP or Gravity.START; title = "HyperIslandProOutside" }) } catch (_: Exception) {} }
    private fun removeOutsideWatcher() { try { windowManager?.removeViewImmediate(outsideWatcherView!!) } catch (_: Exception) {}; outsideWatcherView = null }
    private fun forceRegionUpdate() { visualRoot?.post { visualRoot?.requestLayout(); visualRoot?.parent?.requestLayout() } }
    fun updateAllToCurrentState() { val w = dp(getTargetWidth(currentStage)); val h = dp(getTargetHeight(currentStage)); val r = dp(getTargetRadius(currentStage)).toFloat(); updateIslandLayout(w, h, r) }
    private fun hideIslandInternal() { morphAnimator?.cancel(); autoCollapseRunnable?.let { mainHandler.removeCallbacks(it) }; removeOutsideWatcher(); try { windowManager?.removeViewImmediate(visualRoot!!) } catch (_: Exception) {}; visualRoot = null; currentStage = IslandStage.STAGE1_IDLE; isReplyMode = false }
    private fun loadAppIcon(pkg: String) = try { packageManager.getApplicationIcon(pkg) } catch (_: Exception) { null }
    private fun getAppName(pkg: String) = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
    private fun formatNotificationTime(t: Long) = if (t <= 0L || System.currentTimeMillis() - t < 60000L) "now" else SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(t))
    private fun buildDisplayText(appName: String, t: String, m: String): DisplayText { val a = appName.trim().ifBlank { "App" }; var ti = t.trim(); var me = m.trim(); if (ti.equals(a, true)) { ti = me; me = "" }; if (ti.isBlank() && me.isNotBlank()) { ti = me; me = "" }; return DisplayText(a, ti, me) }
    private fun updateOutlineForIsland(w: Int, h: Int, r: Float) { val left = ((resources.displayMetrics.widthPixels - w) / 2) + dp(AppSettings.getIslandXDp(this)); val top = dp(AppSettings.getIslandYDp(this)); outlineRect.set(left, top, left + w, top + h); outlineRadius = r; islandLayoutParams?.topMargin = top }
    private fun lerpEven(s: Int, e: Int, p: Float): Int { val v = (s + ((e - s) * p)).roundToInt(); return if (v % 2 != 0) v + 1 else v }
    private fun lerp(s: Float, e: Float, p: Float) = s + ((e - s) * p)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun createIslandBackground(r: Float): GradientDrawable = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(Color.BLACK); cornerRadius = r }
    override fun onDestroy() { hideIslandInternal(); if (instance === this) instance = null; super.onDestroy() }
}
