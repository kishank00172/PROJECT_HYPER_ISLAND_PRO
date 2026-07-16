
package com.hyperisland.pro.services

import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.transition.AutoTransition
import android.transition.ChangeBounds
import android.transition.Transition
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
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

/**
 * PHASE 3.5: THE FLUID INTERACTION UPDATE
 * - Slow-Mo Liquid Animations (750ms+)
 * - Full Screen Touch Interceptor as "Off-Switch"
 * - HyperOS Keyboard Stabilization
 */
class HyperAccessibilityService : AccessibilityService() {

    private enum class IslandStage { STAGE1_IDLE, STAGE2_PING, STAGE3_FULL }
    private enum class ExpandReason { MANUAL_USER, AUTO_NOTIFICATION }
    
    private data class DisplayText(val appName: String, val title: String, val message: String)
    private data class NotificationModel(
        val packageName: String, val notificationKey: String?, val appName: String, val title: String, val message: String,
        val postTime: Long, val contentIntent: PendingIntent?, val actions: List<Notification.Action>
    )

    private class ReplyMorphView(context: Context) : View(context) {
        private val rect = RectF()
        private val underRect = RectF()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.LEFT
            typeface = Typeface.DEFAULT_BOLD
        }
        private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(145, 255, 255, 255)
            textAlign = Paint.Align.LEFT
            typeface = Typeface.DEFAULT
        }
        private val sendPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 150, 255)
            textAlign = Paint.Align.RIGHT
            typeface = Typeface.DEFAULT_BOLD
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dpLocal(1f)
            color = Color.argb(45, 255, 255, 255)
        }

        private var radius = dpLocal(16f)
        private var labelAlpha = 1f
        private var hintAlpha = 0f
        private var sendAlpha = 0f
        private var surfaceProgress = 0f
        private var highlightProgress = 0f
        private var visualStyle = 0
        private var labelText = "Reply"
        private var hintText = "Type a reply..."
        private var labelAnchorX = Float.NaN
        private var labelAnchorY = Float.NaN
        private var drawEnabled = false

        fun setLabelAnchor(centerX: Float, centerY: Float) {
            labelAnchorX = centerX
            labelAnchorY = centerY
        }

        fun setGhostState(
            bounds: RectF,
            cornerRadius: Float,
            replyLabelAlpha: Float,
            placeholderAlpha: Float,
            sendButtonAlpha: Float,
            surface: Float,
            highlight: Float,
            visualStyle: Int = 0,
            label: String = "Reply",
            hint: String = "Type a reply..."
        ) {
            rect.set(bounds)
            radius = cornerRadius
            labelAlpha = replyLabelAlpha.coerceIn(0f, 1f)
            hintAlpha = placeholderAlpha.coerceIn(0f, 1f)
            sendAlpha = sendButtonAlpha.coerceIn(0f, 1f)
            surfaceProgress = surface.coerceIn(0f, 1f)
            highlightProgress = highlight.coerceIn(0f, 1f)
            this.visualStyle = visualStyle
            labelText = label
            hintText = hint
            drawEnabled = true
            visibility = VISIBLE
            invalidate()
        }

        fun clearGhost() {
            drawEnabled = false
            labelAnchorX = Float.NaN
            labelAnchorY = Float.NaN
            visibility = INVISIBLE
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!drawEnabled || rect.isEmpty) return

            // Liquid mode: draw a soft lagging under-layer first. This is the real visual difference
            // from Shared mode; it should read as material depth, not as a second textbox.
            if (visualStyle == 1) {
                val lag = dpLocal(2.2f) * (1f - surfaceProgress)
                underRect.set(rect.left + lag, rect.top + dpLocal(1.2f), rect.right - lag, rect.bottom + dpLocal(1.5f))
                paint.style = Paint.Style.FILL
                paint.color = Color.argb((34 * (1f - (surfaceProgress * 0.25f))).toInt().coerceIn(0, 34), 170, 205, 255)
                canvas.drawRoundRect(underRect, radius, radius, paint)
            }

            val fillBase = when (visualStyle) {
                1 -> lerpColor(Color.parseColor("#20262B"), Color.parseColor("#101418"), surfaceProgress)
                2 -> lerpColor(Color.parseColor("#2A2A2A"), Color.parseColor("#151515"), surfaceProgress)
                else -> lerpColor(Color.parseColor("#242424"), Color.parseColor("#111214"), surfaceProgress)
            }
            paint.style = Paint.Style.FILL
            paint.color = fillBase
            canvas.drawRoundRect(rect, radius, radius, paint)

            // Premium edge: restrained and tonal. No neon.
            strokePaint.color = when (visualStyle) {
                1 -> Color.argb((42 + 35 * surfaceProgress).toInt(), 205, 230, 255)
                2 -> Color.argb((42 + 26 * surfaceProgress).toInt(), 255, 255, 255)
                else -> Color.argb((35 + 22 * surfaceProgress).toInt(), 255, 255, 255)
            }
            canvas.drawRoundRect(rect, radius, radius, strokePaint)

            // Style-specific polish. No curved arc: it looked like a scratch on-device.
            if (visualStyle == 1 && highlightProgress > 0.01f) {
                // Liquid Glass: subtle straight sheen clipped to rect, not a curved scratch.
                canvas.save()
                canvas.clipRect(rect)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dpLocal(14f)
                paint.color = Color.argb((18 * kotlin.math.sin(highlightProgress * Math.PI).toFloat()).toInt().coerceIn(0, 18), 255, 255, 255)
                val x = rect.left + rect.width() * highlightProgress
                canvas.drawLine(x - dpLocal(28f), rect.top + dpLocal(6f), x + dpLocal(28f), rect.bottom - dpLocal(6f), paint)
                canvas.restore()
            } else if (visualStyle == 2) {
                // HyperOS Capsule: a lower inner line, visible during fast settle.
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dpLocal(1.15f)
                paint.color = Color.argb((16 + 18 * surfaceProgress).toInt(), 255, 255, 255)
                canvas.drawLine(rect.left + dpLocal(16f), rect.bottom - dpLocal(3f), rect.right - dpLocal(16f), rect.bottom - dpLocal(3f), paint)
            }

            val centerY = rect.centerY()
            textPaint.textSize = dpLocal(11.5f)
            hintPaint.textSize = dpLocal(13f)
            sendPaint.textSize = dpLocal(11.5f)

            if (labelAlpha > 0.01f) {
                textPaint.alpha = (255 * labelAlpha).toInt().coerceIn(0, 255)
                val labelWidth = textPaint.measureText(labelText)
                val anchorX = if (labelAnchorX.isNaN()) rect.centerX() else labelAnchorX
                val anchorY = if (labelAnchorY.isNaN()) centerY else labelAnchorY
                val x = anchorX - labelWidth / 2f
                val y = anchorY - (textPaint.descent() + textPaint.ascent()) / 2f
                canvas.drawText(labelText, x, y, textPaint)
            }

            if (hintAlpha > 0.01f) {
                hintPaint.alpha = (255 * hintAlpha).toInt().coerceIn(0, 255)
                val x = rect.left + dpLocal(14f) + dpLocal(5f) * (1f - hintAlpha)
                val y = centerY - (hintPaint.descent() + hintPaint.ascent()) / 2f
                canvas.drawText(hintText, x, y, hintPaint)
            }

            if (sendAlpha > 0.01f) {
                sendPaint.alpha = (255 * sendAlpha).toInt().coerceIn(0, 255)
                val x = rect.right - dpLocal(12f) + dpLocal(5f) * (1f - sendAlpha)
                val y = centerY - (sendPaint.descent() + sendPaint.ascent()) / 2f
                canvas.drawText("SEND", x, y, sendPaint)
            }
        }

        private fun dpLocal(value: Float): Float = value * resources.displayMetrics.density

        private fun lerpColor(start: Int, end: Int, t: Float): Int {
            val p = t.coerceIn(0f, 1f)
            val a = Color.alpha(start) + ((Color.alpha(end) - Color.alpha(start)) * p).toInt()
            val r = Color.red(start) + ((Color.red(end) - Color.red(start)) * p).toInt()
            val g = Color.green(start) + ((Color.green(end) - Color.green(start)) * p).toInt()
            val b = Color.blue(start) + ((Color.blue(end) - Color.blue(start)) * p).toInt()
            return Color.argb(a, r, g, b)
        }
    }

    // ULTRA-SMOOTH CURVES
    private val expandInterpolator = PathInterpolator(0.34f, 1.56f, 0.64f, 1.0f) 
    private val morphInterpolator = PathInterpolator(0.25f, 0.46f, 0.45f, 0.94f) 
    private val collapseInterpolator = PathInterpolator(0.55f, 0.0f, 0.1f, 1.0f)
    private val ghostMagneticInterpolator = PathInterpolator(0.16f, 1.0f, 0.30f, 1.0f)
    private val ghostMaterialInterpolator = PathInterpolator(0.20f, 0.0f, 0.0f, 1.0f)
    private val ghostReverseInterpolator = PathInterpolator(0.40f, 0.0f, 0.20f, 1.0f)

    companion object {
        private const val WINDOW_FLAGS_MASTER = 16777216 or 8 or 512 or 256 or 65536 or 131072 or 4096
        private const val GLOBAL_ACTION_SHOW_KEYBOARD = 16

        @Volatile private var instance: HyperAccessibilityService? = null
        fun isConnected(): Boolean = instance != null
        fun showIslandFromApp(context: Context) = instance?.run { postShowIsland(); true } ?: false
        fun hideIslandFromApp(context: Context? = null) = instance?.run { postHideIsland(); true } ?: false
        fun refreshIslandFromApp(context: Context) = instance?.run { postUpdateIsland(); true } ?: false
        fun expandIslandFromApp(context: Context) = instance?.run { postExpandIsland(); true } ?: false
        fun collapseIslandFromApp(context: Context) = instance?.run { postCollapseIsland(); true } ?: false
        fun toggleExpandFromApp(context: Context) = instance?.run { postToggleExpanded(); true } ?: false
        fun showNotificationFromApp(context: Context, packageName: String, notificationKey: String? = null, appName: String, title: String, message: String, postTime: Long, contentIntent: PendingIntent?, actions: List<Notification.Action>) =
            instance?.run { postNotificationEvent("NotificationListener", packageName, notificationKey, appName, title, message, postTime, contentIntent, actions); true } ?: false
        fun previewReplyAnimationFromApp(context: Context, replySecond: Boolean) =
            instance?.run { postPreviewReplyAnimation(replySecond); true } ?: false
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

    // Reply Morph V2 — custom ghost material layer (premium candidate)
    private var morphLayer: FrameLayout? = null
    private var replyGhostView: ReplyMorphView? = null
    private var replyEditorLayer: LinearLayout? = null
    private var replyEditorEditText: EditText? = null
    private var replyEditorSendButton: TextView? = null
    private var isGhostReplyMode = false
    private var ghostSourceView: View? = null
    private var ghostSourceRect = RectF()
    private var ghostTargetRect = RectF()
    private var ghostAnimator: Animator? = null
    private var activeGhostReplyMode: Int = AppSettings.REPLY_ANIM_MAGNETIC_DOCK

    private var outsideWatcherView: FrameLayout? = null
    private var morphAnimator: Animator? = null
    private var currentStage = IslandStage.STAGE1_IDLE
    private var expandReason = ExpandReason.MANUAL_USER
    private var notificationMode = false
    private var currentPendingIntent: PendingIntent? = null
    private var currentPackageName: String? = null
    private var currentNotificationKey: String? = null
    private var currentReplyAction: Notification.Action? = null
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
                this@HyperAccessibilityService.visualRoot?.animate()?.alpha(if (isShadeOpen) 0f else 1f)?.setDuration(if (isShadeOpen) 150 else 250)?.start()
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
            postNotificationEvent("AccessibilityFallback", pkg, null, appName, t, m, System.currentTimeMillis(), null, emptyList())
        }
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        // HARD BACK BUTTON TO EXIT REPLY
        if (isReplyMode && event?.keyCode == KeyEvent.KEYCODE_BACK) {
            exitReplyMode()
            return true
        }
        return super.onKeyEvent(event)
    }

    private fun checkNotificationShadeState(): Boolean {
        val windowList = windows ?: return false
        val screenHeight = resources.displayMetrics.heightPixels
        return windowList.any { it.type == AccessibilityWindowInfo.TYPE_SYSTEM && it.getBoundsInScreen(this@HyperAccessibilityService.outlineRect).let { rect -> this@HyperAccessibilityService.outlineRect.height() > screenHeight * 0.35f } && it.root?.packageName == "com.android.systemui" }
    }

    override fun onInterrupt() = Unit

    private fun postShowIsland() = mainHandler.post { showIslandInternal() }
    private fun postHideIsland() = mainHandler.post { hideIslandInternal() }
    private fun postUpdateIsland() = mainHandler.post { if (this@HyperAccessibilityService.visualRoot == null) showIslandInternal() else updateAllToCurrentState() }
    private fun postExpandIsland() = mainHandler.post { setStageAnimated(IslandStage.STAGE3_FULL, ExpandReason.MANUAL_USER) }
    private fun postCollapseIsland() = mainHandler.post { if (isReplyMode) exitReplyMode() else setStageAnimated(IslandStage.STAGE1_IDLE, expandReason) }
    private fun postToggleExpanded() = mainHandler.post { if (notificationMode && currentStage == IslandStage.STAGE3_FULL) openCurrentNotification() else setStageAnimated(if (currentStage == IslandStage.STAGE3_FULL) IslandStage.STAGE1_IDLE else IslandStage.STAGE3_FULL, ExpandReason.MANUAL_USER) }

    private fun postNotificationEvent(source: String, packageName: String, notificationKey: String?, appName: String, title: String, message: String, postTime: Long, contentIntent: PendingIntent?, actions: List<Notification.Action>) {
        mainHandler.post {
            if (!AppSettings.isIslandEnabled(this)) return@post
            val now = System.currentTimeMillis()
            if (source == "AccessibilityFallback" && now - lastPrimaryEventTime < 1500L) return@post
            if (source == "NotificationListener") lastPrimaryEventTime = now
            val display = buildDisplayText(appName, title, message)
            if (ReplyEchoSuppressor.shouldSuppress(packageName, display.title, display.message)) {
                Log.d("HyperIslandPro", "Suppressing reply echo from $packageName")
                return@post
            }
            val fingerprint = "$packageName|${display.title}|${display.message}"
            if (fingerprint == lastIslandFingerprint && now - lastIslandFingerprintTime < 1000L) { scheduleAutoCollapse(); return@post }
            lastIslandFingerprint = fingerprint; lastIslandFingerprintTime = now
            notificationQueue.add(NotificationModel(packageName, notificationKey, appName, title, message, postTime, contentIntent, actions))
            if (!isProcessingQueue) processNextInQueue() else scheduleAutoCollapse()
        }
    }


    private fun postPreviewReplyAnimation(replySecond: Boolean) {
        mainHandler.post {
            if (!AppSettings.isIslandEnabled(this)) return@post
            if (this@HyperAccessibilityService.visualRoot == null) showIslandInternal()
            if (isReplyMode) {
                exitReplyMode()
                mainHandler.postDelayed({ runReplyAnimationPreview(replySecond) }, 720)
            } else {
                runReplyAnimationPreview(replySecond)
            }
        }
    }

    private fun runReplyAnimationPreview(replySecond: Boolean) {
        autoCollapseRunnable?.let { mainHandler.removeCallbacks(it) }
        notificationQueue.clear()
        isProcessingQueue = true
        notificationMode = true

        val actions = if (replySecond) {
            listOf(
                buildPreviewAction("Mark as read", 1),
                buildPreviewAction("Reply", 2)
            )
        } else {
            listOf(
                buildPreviewAction("Reply", 3),
                buildPreviewAction("Mark as read", 4)
            )
        }

        val modeName = AppSettings.getReplyAnimationModeName(AppSettings.getReplyAnimationMode(this))
        val model = NotificationModel(
            packageName = this@HyperAccessibilityService.packageName,
            notificationKey = null,
            appName = "Test Lab",
            title = if (replySecond) "Reply is second action" else "Reply is first action",
            message = modeName,
            postTime = System.currentTimeMillis(),
            contentIntent = null,
            actions = actions
        )

        val wasFull = currentStage == IslandStage.STAGE3_FULL
        updateNotificationContent(model)
        this@HyperAccessibilityService.gridRoot?.visibility = View.VISIBLE
        this@HyperAccessibilityService.gridRoot?.alpha = 1f

        if (!wasFull) {
            triggerFluidExpansion()
        } else {
            updateAllToCurrentState()
            forceRegionUpdate()
        }

        mainHandler.postDelayed({
            if (!isReplyMode) {
                this@HyperAccessibilityService.replyMorphTile?.performClick()
            }
        }, if (wasFull) 360L else 980L)
    }

    private fun buildPreviewAction(title: String, requestCode: Int): Notification.Action {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent().setClassName(packageName, "$packageName.ui.TestLabActivity")
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getActivity(this, 7300 + requestCode, launchIntent, flags)
        val icon = Icon.createWithResource(this, android.R.drawable.ic_menu_send)
        return Notification.Action.Builder(icon, title, pi).build()
    }

    private fun processNextInQueue() {
        if (isReplyMode) return // GOAL: Pause queue during reply process
        val next = notificationQueue.poll() ?: run { isProcessingQueue = false; return }
        isProcessingQueue = true; notificationMode = true
        if (currentStage == IslandStage.STAGE1_IDLE) { updateNotificationContent(next); triggerFluidExpansion(); scheduleAutoCollapse() }
        else playFluidTransitionAnimation(next)
    }

    private fun playFluidTransitionAnimation(next: NotificationModel) {
        val isFlash = notificationQueue.size >= 35
        val exit = ValueAnimator.ofFloat(0f, 1f).apply { duration = if (isFlash) 120L else 300L; interpolator = AccelerateInterpolator(); addUpdateListener { this@HyperAccessibilityService.gridRoot?.alpha = 1f - it.animatedValue as Float; this@HyperAccessibilityService.gridRoot?.translationY = it.animatedValue as Float * 20f } }
        val entry = ValueAnimator.ofFloat(0f, 1f).apply { duration = if (isFlash) 150L else 400L; interpolator = morphInterpolator; addUpdateListener { this@HyperAccessibilityService.gridRoot?.alpha = it.animatedValue as Float; this@HyperAccessibilityService.gridRoot?.translationY = -20f * (1f - it.animatedValue as Float) } }
        exit.addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(a: Animator) { updateNotificationContent(next); entry.start() } })
        entry.addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(a: Animator) { scheduleAutoCollapse() } })
        exit.start()
    }

    private fun updateNotificationContent(model: NotificationModel) {
        currentPendingIntent = model.contentIntent; currentPackageName = model.packageName; currentNotificationKey = model.notificationKey; currentReplyAction = null
        this@HyperAccessibilityService.appIconView?.setImageDrawable(loadAppIcon(model.packageName))
        this@HyperAccessibilityService.appNameText?.text = model.appName
        this@HyperAccessibilityService.timeStampText?.text = formatNotificationTime(model.postTime)
        this@HyperAccessibilityService.titleText?.text = model.title
        this@HyperAccessibilityService.messageText?.text = model.message
        this@HyperAccessibilityService.titleText?.visibility = if (model.title.isBlank()) View.GONE else View.VISIBLE
        this@HyperAccessibilityService.messageText?.visibility = if (model.message.isBlank()) View.GONE else View.VISIBLE
        setupActionTiles(model.actions); forceRegionUpdate()
    }

    private fun getActiveReplyText(): String {
        return (replyEditorEditText?.text ?: replyMorphEditText?.text ?: replyEditText?.text)?.toString()?.trim().orEmpty()
    }

    private fun setReplySendIdle() {
        sendButton?.text = "SEND"
        replyEditorSendButton?.text = "SEND"
        replyMorphSendButton?.text = "SEND"
    }

    private fun setReplySendError(message: String) {
        replyEditorEditText?.hint = message
        replyMorphEditText?.hint = message
        replyEditText?.hint = message
        setReplySendIdle()
    }

    private fun sendCurrentReply() {
        val replyText = getActiveReplyText()
        if (replyText.isBlank()) {
            setReplySendError("Type something...")
            return
        }

        val action = currentReplyAction
        val remoteInputs = action?.remoteInputs
        if (action == null || remoteInputs == null || remoteInputs.isEmpty()) {
            setReplySendError("Reply not supported")
            return
        }

        var echoId = -1L
        try {
            sendButton?.text = "SENDING"
            replyEditorSendButton?.text = "SENDING"
            replyMorphSendButton?.text = "SENDING"

            // Specific temporary echo memory: if the app immediately posts "You: <reply>",
            // the notification pipeline will suppress only that exact echo and then delete it.
            echoId = ReplyEchoSuppressor.recordSent(
                packageName = currentPackageName,
                conversationTitle = titleText?.text?.toString(),
                replyText = replyText,
                notificationKey = currentNotificationKey
            )

            val replyIntent = Intent()
            val results = Bundle()
            remoteInputs.forEach { input ->
                results.putCharSequence(input.resultKey, replyText)
            }
            RemoteInput.addResultsToIntent(remoteInputs, replyIntent, results)
            action.actionIntent.send(this@HyperAccessibilityService, 0, replyIntent)

            replyEditorEditText?.setText("")
            replyMorphEditText?.setText("")
            replyEditText?.setText("")
            titleText?.text = "Reply sent"
            messageText?.text = replyText
            exitReplyMode()
            mainHandler.postDelayed({ postCollapseIsland() }, 420)
        } catch (e: Exception) {
            ReplyEchoSuppressor.clear(echoId)
            Log.e("HyperIslandPro", "Reply send failed", e)
            setReplySendError("Send failed")
        }
    }

    // Perfect Morph — remember last reply source for reverse animation
    private var lastReplySourceRect: Rect? = null
    // IMPORTANT: never call dp()/resources in field initializers — Service context is not attached yet.
    // Calling dp() here caused: NPE getResources() while creating HyperAccessibilityService.
    private var lastReplySourceRadius: Float = 0f

    private fun setupActionTiles(actions: List<Notification.Action>) {
        this@HyperAccessibilityService.footerActions?.removeAllViews()
        replyTileAnimator?.cancel()
        replyMorphTile = null
        replyMorphLabel = null
        replyMorphEditText = null
        replyMorphSendButton = null
        replyMorphBg = null
        replyMorphOriginalWidth = 0
        replyMorphOriginalHeight = 0
        replyMorphTargetTranslationX = 0f
        this@HyperAccessibilityService.replyBar?.visibility = View.GONE

        if (actions.isEmpty()) {
            this@HyperAccessibilityService.actionScroll?.visibility = View.GONE
            return
        }

        this@HyperAccessibilityService.actionScroll?.visibility = View.VISIBLE
        this@HyperAccessibilityService.actionScroll?.alpha = 1f

        val totalWidthDp = AppSettings.getIslandExpandedWidthDp(this) - 56
        val availableWidthDp = (totalWidthDp * 0.8).toInt()
        val btnWidth = when (actions.size) {
            1 -> (availableWidthDp * 0.70).toInt()
            2 -> (availableWidthDp * 0.46).toInt()
            else -> (availableWidthDp * 0.31).toInt()
        }

        actions.forEach { action ->
            val actionTitle = action.title?.toString().orEmpty().ifBlank { "Action" }

            val isReplyAction = actionTitle.contains("Reply", true) || !action.remoteInputs.isNullOrEmpty()
            if (isReplyAction) {
                currentReplyAction = action
                // TRUE MORPH SOURCE: the Reply action tile itself contains the hidden textbox.
                // Click karne par yehi tile expand hoga — separate replyBar side/bottom se nahi aayega.
                val tileBg = createIslandBackground(dp(16).toFloat()).apply {
                    setColor(Color.parseColor("#222222"))
                    setStroke(dp(1), Color.parseColor("#444444"))
                }

                val tile = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), 0, dp(10), 0)
                    background = tileBg
                    isClickable = true
                    isFocusable = true
                    setClipChildren(false)
                    setClipToPadding(false)
                }

                val label = TextView(this).apply {
                    text = actionTitle
                    setTextColor(Color.WHITE)
                    textSize = 11f
                    gravity = Gravity.CENTER
                    typeface = Typeface.DEFAULT_BOLD
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setIncludeFontPadding(false)
                }

                val input = EditText(this).apply {
                    visibility = View.GONE
                    alpha = 0f
                    hint = "Type a reply..."
                    setHintTextColor(Color.GRAY)
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    setSingleLine(true)
                    maxLines = 1
                    background = null
                    setPadding(0, 0, dp(8), 0)
                    setIncludeFontPadding(false)
                }

                val send = TextView(this).apply {
                    visibility = View.GONE
                    alpha = 0f
                    text = "SEND"
                    setTextColor(Color.rgb(0, 150, 255))
                    textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setIncludeFontPadding(false)
                    setPadding(dp(8), 0, 0, 0)
                    setOnClickListener { sendCurrentReply() }
                }

                tile.addView(label, LinearLayout.LayoutParams(-1, -1))
                tile.addView(input, LinearLayout.LayoutParams(0, -1, 1f))
                tile.addView(send, LinearLayout.LayoutParams(-2, -1))

                tile.setOnClickListener { v ->
                    enterReplyMode(v)
                }

                replyMorphTile = tile
                replyMorphLabel = label
                replyMorphEditText = input
                replyMorphSendButton = send
                replyMorphBg = tileBg
                this@HyperAccessibilityService.replyEditText = input
                this@HyperAccessibilityService.sendButton = send

                this@HyperAccessibilityService.footerActions?.addView(
                    tile,
                    LinearLayout.LayoutParams(dp(btnWidth), dp(32)).apply { marginStart = dp(6) }
                )
            } else {
                val btn = TextView(this).apply {
                    text = actionTitle
                    setTextColor(Color.WHITE)
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setPadding(dp(12), 0, dp(12), 0)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    background = createIslandBackground(dp(16).toFloat()).apply {
                        setColor(Color.parseColor("#222222"))
                        setStroke(dp(1), Color.parseColor("#444444"))
                    }
                    isClickable = true
                    setOnClickListener {
                        val oldT = text
                        text = "✓ $oldT"
                        setTextColor(Color.GREEN)
                        postDelayed({
                            try {
                                action.actionIntent.send()
                                postCollapseIsland()
                            } catch (_: Exception) {
                                text = oldT
                                setTextColor(Color.WHITE)
                            }
                        }, 500)
                    }
                }

                this@HyperAccessibilityService.footerActions?.addView(
                    btn,
                    LinearLayout.LayoutParams(dp(btnWidth), dp(32)).apply { marginStart = dp(6) }
                )
            }
        }
    }

    // Perfect Morph state
    private var lastReplySourceView: View? = null
    private var replyModeSessionId: Int = 0
    private var replyTileAnimator: Animator? = null
    private var replyMorphTile: LinearLayout? = null
    private var replyMorphLabel: TextView? = null
    private var replyMorphEditText: EditText? = null
    private var replyMorphSendButton: TextView? = null
    private var replyMorphBg: GradientDrawable? = null
    private var replyMorphOriginalWidth: Int = 0
    private var replyMorphOriginalHeight: Int = 0
    private var replyMorphTargetTranslationX: Float = 0f

    private fun shouldUseGhostReplyMorph(mode: Int): Boolean {
        return mode == AppSettings.REPLY_ANIM_MAGNETIC_DOCK ||
            mode == AppSettings.REPLY_ANIM_LIQUID_FILL ||
            mode == AppSettings.REPLY_ANIM_ELASTIC_BUBBLE
    }

    private fun rectInLayer(view: View, layer: View): RectF {
        val viewLocation = IntArray(2)
        val layerLocation = IntArray(2)
        view.getLocationOnScreen(viewLocation)
        layer.getLocationOnScreen(layerLocation)
        val left = (viewLocation[0] - layerLocation[0]).toFloat()
        val top = (viewLocation[1] - layerLocation[1]).toFloat()
        return RectF(left, top, left + view.width, top + view.height)
    }

    private fun scaledRect(source: RectF, scale: Float): RectF {
        val dx = source.width() * (1f - scale) * 0.5f
        val dy = source.height() * (1f - scale) * 0.5f
        return RectF(source.left + dx, source.top + dy, source.right - dx, source.bottom - dy)
    }

    private fun segment(value: Float, start: Float, end: Float): Float {
        if (end <= start) return if (value >= end) 1f else 0f
        return ((value - start) / (end - start)).coerceIn(0f, 1f)
    }

    private fun interpolateRectEdges(from: RectF, to: RectF, h: Float, v: Float): RectF {
        val bounds = RectF(0f, 0f, (morphLayer?.width ?: islandView?.width ?: 0).toFloat(), (morphLayer?.height ?: islandView?.height ?: 0).toFloat())
        val out = RectF(
            lerp(from.left, to.left, h),
            lerp(from.top, to.top, v),
            lerp(from.right, to.right, h),
            lerp(from.bottom, to.bottom, v)
        )
        if (!bounds.isEmpty) {
            out.left = out.left.coerceAtLeast(bounds.left + dp(1))
            out.top = out.top.coerceAtLeast(bounds.top + dp(1))
            out.right = out.right.coerceAtMost(bounds.right - dp(1))
            out.bottom = out.bottom.coerceAtMost(bounds.bottom - dp(1))
        }
        return out
    }

    private fun getLiquidEditorGapPx(): Float = dp(13).toFloat()

    private fun getLiquidEditorRadiusPx(): Float {
        // Final calibrated Liquid Parallax radius from device tuning.
        return dp(26).toFloat()
    }

    private fun styleGhostEditorForMode(mode: Int) {
        val layer = replyEditorLayer ?: return
        val bg = if (mode == AppSettings.REPLY_ANIM_LIQUID_FILL) {
            // Permanent Liquid Parallax finish:
            // The real editor keeps the glossy/glass surface after the ghost hands off; no flat fade-out.
            GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    Color.parseColor("#1B252E"),
                    Color.parseColor("#111820"),
                    Color.parseColor("#0B0F14")
                )
            ).apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = getLiquidEditorRadiusPx()
                setStroke(dp(1), Color.argb(118, 205, 232, 255))
            }
        } else {
            val fill = when (mode) {
                AppSettings.REPLY_ANIM_ELASTIC_BUBBLE -> Color.parseColor("#171717")
                else -> Color.parseColor("#111214")
            }
            val stroke = when (mode) {
                AppSettings.REPLY_ANIM_ELASTIC_BUBBLE -> Color.argb(70, 255, 255, 255)
                else -> Color.argb(55, 255, 255, 255)
            }
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(fill)
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), stroke)
            }
        }
        layer.background = bg
    }

    private fun interpolateAnchoredRect(from: RectF, to: RectF, raw: Float, mode: Int): RectF {
        val p = raw.coerceIn(0f, 1f)
        val growLeft = from.centerX() > to.centerX() + dp(10)
        val growRight = from.centerX() < to.centerX() - dp(10)

        val travel = when (mode) {
            AppSettings.REPLY_ANIM_LIQUID_FILL -> ghostMaterialInterpolator.getInterpolation(segment(p, 0.00f, 1.00f))
            AppSettings.REPLY_ANIM_ELASTIC_BUBBLE -> ghostMagneticInterpolator.getInterpolation(segment(p, 0.00f, 0.78f))
            else -> ghostMagneticInterpolator.getInterpolation(segment(p, 0.00f, 0.92f))
        }
        val anchor = when (mode) {
            AppSettings.REPLY_ANIM_LIQUID_FILL -> ghostMaterialInterpolator.getInterpolation(segment(p, 0.10f, 0.82f))
            AppSettings.REPLY_ANIM_ELASTIC_BUBBLE -> ghostMaterialInterpolator.getInterpolation(segment(p, 0.18f, 0.70f))
            else -> ghostMaterialInterpolator.getInterpolation(segment(p, 0.08f, 0.70f))
        }
        val vertical = when (mode) {
            AppSettings.REPLY_ANIM_LIQUID_FILL -> ghostMaterialInterpolator.getInterpolation(segment(p, 0.18f, 1.00f))
            AppSettings.REPLY_ANIM_ELASTIC_BUBBLE -> ghostMaterialInterpolator.getInterpolation(segment(p, 0.10f, 0.82f))
            else -> ghostMaterialInterpolator.getInterpolation(segment(p, 0.08f, 0.94f))
        }

        val leftP: Float
        val rightP: Float
        when {
            growLeft -> {
                // Reply is on right side: right edge is the origin anchor, left edge travels.
                leftP = travel
                rightP = anchor
            }
            growRight -> {
                // Reply is on left side: left edge is the origin anchor, right edge travels.
                leftP = anchor
                rightP = travel
            }
            else -> {
                leftP = travel
                rightP = travel
            }
        }

        val bounds = RectF(0f, 0f, (morphLayer?.width ?: islandView?.width ?: 0).toFloat(), (morphLayer?.height ?: islandView?.height ?: 0).toFloat())
        val out = RectF(
            lerp(from.left, to.left, leftP),
            lerp(from.top, to.top, vertical),
            lerp(from.right, to.right, rightP),
            lerp(from.bottom, to.bottom, vertical)
        )
        if (!bounds.isEmpty) {
            out.left = out.left.coerceAtLeast(bounds.left + dp(1))
            out.top = out.top.coerceAtLeast(bounds.top + dp(1))
            out.right = out.right.coerceAtMost(bounds.right - dp(1))
            out.bottom = out.bottom.coerceAtMost(bounds.bottom - dp(1))
        }
        return out
    }

    private fun prepareGhostEditor(target: RectF) {
        val layer = replyEditorLayer ?: return
        val lp = (layer.layoutParams as? FrameLayout.LayoutParams) ?: FrameLayout.LayoutParams(target.width().roundToInt(), target.height().roundToInt())
        lp.width = target.width().roundToInt().coerceAtLeast(dp(180))
        lp.height = target.height().roundToInt().coerceAtLeast(dp(40))
        lp.leftMargin = target.left.roundToInt()
        lp.topMargin = target.top.roundToInt()
        layer.layoutParams = lp
        layer.alpha = 0f
        layer.visibility = View.INVISIBLE
        replyEditorEditText?.isCursorVisible = false
        replyEditorEditText?.setText("")
    }

    private fun lockGhostEditorVisible(sessionId: Int) {
        val editor = replyEditorLayer ?: return
        if (!isReplyMode || !isGhostReplyMode || sessionId != replyModeSessionId) return
        morphLayer?.bringToFront()
        editor.visibility = View.VISIBLE
        editor.alpha = 1f
        editor.scaleX = 1f
        editor.scaleY = 1f
        editor.bringToFront()
        replyEditorEditText?.isCursorVisible = true
        forceRegionUpdate()
    }

    private fun startGhostReplyMorph(
        sessionId: Int,
        tile: LinearLayout,
        footer: LinearLayout,
        actionView: HorizontalScrollView,
        labelText: String,
        mode: Int
    ) {
        val layer = morphLayer ?: return
        val ghost = replyGhostView ?: return
        val editor = replyEditorLayer ?: return
        val editorInput = replyEditorEditText ?: return
        val editorSend = replyEditorSendButton ?: return

        isGhostReplyMode = true
        activeGhostReplyMode = mode
        ghostSourceView = tile
        this@HyperAccessibilityService.replyEditText = editorInput
        this@HyperAccessibilityService.sendButton = editorSend

        ghostSourceRect = rectInLayer(tile, layer)
        val actionRect = rectInLayer(actionView, layer)
        val isLiquidMode = mode == AppSettings.REPLY_ANIM_LIQUID_FILL
        val liquidRadius = getLiquidEditorRadiusPx()
        val editorH = if (isLiquidMode) dp(44).toFloat() else dp(44).toFloat()
        val edgeGap = if (isLiquidMode) getLiquidEditorGapPx() else dp(6).toFloat()
        val leftGap = if (isLiquidMode) dp(0).toFloat() else edgeGap
        val safeLeft = (actionRect.left + leftGap).coerceAtLeast(dp(1).toFloat())
        val safeRight = if (isLiquidMode) {
            // Liquid default: make right gap and bottom gap equal for a balanced docked editor.
            (layer.width - edgeGap).coerceAtMost((layer.width - dp(1)).toFloat())
        } else {
            (actionRect.right - edgeGap).coerceAtMost((layer.width - dp(1)).toFloat())
        }
        ghostTargetRect = if (isLiquidMode) {
            val bottom = (layer.height - edgeGap).coerceAtLeast(editorH + dp(1))
            RectF(safeLeft, bottom - editorH, safeRight, bottom)
        } else {
            val centerY = actionRect.centerY().coerceIn(editorH / 2f + dp(1), layer.height - editorH / 2f - dp(1))
            RectF(safeLeft, centerY - editorH / 2f, safeRight, centerY + editorH / 2f)
        }
        styleGhostEditorForMode(mode)
        prepareGhostEditor(ghostTargetRect)

        val startRect = scaledRect(ghostSourceRect, 0.965f)
        val startR = ghostSourceRect.height() / 2f
        val endR = if (isLiquidMode) liquidRadius else dp(16).toFloat()
        val duration = when (mode) {
            AppSettings.REPLY_ANIM_LIQUID_FILL -> 560L
            AppSettings.REPLY_ANIM_ELASTIC_BUBBLE -> 390L
            else -> 420L
        }
        val ghostVisualStyle = when (mode) {
            AppSettings.REPLY_ANIM_LIQUID_FILL -> 1
            AppSettings.REPLY_ANIM_ELASTIC_BUBBLE -> 2
            else -> 0
        }

        // Ghost first, then source invisible: no one-frame blank/double source.
        morphLayer?.bringToFront()
        ghost.bringToFront()
        ghost.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        ghost.alpha = 1f
        ghost.setLabelAnchor(startRect.centerX(), startRect.centerY())
        ghost.setGhostState(startRect, startR, 1f, 0f, 0f, 0f, 0f, ghostVisualStyle, labelText)
        tile.visibility = View.INVISIBLE
        tile.alpha = 0f
        tile.scaleX = 1f
        tile.scaleY = 1f

        // Siblings are not on a random fade timeline anymore.
        // Their alpha is derived from the capsule overlap each frame: the growing surface pushes them aside.
        val siblingRects = mutableListOf<Pair<View, RectF>>()
        for (i in 0 until footer.childCount) {
            val child = footer.getChildAt(i)
            if (child !== tile) {
                child.isEnabled = false
                child.animate()?.setListener(null)
                child.animate()?.cancel()
                child.alpha = 1f
                child.translationY = 0f
                siblingRects.add(child to rectInLayer(child, layer))
            }
        }

        ghostAnimator?.cancel()
        ghostAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            interpolator = ghostMaterialInterpolator
            addUpdateListener { va ->
                if (!isReplyMode || !isGhostReplyMode || sessionId != replyModeSessionId) return@addUpdateListener
                val raw = va.animatedFraction
                val h = when (mode) {
                    AppSettings.REPLY_ANIM_LIQUID_FILL -> ghostMaterialInterpolator.getInterpolation(segment(raw, 0.0f, 0.98f))
                    AppSettings.REPLY_ANIM_ELASTIC_BUBBLE -> ghostMagneticInterpolator.getInterpolation(segment(raw, 0.0f, 0.74f))
                    else -> ghostMagneticInterpolator.getInterpolation(segment(raw, 0.0f, 0.92f))
                }
                val v = when (mode) {
                    AppSettings.REPLY_ANIM_LIQUID_FILL -> ghostMaterialInterpolator.getInterpolation(segment(raw, 0.18f, 1.0f))
                    AppSettings.REPLY_ANIM_ELASTIC_BUBBLE -> ghostMaterialInterpolator.getInterpolation(segment(raw, 0.10f, 0.82f))
                    else -> ghostMaterialInterpolator.getInterpolation(segment(raw, 0.08f, 0.94f))
                }
                val surface = when (mode) {
                    AppSettings.REPLY_ANIM_LIQUID_FILL -> ghostMaterialInterpolator.getInterpolation(segment(raw, 0.06f, 0.96f))
                    AppSettings.REPLY_ANIM_ELASTIC_BUBBLE -> ghostMagneticInterpolator.getInterpolation(segment(raw, 0.12f, 0.78f))
                    else -> ghostMaterialInterpolator.getInterpolation(segment(raw, 0.12f, 0.88f))
                }
                val labelA = 1f - ghostMaterialInterpolator.getInterpolation(segment(raw, 0.04f, if (mode == AppSettings.REPLY_ANIM_LIQUID_FILL) 0.26f else 0.34f))
                val hintA = ghostMaterialInterpolator.getInterpolation(segment(raw, if (mode == AppSettings.REPLY_ANIM_LIQUID_FILL) 0.62f else if (mode == AppSettings.REPLY_ANIM_ELASTIC_BUBBLE) 0.40f else 0.48f, 0.90f))
                val sendA = ghostMagneticInterpolator.getInterpolation(segment(raw, if (mode == AppSettings.REPLY_ANIM_LIQUID_FILL) 0.74f else if (mode == AppSettings.REPLY_ANIM_ELASTIC_BUBBLE) 0.52f else 0.62f, 1.0f))
                val rect = interpolateAnchoredRect(startRect, ghostTargetRect, raw, mode)
                siblingRects.forEach { pair ->
                    val child = pair.first
                    val childRect = pair.second
                    val overlap = (minOf(rect.right, childRect.right) - maxOf(rect.left, childRect.left)).coerceAtLeast(0f)
                    val push = (overlap / childRect.width().coerceAtLeast(1f)).coerceIn(0f, 1f)
                    child.alpha = 1f - push
                    child.translationY = dp(5).toFloat() * push
                    if (push >= 0.98f) child.visibility = View.INVISIBLE else child.visibility = View.VISIBLE
                }
                ghost.setGhostState(
                    bounds = rect,
                    cornerRadius = lerp(startR, endR, surface),
                    replyLabelAlpha = labelA,
                    placeholderAlpha = hintA,
                    sendButtonAlpha = sendA,
                    surface = surface,
                    highlight = segment(raw, if (mode == AppSettings.REPLY_ANIM_LIQUID_FILL) 0.12f else 0.18f, if (mode == AppSettings.REPLY_ANIM_LIQUID_FILL) 0.92f else 0.82f),
                    visualStyle = ghostVisualStyle,
                    label = labelText
                )
                if (raw >= 0.82f && editor.visibility != View.VISIBLE) {
                    editor.visibility = View.VISIBLE
                    editor.alpha = 0f
                    editor.animate()?.alpha(1f)?.setDuration(90L)?.setInterpolator(morphInterpolator)?.start()
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!isReplyMode || !isGhostReplyMode || sessionId != replyModeSessionId) return
                    // Hard handoff: real editor must stay on top after ghost finishes.
                    // Fixes bug where ghost disappeared but textbox/editor also appeared gone.
                    lockGhostEditorVisible(sessionId)
                    ghost.bringToFront()
                    ghost.animate()?.alpha(0f)?.setDuration(70L)?.setInterpolator(morphInterpolator)?.setListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            if (isGhostReplyMode) {
                                ghost.clearGhost()
                                ghost.alpha = 1f
                                ghost.setLayerType(View.LAYER_TYPE_NONE, null)
                                lockGhostEditorVisible(sessionId)
                            }
                        }
                    })?.start()
                    mainHandler.postDelayed({ lockGhostEditorVisible(sessionId) }, 90)
                    mainHandler.postDelayed({ lockGhostEditorVisible(sessionId) }, 220)
                    mainHandler.postDelayed({ lockGhostEditorVisible(sessionId) }, 520)
                    if (mode == AppSettings.REPLY_ANIM_ELASTIC_BUBBLE) {
                        editor.animate()?.setListener(null)?.cancel()
                        editor.animate()
                            ?.withLayer()
                            ?.scaleX(1.012f)
                            ?.scaleY(1.028f)
                            ?.setDuration(95L)
                            ?.setInterpolator(ghostMagneticInterpolator)
                            ?.withEndAction {
                                editor.animate()
                                    ?.withLayer()
                                    ?.scaleX(1f)
                                    ?.scaleY(1f)
                                    ?.setDuration(145L)
                                    ?.setInterpolator(ghostReverseInterpolator)
                                    ?.start()
                            }
                            ?.start()
                    }
                    forceRegionUpdate()
                }
            })
            start()
        }
    }

    private fun reverseGhostReplyMode() {
        if (!isGhostReplyMode) return
        val oldSession = ++replyModeSessionId
        isReplyMode = false
        val layer = morphLayer
        val ghost = replyGhostView
        val editor = replyEditorLayer
        val input = replyEditorEditText
        val source = ghostSourceView
        val footer = footerActions
        if (layer == null || ghost == null || editor == null || input == null || source == null) {
            isGhostReplyMode = false
            restoreAfterGhostReverse()
            return
        }

        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        input.isCursorVisible = false
        try { imm.hideSoftInputFromWindow(input.windowToken, 0) } catch (_: Exception) {}
        input.clearFocus()

        val currentSourceRect = rectInLayer(source, layer)
        val targetStart = RectF(ghostTargetRect)
        val targetEnd = scaledRect(currentSourceRect, 0.965f)
        val startR = dp(16).toFloat()
        val endR = currentSourceRect.height() / 2f

        ghost.alpha = 1f
        ghost.setGhostState(targetStart, startR, 0f, 1f, 1f, 1f, 0f, 0, "Reply")
        ghost.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        ghost.bringToFront()
        editor.animate()?.setListener(null)
        editor.animate()?.cancel()
        editor.animate()?.alpha(0f)?.setDuration(70L)?.setInterpolator(morphInterpolator)?.setListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                editor.visibility = View.INVISIBLE
                editor.alpha = 1f
            }
        })?.start()

        ghostAnimator?.cancel()
        ghostAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 330L
            interpolator = ghostReverseInterpolator
            addUpdateListener { va ->
                if (oldSession != replyModeSessionId) return@addUpdateListener
                val raw = va.animatedFraction
                val h = ghostReverseInterpolator.getInterpolation(segment(raw, 0.0f, 0.94f))
                val v = ghostReverseInterpolator.getInterpolation(segment(raw, 0.04f, 1.0f))
                val labelA = ghostMaterialInterpolator.getInterpolation(segment(raw, 0.62f, 1.0f))
                val hintA = 1f - ghostMaterialInterpolator.getInterpolation(segment(raw, 0.0f, 0.42f))
                val sendA = 1f - ghostMaterialInterpolator.getInterpolation(segment(raw, 0.0f, 0.34f))
                val rect = interpolateAnchoredRect(targetStart, targetEnd, raw, activeGhostReplyMode)
                ghost.setGhostState(rect, lerp(startR, endR, raw), labelA, hintA, sendA, 1f - raw, 0f, 0, "Reply")
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (oldSession != replyModeSessionId) return
                    source.visibility = View.VISIBLE
                    source.alpha = 1f
                    source.scaleX = 1f
                    source.scaleY = 1f
                    source.translationX = 0f
                    source.translationY = 0f
                    source.isEnabled = true
                    source.isClickable = true
                    ghost.clearGhost()
                    ghost.setLayerType(View.LAYER_TYPE_NONE, null)
                    isGhostReplyMode = false
                    restoreAfterGhostReverse()
                }
            })
            start()
        }

        footer?.let { row ->
            mainHandler.postDelayed({
                if (oldSession != replyModeSessionId) return@postDelayed
                for (i in 0 until row.childCount) {
                    val child = row.getChildAt(i)
                    child.isEnabled = true
                    child.visibility = View.VISIBLE
                    child.animate()?.setListener(null)
                    child.animate()?.cancel()
                    child.animate()?.alpha(1f)?.translationY(0f)?.setDuration(240L)?.setInterpolator(morphInterpolator)?.start()
                }
            }, 110)
        }
    }

    private fun restoreAfterGhostReverse() {
        replyEditorLayer?.visibility = View.INVISIBLE
        replyEditorLayer?.alpha = 1f
        replyEditorEditText?.setText("")
        val p = visualParams
        if (p != null) {
            p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
            visualRoot?.postDelayed({
                try { windowManager?.updateViewLayout(visualRoot, p) } catch (_: Exception) {}
                forceRegionUpdate()
            }, 80)
        }
        mainHandler.postDelayed({
            forceRegionUpdate()
            if (notificationQueue.isNotEmpty()) processNextInQueue()
        }, 420)
    }

    private fun enterReplyMode(sourceView: View? = null) {
        if (isReplyMode) return
        isReplyMode = true
        replyModeSessionId++
        val sessionId = replyModeSessionId
        setReplySendIdle()
        replyEditorEditText?.hint = "Type a reply..."
        replyMorphEditText?.hint = "Type a reply..."
        replyEditText?.hint = "Type a reply..."
        autoCollapseRunnable?.let { mainHandler.removeCallbacks(it) }

        val p = visualParams
        if (p == null) {
            isReplyMode = false
            return
        }
        p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        p.flags = p.flags and WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM.inv()
        p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        try { windowManager?.updateViewLayout(visualRoot, p) } catch (_: Exception) {}

        val actionView = this@HyperAccessibilityService.actionScroll
        val footer = this@HyperAccessibilityService.footerActions
        val tile = (sourceView as? LinearLayout) ?: replyMorphTile
        val label = replyMorphLabel
        val editView = replyMorphEditText ?: this@HyperAccessibilityService.replyEditText
        val sendView = replyMorphSendButton ?: this@HyperAccessibilityService.sendButton
        val tileBg = replyMorphBg ?: (tile?.background as? GradientDrawable)

        // Old separate replyBar must never appear. User wants Reply tab itself -> textbox.
        this@HyperAccessibilityService.replyBar?.animate()?.cancel()
        this@HyperAccessibilityService.replyBar?.visibility = View.GONE

        // Ensure grid/action row stays visible — no blank, no side textbox.
        this@HyperAccessibilityService.gridRoot?.visibility = View.VISIBLE
        this@HyperAccessibilityService.gridRoot?.alpha = 1f
        actionView?.animate()?.setListener(null)
        actionView?.animate()?.cancel()
        actionView?.visibility = View.VISIBLE
        actionView?.alpha = 1f

        if (tile != null && actionView != null && footer != null && label != null && editView != null && sendView != null) {
            lastReplySourceView = tile
            replyTileAnimator?.cancel()
            tile.animate()?.setListener(null)
            tile.animate()?.cancel()
            tile.visibility = View.VISIBLE
            tile.alpha = 1f
            tile.scaleX = 1f
            tile.scaleY = 1f
            tile.translationX = 0f
            tile.translationY = 0f
            tile.isClickable = false

            // Fade out only the other action tabs. Reply tile stays there and becomes textbox.
            for (i in 0 until footer.childCount) {
                val child = footer.getChildAt(i)
                child.animate()?.setListener(null)
                child.animate()?.cancel()
                if (child !== tile) {
                    child.animate()
                        ?.alpha(0f)
                        ?.scaleX(0.92f)
                        ?.scaleY(0.92f)
                        ?.setDuration(180)
                        ?.setInterpolator(collapseInterpolator)
                        ?.start()
                } else {
                    child.alpha = 1f
                    child.scaleX = 1f
                    child.scaleY = 1f
                }
            }

            tile.post {
                if (!isReplyMode || sessionId != replyModeSessionId) return@post

                val startW = (tile.layoutParams?.width ?: 0).takeIf { it > 0 } ?: tile.width.takeIf { it > 0 } ?: dp(92)
                val startH = (tile.layoutParams?.height ?: 0).takeIf { it > 0 } ?: tile.height.takeIf { it > 0 } ?: dp(32)
                if (replyMorphOriginalWidth <= 0) replyMorphOriginalWidth = startW
                if (replyMorphOriginalHeight <= 0) replyMorphOriginalHeight = startH

                val targetW = (actionView.width - dp(12)).coerceAtLeast(dp(220))
                val targetH = dp(40)

                val replyAnimMode = AppSettings.getReplyAnimationMode(this)
                val useDocking = replyAnimMode == AppSettings.REPLY_ANIM_MAGNETIC_DOCK ||
                    replyAnimMode == AppSettings.REPLY_ANIM_LIQUID_FILL ||
                    replyAnimMode == AppSettings.REPLY_ANIM_ELASTIC_BUBBLE ||
                    replyAnimMode == AppSettings.REPLY_ANIM_MINIMAL_PRO
                val morphDuration = when (replyAnimMode) {
                    AppSettings.REPLY_ANIM_CLASSIC_LAYOUT -> 780L
                    AppSettings.REPLY_ANIM_GPU_SMOOTH -> 680L
                    AppSettings.REPLY_ANIM_MAGNETIC_DOCK -> 760L
                    AppSettings.REPLY_ANIM_LIQUID_FILL -> 920L
                    AppSettings.REPLY_ANIM_ELASTIC_BUBBLE -> 720L
                    AppSettings.REPLY_ANIM_MINIMAL_PRO -> 360L
                    else -> 760L
                }
                val inputDelay = when (replyAnimMode) {
                    AppSettings.REPLY_ANIM_LIQUID_FILL -> 330L
                    AppSettings.REPLY_ANIM_ELASTIC_BUBBLE -> 240L
                    AppSettings.REPLY_ANIM_MINIMAL_PRO -> 90L
                    else -> 210L
                }
                val inputDuration = when (replyAnimMode) {
                    AppSettings.REPLY_ANIM_LIQUID_FILL -> 520L
                    AppSettings.REPLY_ANIM_MINIMAL_PRO -> 210L
                    else -> 420L
                }
                val sendDelay = when (replyAnimMode) {
                    AppSettings.REPLY_ANIM_LIQUID_FILL -> 480L
                    AppSettings.REPLY_ANIM_MINIMAL_PRO -> 150L
                    else -> 330L
                }
                val sendDuration = when (replyAnimMode) {
                    AppSettings.REPLY_ANIM_MINIMAL_PRO -> 180L
                    else -> 320L
                }

                // Magnetic Dock Morph family:
                // If Reply is second/right-side action, expanding from its own left would go outside island.
                // Docking modes slide the SAME tile left into the safe textbox lane while expanding.
                val safeLeft = dp(6)
                val tileLeftInActionRow = tile.left
                replyMorphTargetTranslationX = if (useDocking) (safeLeft - tileLeftInActionRow).toFloat() else 0f

                val startR = tileBg?.cornerRadius ?: dp(16).toFloat()
                val endR = dp(12).toFloat()
                lastReplySourceRadius = startR
                if (replyAnimMode == AppSettings.REPLY_ANIM_LIQUID_FILL) {
                    tileBg?.setStroke(dp(1), Color.rgb(0, 150, 255))
                }

                if (shouldUseGhostReplyMorph(replyAnimMode)) {
                    startGhostReplyMorph(
                        sessionId = sessionId,
                        tile = tile,
                        footer = footer,
                        actionView = actionView,
                        labelText = label.text?.toString().orEmpty().ifBlank { "Reply" },
                        mode = replyAnimMode
                    )
                    return@post
                }

                label.visibility = View.VISIBLE
                label.alpha = 1f
                editView.visibility = View.VISIBLE
                sendView.visibility = View.VISIBLE
                editView.alpha = 0f
                sendView.alpha = 0f

                // Keep inner layout stable. No per-frame child width relayout.
                val labelStartWidth = (targetW - tile.paddingLeft - tile.paddingRight).coerceAtLeast(dp(40))
                (label.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                    lp.width = labelStartWidth
                    lp.weight = 0f
                    label.layoutParams = lp
                }
                (editView.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                    lp.width = 0
                    lp.weight = 1f
                    editView.layoutParams = lp
                }

                if (replyAnimMode == AppSettings.REPLY_ANIM_CLASSIC_LAYOUT) {
                    // Mode 1: Classic Layout Morph — old experimental style.
                    // This intentionally changes internal layout size every frame for A/B testing.
                    label.alpha = 0f
                    label.visibility = View.GONE
                    tile.pivotX = 0f
                    tile.pivotY = startH / 2f
                    tile.scaleX = 1f
                    tile.scaleY = 1f
                    tile.translationX = 0f

                    replyTileAnimator?.cancel()
                    val classicAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = morphDuration
                        interpolator = morphInterpolator
                        addUpdateListener { va ->
                            if (isReplyMode && sessionId == replyModeSessionId) {
                                val t = (va.animatedValue as Float).coerceIn(0f, 1f)
                                (tile.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                                    lp.width = lerpEven(startW, targetW, t)
                                    lp.height = lerpEven(startH, targetH, t)
                                    tile.layoutParams = lp
                                }
                                tile.translationX = lerp(0f, replyMorphTargetTranslationX, t)
                                tileBg?.cornerRadius = lerp(startR, endR, t)
                            }
                        }
                        addListener(object : AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: Animator) {
                                if (!isReplyMode || sessionId != replyModeSessionId) return
                                tile.translationX = replyMorphTargetTranslationX
                                editView.alpha = 1f
                                sendView.alpha = 1f
                                tileBg?.cornerRadius = endR
                                editView.requestFocus()
                                editView.isCursorVisible = true
                                forceRegionUpdate()
                            }
                        })
                    }
                    replyTileAnimator = classicAnimator
                    classicAnimator.start()
                    editView.animate()?.alpha(1f)?.setStartDelay(inputDelay)?.setDuration(inputDuration)?.setInterpolator(morphInterpolator)?.start()
                    sendView.animate()?.alpha(1f)?.setStartDelay(sendDelay)?.setDuration(sendDuration)?.setInterpolator(morphInterpolator)?.start()
                    return@post
                }

                // SMOOTH FIX:
                // Do NOT update layoutParams.width on every frame — that causes re-measure jank.
                // Set final layout once, then GPU-scale the same Reply tile from button size to textbox size.
                (tile.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                    lp.width = targetW
                    lp.height = targetH
                    tile.layoutParams = lp
                }
                tile.pivotX = 0f
                tile.pivotY = targetH / 2f
                tile.translationX = 0f
                tile.scaleX = (startW.toFloat() / targetW.coerceAtLeast(1)).coerceIn(0.15f, 1f)
                tile.scaleY = (startH.toFloat() / targetH.coerceAtLeast(1)).coerceIn(0.15f, 1f)

                replyTileAnimator?.cancel()
                val radiusAnimator = ValueAnimator.ofFloat(startR, endR).apply {
                    duration = morphDuration
                    interpolator = morphInterpolator
                    addUpdateListener { va ->
                        tileBg?.cornerRadius = va.animatedValue as Float
                    }
                }
                replyTileAnimator = radiusAnimator
                radiusAnimator.start()

                label.animate()?.setListener(null)
                editView.animate()?.setListener(null)
                sendView.animate()?.setListener(null)
                label.animate()?.cancel()
                editView.animate()?.cancel()
                sendView.animate()?.cancel()

                // IMPORTANT: parent tile is GPU-scaled during morph.
                // If "Reply" text stays visible inside a scaled parent, it becomes squeezed/weird.
                // Hide text immediately; only the tile shape morphs into textbox.
                label.alpha = 0f
                label.visibility = View.GONE

                editView.animate()
                    ?.alpha(1f)
                    ?.setStartDelay(inputDelay)
                    ?.setDuration(inputDuration)
                    ?.setInterpolator(morphInterpolator)
                    ?.start()

                sendView.animate()
                    ?.alpha(1f)
                    ?.setStartDelay(sendDelay)
                    ?.setDuration(sendDuration)
                    ?.setInterpolator(morphInterpolator)
                    ?.start()

                tile.animate()
                    ?.setListener(null)
                    ?.cancel()
                tile.animate()
                    ?.withLayer()
                    ?.translationX(replyMorphTargetTranslationX)
                    ?.scaleX(1f)
                    ?.scaleY(1f)
                    ?.setDuration(morphDuration)
                    ?.setInterpolator(morphInterpolator)
                    ?.setListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            if (!isReplyMode || sessionId != replyModeSessionId) return
                            tile.translationX = replyMorphTargetTranslationX
                            tile.scaleX = 1f
                            tile.scaleY = 1f
                            label.visibility = View.GONE
                            editView.alpha = 1f
                            sendView.alpha = 1f
                            tileBg?.cornerRadius = endR
                            editView.requestFocus()
                            editView.isCursorVisible = true
                            if (replyAnimMode == AppSettings.REPLY_ANIM_ELASTIC_BUBBLE) {
                                tile.animate()?.setListener(null)?.cancel()
                                tile.animate()
                                    ?.withLayer()
                                    ?.scaleY(1.025f)
                                    ?.setDuration(90L)
                                    ?.setInterpolator(morphInterpolator)
                                    ?.withEndAction {
                                        tile.animate()?.withLayer()?.scaleY(1f)?.setDuration(120L)?.setInterpolator(collapseInterpolator)?.start()
                                    }
                                    ?.start()
                            }
                            forceRegionUpdate()
                        }
                    })
                    ?.start()
            }
        } else {
            // Rare fallback: keep UI safe, but do not show the old below/side-growing replyBar.
            actionView?.visibility = View.VISIBLE
            actionView?.alpha = 1f
        }

        // INTERCEPTOR — Off-Switch full screen
        forceRegionUpdate()
        mainHandler.postDelayed({ forceRegionUpdate() }, 80)
        mainHandler.postDelayed({ forceRegionUpdate() }, 350)

        // Keyboard — delayed so the premium morph is visually established first.
        mainHandler.postDelayed({
            if (!isReplyMode || sessionId != replyModeSessionId) return@postDelayed
            val activeEdit = replyEditText ?: replyMorphEditText ?: this@HyperAccessibilityService.replyEditText ?: return@postDelayed
            activeEdit.requestFocus()
            activeEdit.isCursorVisible = true
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.restartInput(activeEdit)
            try { performGlobalAction(GLOBAL_ACTION_SHOW_KEYBOARD) } catch (_: Exception) {}
            imm.showSoftInput(activeEdit, InputMethodManager.SHOW_IMPLICIT)
            mainHandler.postDelayed({
                if (!isReplyMode || sessionId != replyModeSessionId) return@postDelayed
                try { imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0) } catch (_: Exception) {}
            }, 120)
        }, 540)
    }

    private fun exitReplyMode() {
        if (!isReplyMode && !isGhostReplyMode) return
        if (isGhostReplyMode) {
            reverseGhostReplyMode()
            return
        }

        isReplyMode = false
        replyModeSessionId++
        val sessionId = replyModeSessionId

        val activeEdit = replyMorphEditText ?: this@HyperAccessibilityService.replyEditText
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        try {
            imm.hideSoftInputFromWindow(activeEdit?.windowToken, 0)
        } catch (_: Exception) {}
        activeEdit?.clearFocus()

        val actionView = this@HyperAccessibilityService.actionScroll
        val footer = this@HyperAccessibilityService.footerActions
        val tile = replyMorphTile ?: (lastReplySourceView as? LinearLayout)
        val label = replyMorphLabel
        val editView = replyMorphEditText ?: this@HyperAccessibilityService.replyEditText
        val sendView = replyMorphSendButton ?: this@HyperAccessibilityService.sendButton
        val tileBg = replyMorphBg ?: (tile?.background as? GradientDrawable)

        this@HyperAccessibilityService.replyBar?.animate()?.cancel()
        this@HyperAccessibilityService.replyBar?.visibility = View.GONE

        actionView?.animate()?.setListener(null)
        actionView?.animate()?.cancel()
        actionView?.visibility = View.VISIBLE
        actionView?.alpha = 1f

        if (tile != null && footer != null && label != null && editView != null && sendView != null) {
            replyTileAnimator?.cancel()
            tile.animate()?.setListener(null)
            tile.animate()?.cancel()
            tile.visibility = View.VISIBLE
            tile.alpha = 1f
            tile.scaleX = 1f
            tile.scaleY = 1f
            tile.translationX = 0f
            tile.translationY = 0f

            val startW = (tile.layoutParams?.width ?: 0).takeIf { it > 0 } ?: tile.width.takeIf { it > 0 } ?: dp(220)
            val startH = (tile.layoutParams?.height ?: 0).takeIf { it > 0 } ?: tile.height.takeIf { it > 0 } ?: dp(40)
            val endW = replyMorphOriginalWidth.takeIf { it > 0 } ?: dp(92)
            val endH = replyMorphOriginalHeight.takeIf { it > 0 } ?: dp(32)
            val startR = tileBg?.cornerRadius ?: dp(12).toFloat()
            val endR = lastReplySourceRadius.takeIf { it > 0f } ?: dp(16).toFloat()
            val labelEndWidth = (startW - tile.paddingLeft - tile.paddingRight).coerceAtLeast(dp(40))

            // Keep Reply label hidden while parent tile is scaling back.
            // It will be shown only after real small button layout is committed.
            label.visibility = View.GONE
            editView.visibility = View.VISIBLE
            sendView.visibility = View.VISIBLE

            // Smooth reverse: no per-frame layout width updates.
            // Keep textbox layout size, GPU-scale it back to Reply button visual size,
            // then commit real small layout at the end.
            (tile.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                lp.width = startW
                lp.height = startH
                tile.layoutParams = lp
            }
            tile.pivotX = 0f
            tile.pivotY = startH / 2f
            tile.translationX = replyMorphTargetTranslationX
            tile.scaleX = 1f
            tile.scaleY = 1f

            (label.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                lp.width = labelEndWidth
                lp.weight = 0f
                label.layoutParams = lp
            }
            label.alpha = 0f
            editView.alpha = 1f
            sendView.alpha = 1f

            replyTileAnimator?.cancel()
            val radiusAnimator = ValueAnimator.ofFloat(startR, endR).apply {
                duration = 560L
                interpolator = collapseInterpolator
                addUpdateListener { va ->
                    tileBg?.cornerRadius = va.animatedValue as Float
                }
            }
            replyTileAnimator = radiusAnimator
            radiusAnimator.start()

            label.animate()?.setListener(null)
            editView.animate()?.setListener(null)
            sendView.animate()?.setListener(null)
            label.animate()?.cancel()
            editView.animate()?.cancel()
            sendView.animate()?.cancel()

            editView.animate()
                ?.alpha(0f)
                ?.setDuration(220L)
                ?.setInterpolator(collapseInterpolator)
                ?.start()

            sendView.animate()
                ?.alpha(0f)
                ?.setDuration(180L)
                ?.setInterpolator(collapseInterpolator)
                ?.start()

            // Do not fade Reply label during scale-shrink; parent scale would distort it.
            // Label comes back normal in onAnimationEnd after layout is committed to button size.

            tile.animate()
                ?.setListener(null)
                ?.cancel()
            tile.animate()
                ?.withLayer()
                ?.translationX(0f)
                ?.scaleX((endW.toFloat() / startW.coerceAtLeast(1)).coerceIn(0.15f, 1f))
                ?.scaleY((endH.toFloat() / startH.coerceAtLeast(1)).coerceIn(0.15f, 1f))
                ?.setDuration(560L)
                ?.setInterpolator(collapseInterpolator)
                ?.setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        // Commit real small layout only once after visual shrink.
                        (tile.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                            lp.width = endW
                            lp.height = endH
                            tile.layoutParams = lp
                        }
                        tile.translationX = 0f
                        tile.scaleX = 1f
                        tile.scaleY = 1f
                        label.alpha = 1f
                        label.visibility = View.VISIBLE
                        editView.alpha = 0f
                        editView.visibility = View.GONE
                        sendView.alpha = 0f
                        sendView.visibility = View.GONE
                        tile.isClickable = true
                        (label.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                            lp.width = -1
                            lp.weight = 0f
                            label.layoutParams = lp
                        }
                        tileBg?.cornerRadius = endR
                        tileBg?.setStroke(dp(1), Color.parseColor("#444444"))
                        replyMorphOriginalWidth = 0
                        replyMorphOriginalHeight = 0
                        forceRegionUpdate()
                    }
                })
                ?.start()

            // Other action tabs return after the Reply tile starts shrinking.
            mainHandler.postDelayed({
                if (isReplyMode || sessionId != replyModeSessionId) return@postDelayed
                for (i in 0 until footer.childCount) {
                    val child = footer.getChildAt(i)
                    if (child !== tile) {
                        child.visibility = View.VISIBLE
                        child.animate()?.setListener(null)
                        child.animate()?.cancel()
                        child.animate()
                            ?.alpha(1f)
                            ?.scaleX(1f)
                            ?.scaleY(1f)
                            ?.setDuration(300)
                            ?.setInterpolator(morphInterpolator)
                            ?.start()
                    } else {
                        child.alpha = 1f
                    }
                }
            }, 180)
        } else {
            // Safety reset: if tile refs are unavailable, at least restore all action tabs.
            footer?.let { tabs ->
                for (i in 0 until tabs.childCount) {
                    val child = tabs.getChildAt(i)
                    child.animate()?.setListener(null)
                    child.animate()?.cancel()
                    child.visibility = View.VISIBLE
                    child.alpha = 1f
                    child.scaleX = 1f
                    child.scaleY = 1f
                    child.translationX = 0f
                    child.translationY = 0f
                }
            }
        }

        // Window flags back — NOT_FOCUSABLE restore
        val p = visualParams
        if (p != null) {
            p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
            visualRoot?.postDelayed({
                try { windowManager?.updateViewLayout(visualRoot, p) } catch (_: Exception) {}
                forceRegionUpdate()
            }, 80)
        }

        // Continue queue after reverse morph completes.
        mainHandler.postDelayed({
            forceRegionUpdate()
            if (notificationQueue.isNotEmpty()) processNextInQueue()
        }, 650)
    }

    private fun triggerFluidExpansion() {
        morphAnimator?.cancel()
        val startW = dp(AppSettings.getIslandWidthDp(this)); val pingW = dp(AppSettings.getIslandStage2WidthDp(this)); val targetW = dp(AppSettings.getIslandExpandedWidthDp(this))
        val startH = dp(AppSettings.getIslandHeightDp(this)); val targetH = dp(AppSettings.getIslandExpandedHeightDp(this))
        val startR = dp(AppSettings.getIslandCornerRadiusDp(this)).toFloat(); val targetR = dp(AppSettings.getIslandExpandedCornerRadiusDp(this)).toFloat()
        currentStage = IslandStage.STAGE3_FULL; expandReason = ExpandReason.AUTO_NOTIFICATION
        val ping = ValueAnimator.ofFloat(0f, 1f).apply { duration = 150L; addUpdateListener { updateIslandLayout(lerpEven(startW, pingW, it.animatedValue as Float), startH, startR) } }
        val expand = ValueAnimator.ofFloat(0f, 1f).apply { duration = 650L; interpolator = expandInterpolator; addUpdateListener { val t = it.animatedValue as Float; updateIslandLayout(lerpEven(pingW, targetW, t), lerpEven(startH, targetH, t), lerp(startR, targetR, t)); this@HyperAccessibilityService.gridRoot?.alpha = t; this@HyperAccessibilityService.gridRoot?.visibility = View.VISIBLE; this@HyperAccessibilityService.islandView?.scaleY = 1f - (0.04f * sin(t * Math.PI).toFloat()) } }
        val set = AnimatorSet().apply { playSequentially(ping, expand); addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(a: Animator) { scheduleAutoCollapse(); updateOutsideWatcherForState() } }) }
        morphAnimator = set; set.start()
    }

    private fun setStageAnimated(target: IslandStage, reason: ExpandReason) {
        if (currentStage == target && morphAnimator?.isRunning == true) return
        if (target == IslandStage.STAGE3_FULL && reason == ExpandReason.AUTO_NOTIFICATION) { triggerFluidExpansion(); return }
        morphAnimator?.cancel()
        val curW = this@HyperAccessibilityService.islandLayoutParams?.width ?: dp(AppSettings.getIslandWidthDp(this)); val curH = this@HyperAccessibilityService.islandLayoutParams?.height ?: dp(AppSettings.getIslandHeightDp(this)); val curR = this@HyperAccessibilityService.islandBackground?.cornerRadius ?: dp(AppSettings.getIslandCornerRadiusDp(this)).toFloat()
        currentStage = target; expandReason = reason
        val targetW = dp(getTargetWidth(target)); val targetH = dp(getTargetHeight(target)); val targetR = dp(getTargetRadius(target)).toFloat()
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (target == IslandStage.STAGE1_IDLE) 400L else 600L
            interpolator = if (target == IslandStage.STAGE1_IDLE) collapseInterpolator else expandInterpolator
            addUpdateListener { val t = it.animatedValue as Float; updateIslandLayout(lerpEven(curW, targetW, t), lerpEven(curH, targetH, t), lerp(curR, targetR, t)); if (notificationMode && target == IslandStage.STAGE1_IDLE) this@HyperAccessibilityService.gridRoot?.alpha = 1f - t; this@HyperAccessibilityService.islandView?.scaleY = 1f - (0.04f * sin(t * Math.PI).toFloat()) }
            addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(a: Animator) { if (target == IslandStage.STAGE1_IDLE) { this@HyperAccessibilityService.gridRoot?.visibility = View.GONE; notificationMode = false }; updateOutsideWatcherForState(); isProcessingQueue = false } })
        }
        morphAnimator = anim; anim.start()
    }

    private fun updateIslandLayout(w: Int, h: Int, r: Float) {
        this@HyperAccessibilityService.islandLayoutParams?.width = w; this@HyperAccessibilityService.islandLayoutParams?.height = h; this@HyperAccessibilityService.islandBackground?.cornerRadius = r
        this@HyperAccessibilityService.outlineRadius = r; this@HyperAccessibilityService.islandView?.layoutParams = this@HyperAccessibilityService.islandLayoutParams; visualRoot?.invalidateOutline(); forceRegionUpdate()
    }

    private fun getTargetWidth(s: IslandStage) = when(s) { IslandStage.STAGE1_IDLE -> AppSettings.getIslandWidthDp(this); IslandStage.STAGE2_PING -> AppSettings.getIslandStage2WidthDp(this); IslandStage.STAGE3_FULL -> AppSettings.getIslandExpandedWidthDp(this) }
    private fun getTargetHeight(s: IslandStage) = when(s) { IslandStage.STAGE1_IDLE -> AppSettings.getIslandHeightDp(this); IslandStage.STAGE2_PING -> AppSettings.getIslandStage2WidthDp(this) + 4; IslandStage.STAGE3_FULL -> AppSettings.getIslandExpandedHeightDp(this) }
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
        
        // THE INTERCEPTOR: Detects touches based on Reply State
        // Phase 3.5 Fluid — Off-Switch + Reflection Hack (compile-safe)
        visualRoot = object : FrameLayout(this) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (isReplyMode && event.action == MotionEvent.ACTION_DOWN) {
                    val rect = Rect()
                    this@HyperAccessibilityService.islandView?.getGlobalVisibleRect(rect)
                    if (!rect.contains(event.rawX.toInt(), event.rawY.toInt())) {
                        exitReplyMode() // OUTSIDE TAP = BACK SWITCH
                        return true
                    }
                }
                return super.onTouchEvent(event)
            }
        }.apply { 
            setBackgroundColor(0)
            // Reflection Hack — OnComputeInternalInsetsListener (hidden API)
            // Master Prompt: Touch Pass-through via Reflection — system UI gestures stay alive
            try {
                val observer = viewTreeObserver
                val listenerClass = Class.forName("android.view.ViewTreeObserver\$OnComputeInternalInsetsListener")
                val proxy = Proxy.newProxyInstance(
                    listenerClass.classLoader,
                    arrayOf(listenerClass)
                ) { _, method, args ->
                    if (method.name == "onComputeInternalInsets") {
                        val info = args?.get(0) ?: return@newProxyInstance null
                        try {
                            // setTouchableInsets(TOUCHABLE_INSETS_REGION = 3)
                            info.javaClass.getMethod("setTouchableInsets", Int::class.javaPrimitiveType)
                                .invoke(info, 3)
                        } catch (_: Exception) {}

                        // Clear content / visible insets via reflection if available
                        try {
                            val contentInsets = info.javaClass.getField("contentInsets").get(info) as Rect
                            contentInsets.setEmpty()
                        } catch (_: Exception) {}
                        try {
                            val visibleInsets = info.javaClass.getField("visibleInsets").get(info) as Rect
                            visibleInsets.setEmpty()
                        } catch (_: Exception) {}

                        try {
                            val region = info.javaClass.getField("touchableRegion").get(info) as Region
                            region.setEmpty()

                            if (isReplyMode) {
                                // FULL SCREEN INTERCEPTION during reply — Off-Switch
                                // Prompt #2: "touch ko system ui ko mat do ... animation reverse me chal jayega"
                                val rootW = resources.displayMetrics.widthPixels
                                val rootH = resources.displayMetrics.heightPixels
                                region.set(0, 0, rootW, rootH)
                            } else {
                                // Normal Island bounds — Touch Pass-through
                                val rect = Rect()
                                this@HyperAccessibilityService.islandView?.getGlobalVisibleRect(rect)
                                if (!rect.isEmpty()) {
                                    region.set(rect)
                                }
                            }
                        } catch (_: Exception) {}
                    }
                    null
                }
                observer.javaClass.getMethod("addOnComputeInternalInsetsListener", listenerClass)
                    .invoke(observer, proxy)
            } catch (_: Exception) {
                // Reflection failed — fallback to full touch (safe)
            }
        }
        
        this@HyperAccessibilityService.islandBackground = createIslandBackground(r)
        this@HyperAccessibilityService.islandView = FrameLayout(this).apply {
            background = this@HyperAccessibilityService.islandBackground; clipToOutline = true; outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, this@HyperAccessibilityService.outlineRadius) } }
            this@HyperAccessibilityService.gridRoot = LinearLayout(this@HyperAccessibilityService).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(1), dp(1), dp(1), dp(1)); visibility = View.GONE; alpha = 0f; weightSum = 1f
                val iconSec = FrameLayout(context).apply { this@HyperAccessibilityService.appIconView = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }; addView(this@HyperAccessibilityService.appIconView, FrameLayout.LayoutParams(dp(38), dp(38), Gravity.CENTER)) }
                val contentSec = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, 0, 0)
                    val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                    this@HyperAccessibilityService.appNameText = TextView(context).apply { setTextColor(Color.rgb(0, 150, 255)); textSize = 11f; typeface = Typeface.DEFAULT_BOLD }
                    this@HyperAccessibilityService.timeStampText = TextView(context).apply { setTextColor(Color.GRAY); textSize = 10f; setPadding(dp(6), 0, 0, 0) }
                    header.addView(this@HyperAccessibilityService.appNameText); header.addView(this@HyperAccessibilityService.timeStampText)
                    this@HyperAccessibilityService.headerLine = this@HyperAccessibilityService.appNameText
                    this@HyperAccessibilityService.titleText = TextView(context).apply { setTextColor(Color.WHITE); textSize = 15.5f; typeface = Typeface.DEFAULT_BOLD; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
                    this@HyperAccessibilityService.messageText = TextView(context).apply { setTextColor(Color.rgb(200, 200, 200)); textSize = 13f; maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
                    this@HyperAccessibilityService.actionScroll = HorizontalScrollView(context).apply {
                        isHorizontalScrollBarEnabled = false
                        overScrollMode = View.OVER_SCROLL_NEVER
                        setClipChildren(false)
                        setClipToPadding(false)
                        this@HyperAccessibilityService.footerActions = LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            setClipChildren(false)
                            setClipToPadding(false)
                        }
                        addView(this@HyperAccessibilityService.footerActions)
                    }
                    
                    // Reply Bar UI
                    this@HyperAccessibilityService.replyBar = LinearLayout(context).apply { 
                        orientation = LinearLayout.HORIZONTAL; visibility = View.GONE; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0)
                        val inputBg = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(Color.parseColor("#1A1A1A")); cornerRadius = dp(12).toFloat(); setStroke(dp(1), Color.parseColor("#333333")) }
                        this@HyperAccessibilityService.replyEditText = EditText(context).apply { hint = "Type a reply..."; setHintTextColor(Color.GRAY); setTextColor(Color.WHITE); textSize = 13f; background = inputBg; setPadding(dp(12), dp(8), dp(12), dp(8)); layoutParams = LinearLayout.LayoutParams(0, dp(40), 1f) }
                        this@HyperAccessibilityService.sendButton = TextView(context).apply { text = "SEND"; setTextColor(Color.rgb(0, 150, 255)); typeface = Typeface.DEFAULT_BOLD; setPadding(dp(12), 0, 0, 0); setOnClickListener { sendCurrentReply() } }
                        addView(this@HyperAccessibilityService.replyEditText); addView(this@HyperAccessibilityService.sendButton)
                    }

                    addView(header); addView(this@HyperAccessibilityService.titleText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(1) }); addView(this@HyperAccessibilityService.messageText, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(1) }); addView(this@HyperAccessibilityService.actionScroll, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }); addView(this@HyperAccessibilityService.replyBar)
                }
                addView(iconSec, LinearLayout.LayoutParams(0, -2, 0.2f)); addView(contentSec, LinearLayout.LayoutParams(0, -2, 0.8f))
            }
            addView(this@HyperAccessibilityService.gridRoot, FrameLayout.LayoutParams(-1, -1))

            // Reply Morph V2 layer: full island coordinate space, above normal content, outside action scroll clipping.
            this@HyperAccessibilityService.morphLayer = FrameLayout(this@HyperAccessibilityService).apply {
                visibility = View.VISIBLE
                setClipChildren(false)
                setClipToPadding(false)
                isClickable = false

                this@HyperAccessibilityService.replyEditorLayer = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    visibility = View.INVISIBLE
                    alpha = 0f
                    setPadding(dp(14), 0, dp(10), 0)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        setColor(Color.parseColor("#111214"))
                        cornerRadius = dp(12).toFloat()
                        setStroke(dp(1), Color.argb(55, 255, 255, 255))
                    }
                    this@HyperAccessibilityService.replyEditorEditText = EditText(context).apply {
                        hint = "Type a reply..."
                        setHintTextColor(Color.argb(145, 255, 255, 255))
                        setTextColor(Color.WHITE)
                        textSize = 13f
                        setSingleLine(true)
                        maxLines = 1
                        background = null
                        setPadding(0, 0, dp(8), 0)
                        setIncludeFontPadding(false)
                    }
                    this@HyperAccessibilityService.replyEditorSendButton = TextView(context).apply {
                        text = "SEND"
                        setTextColor(Color.rgb(0, 150, 255))
                        textSize = 11.5f
                        typeface = Typeface.DEFAULT_BOLD
                        gravity = Gravity.CENTER
                        setIncludeFontPadding(false)
                        setPadding(dp(8), 0, 0, 0)
                        setOnClickListener { sendCurrentReply() }
                    }
                    addView(this@HyperAccessibilityService.replyEditorEditText, LinearLayout.LayoutParams(0, -1, 1f))
                    addView(this@HyperAccessibilityService.replyEditorSendButton, LinearLayout.LayoutParams(-2, -1))
                }

                this@HyperAccessibilityService.replyGhostView = ReplyMorphView(context).apply {
                    visibility = View.INVISIBLE
                    isClickable = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }

                addView(this@HyperAccessibilityService.replyEditorLayer, FrameLayout.LayoutParams(1, 1))
                addView(this@HyperAccessibilityService.replyGhostView, FrameLayout.LayoutParams(-1, -1))
            }
            addView(this@HyperAccessibilityService.morphLayer, FrameLayout.LayoutParams(-1, -1))

            setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_UP) { if (e.rawY - touchStartY < -dp(24)) postCollapseIsland() else if (abs(e.rawY - touchStartY) < dp(10)) postToggleExpanded() } else if (e.action == MotionEvent.ACTION_DOWN) { touchStartY = e.rawY }; true }
        }
        this@HyperAccessibilityService.islandLayoutParams = FrameLayout.LayoutParams(w, h).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL }
        visualRoot?.addView(this@HyperAccessibilityService.islandView, this@HyperAccessibilityService.islandLayoutParams); updateOutlineForIsland(w, h, r)
        try { windowManager?.addView(visualRoot, visualParams) } catch (_: Exception) { hideIslandInternal() }
    }

    private fun updateOutsideWatcherForState() { if (currentStage == IslandStage.STAGE3_FULL && expandReason == ExpandReason.MANUAL_USER && !isReplyMode) ensureOutsideWatcher() else removeOutsideWatcher() }
    private fun ensureOutsideWatcher() { if (outsideWatcherView != null) return; outsideWatcherView = FrameLayout(this).apply { setBackgroundColor(0); setOnTouchListener { _, event -> if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_OUTSIDE) postCollapseIsland() ; false } }; try { windowManager?.addView(outsideWatcherView, WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, 16777216 or 8 or 4096 or 512 or 256, -3).apply { gravity = Gravity.TOP or Gravity.START; title = "HyperIslandProOutside" }) } catch (_: Exception) {} }
    private fun removeOutsideWatcher() { try { windowManager?.removeViewImmediate(outsideWatcherView!!) } catch (_: Exception) {}; outsideWatcherView = null }
    private fun forceRegionUpdate() { visualRoot?.post { visualRoot?.requestLayout(); visualRoot?.parent?.requestLayout() } }
    fun updateAllToCurrentState() { val w = dp(getTargetWidth(currentStage)); val h = dp(getTargetHeight(currentStage)); val r = dp(getTargetRadius(currentStage)).toFloat(); updateIslandLayout(w, h, r) }
    private fun hideIslandInternal() { morphAnimator?.cancel(); ghostAnimator?.cancel(); autoCollapseRunnable?.let { mainHandler.removeCallbacks(it) }; removeOutsideWatcher(); try { windowManager?.removeViewImmediate(visualRoot!!) } catch (_: Exception) {}; visualRoot = null; currentStage = IslandStage.STAGE1_IDLE; isReplyMode = false; isGhostReplyMode = false; replyGhostView?.clearGhost() }
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
