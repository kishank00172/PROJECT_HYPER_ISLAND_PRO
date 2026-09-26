package com.hyperisland.pro.services

import android.accessibilityservice.AccessibilityService
import android.hardware.display.DisplayManager
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Region
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.transition.AutoTransition
import android.transition.ChangeBounds
import android.transition.Transition
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.util.Log
import android.view.Choreographer
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.animation.TimeInterpolator
import android.view.animation.PathInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.hyperisland.pro.core.AppSettings
import com.hyperisland.pro.core.ChatDisplayPolicy
import com.hyperisland.pro.core.FrameWatch
import com.hyperisland.pro.core.GcSnapshot
import com.hyperisland.pro.core.IslandGesture
import com.hyperisland.pro.core.IslandMorphFrame
import com.hyperisland.pro.core.MorphFrame
import com.hyperisland.pro.core.MorphFrameHost
import com.hyperisland.pro.core.MorphCarry
import com.hyperisland.pro.core.MotionVariant
import com.hyperisland.pro.core.MorphJankMeter
import com.hyperisland.pro.core.stallLine
import com.hyperisland.pro.core.stallShouldPrint
import com.hyperisland.pro.core.TraceLog
import com.hyperisland.pro.core.UpdateGate
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Calendar
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
        val unreadCount: Int, val conversationKey: String, val conversationKeySource: String,
        val postTime: Long, val contentIntent: PendingIntent?, val actions: List<Notification.Action>, val smallIcon: Icon?,
        /** True when the notification carries conversation extras (MessagingStyle / conversationTitle).
         *  Drives ring eviction: junk that only wears CATEGORY_MESSAGE must not push real chats out. */
        val isMessagingStyle: Boolean = false,
        /** The message's own instant, when the app told us one; 0 means "fall back to postTime". */
        val displayTimeMs: Long = 0L
    )

    private class InstagramGradientCameraDrawable : Drawable() {
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val rect = RectF()

        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            if (w <= 0f || h <= 0f) return

            val gradient = LinearGradient(
                b.left.toFloat(), b.bottom.toFloat(), b.right.toFloat(), b.top.toFloat(),
                intArrayOf(
                    Color.rgb(252, 175, 69),   // orange
                    Color.rgb(225, 48, 108),   // pink
                    Color.rgb(131, 58, 180)    // purple
                ),
                floatArrayOf(0f, 0.52f, 1f),
                Shader.TileMode.CLAMP
            )
            strokePaint.shader = gradient
            fillPaint.shader = gradient
            strokePaint.strokeWidth = w * 0.085f

            rect.set(
                b.left + w * 0.18f,
                b.top + h * 0.18f,
                b.right - w * 0.18f,
                b.bottom - h * 0.18f
            )
            canvas.drawRoundRect(rect, w * 0.20f, h * 0.20f, strokePaint)
            canvas.drawCircle(b.left + w * 0.50f, b.top + h * 0.52f, w * 0.145f, strokePaint)
            canvas.drawCircle(b.left + w * 0.69f, b.top + h * 0.33f, w * 0.045f, fillPaint)

            strokePaint.shader = null
            fillPaint.shader = null
        }

        override fun getIntrinsicWidth(): Int = 64
        override fun getIntrinsicHeight(): Int = 64
        override fun setAlpha(alpha: Int) {
            strokePaint.alpha = alpha
            fillPaint.alpha = alpha
        }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            strokePaint.colorFilter = colorFilter
            fillPaint.colorFilter = colorFilter
        }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

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
    /**
     * The curve the morph animators run on. The animators are BUILT before `beginMorphPerf` knows which look
     * this is, so a spring chosen at build time would always be the previous morph's; this reads the state at
     * frame time instead. For the four original styles it *is* the house curve, unchanged - a new design must
     * never be able to rewrite a feel he has already judged, which is the rule that broke once before.
     */
    private val morphCurve = TimeInterpolator { f: Float ->
        if (MotionVariant.isSpring(morphVariant))
            MotionVariant.spring(f, morphDurationMs, morphResponseSec, morphDamping)
        else morphInterpolator.getInterpolation(f)
    } 
    private val collapseInterpolator = PathInterpolator(0.55f, 0.0f, 0.1f, 1.0f)
    private val ghostMagneticInterpolator = PathInterpolator(0.16f, 1.0f, 0.30f, 1.0f)
    private val ghostMaterialInterpolator = PathInterpolator(0.20f, 0.0f, 0.0f, 1.0f)
    private val ghostReverseInterpolator = PathInterpolator(0.40f, 0.0f, 0.20f, 1.0f)

    companion object {
        /**
         * How many conversations the island holds at once. There is no platform rule and no Dynamic
         * Island precedent for this number - the first carousel copied "a stack of 5" from HyperOS and I
         * kept it, which meant six chats arriving together dropped one outright. The shelf is the real
         * limit now: 24 is a guard against an unbounded list (overlay memory, and a page indicator nobody
         * would ever flip through), not a design choice. Eviction is logged, so a dropped chat is
         * auditable instead of silent.
         */
        private const val MAX_RING_ITEMS = 24
        /** Same tag the notification listener logs every show/drop under: `adb logcat -s HIP_TRACE`. */
        private const val TRACE_TAG = "HIP_TRACE"

        /**
         * The main thread being this long without completing a tick is two 120 Hz frames lost, which is the
         * smallest block a human reliably calls a jolt. It is the whole thread, not our draw: the tester's
         * log shows our draw at 0.1 ms a frame while his animation still jumped, so the cost was never in
         * the pixels we produce.
         */
        private const val STALL_MS = 24L

        /**
         * The tick cadence. It must stay well under [STALL_MS] or a free main thread reads as a stall: what is
         * measured is the time since the last tick completed, and a queue that is doing nothing still waits
         * one cadence before the next look.
         */
        private const val STALL_POLL_MS = 12L

        /** Above this the process was frozen rather than blocked; a different problem we cannot name. */
        private const val STALL_FREEZE_MS = 15_000L

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
        fun showNotificationFromApp(context: Context, packageName: String, notificationKey: String? = null, appName: String, title: String, message: String, unreadCount: Int = 1, conversationKey: String? = null, conversationKeySource: String? = null, postTime: Long, contentIntent: PendingIntent?, actions: List<Notification.Action>, smallIcon: Icon? = null, isMessagingStyle: Boolean = false, displayTimeMs: Long = 0L) =
            instance?.run { postNotificationEvent(packageName, notificationKey, appName, title, message, unreadCount, conversationKey, conversationKeySource, postTime, contentIntent, actions, smallIcon, isMessagingStyle, displayTimeMs); true } ?: false
        /**
         * Drop exactly one conversation from the ring (the listener's removal callback and the island's own
         * tap-to-open both use it). [reason] is the removal code, carried so every drop in the log can say who
         * did it: "counting badh ghat kaise raha hai" only has an answer if a drop is attributable.
         */
        fun dismissConversationFromApp(context: Context, conversationKey: String, reason: Int = -1) =
            instance?.run { mainHandler.post { dismissConversationFromRing(conversationKey, reason) }; true } ?: false
        fun previewReplyAnimationFromApp(context: Context, replySecond: Boolean) =
            instance?.run { postPreviewReplyAnimation(replySecond); true } ?: false
        fun previewPillIconFromApp(context: Context, packageName: String, count: Int) =
            instance?.run { postPreviewPillIcon(packageName, count); true } ?: false
        fun previewShadePullFromApp(context: Context) =
            instance?.run { postPreviewShadePull(); true } ?: false
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val notificationQueue = ArrayDeque<NotificationModel>() // legacy transient queue
    private val notificationRing = ArrayList<NotificationModel>() // Target 2 bounded conversation ring
    private var currentRingIndex = 0
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

    // Pill badge notification preview — compact, non-intrusive default surface
    private var pillPreviewRoot: FrameLayout? = null
    private var pillPreviewIcon: ImageView? = null
    private var pillPreviewCount: TextView? = null
    /** The pill badge: how many conversations are stacked right now. Not a sum of app badges. */
    private var pillChatCountField = 0

    /**
     * The number he sees, with exactly one writer that cannot stay silent. Until now `clearNotificationRing()`
     * zeroed it with no log line at all and the preview paths wrote it behind everyone's back, so "notification
     * khole bina kam ho rahe hain" had no answer in the trace. A property cannot be bypassed by the next
     * well-meaning patch, which is the point: the log must not depend on what I remembered to instrument.
     */
    private var pillChatCount: Int
        get() = pillChatCountField
        set(value) {
            val before = pillChatCountField
            pillChatCountField = value
            // Blank below two, a digit from two up, nothing in between: the badge earns its frame of attention
            // only when there is a stack to count (his rule) - and the number is written here, nowhere else.
            val badge = com.hyperisland.pro.core.PillBadge.textFor(value)
            pillPreviewCount?.text = badge
            pillPreviewCount?.visibility =
                if (com.hyperisland.pro.core.PillBadge.isShown(value)) View.VISIBLE else View.GONE
            if (value == before) return
            if (com.hyperisland.pro.core.PillBadge.isShown(before) && com.hyperisland.pro.core.PillBadge.isShown(value)) {
                rollBadge(before, value)
            }
            val page = if (notificationRing.isEmpty()) 0 else currentRingIndex + 1
            val unread = notificationRing.getOrNull(currentRingIndex)?.unreadCount ?: 0
            TraceLog.count(
                "$before->$value chats=$value page=$page/${notificationRing.size} unread=$unread " +
                    "badge=\"$badge\" cause=$lastRingOp"
            )
        }

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
    private var morphMeter: MorphJankMeter? = null
    private var morphLayoutW = -1
    private var morphLayoutH = -1
    /** The card view, seen as the thing that can draw its own morph box (null = use the old resize path). */
    private var islandMorph: MorphFrameHost? = null
    /**
     * The travelling-element state, armed only while a drawn box is what moves. Both drawables are taken from
     * the views that already hold them, so a morph costs no extra `PackageManager` call: `morphIconLauncher` is
     * the badge the card was showing, `morphIconPill` the glyph the pill was showing.
     */
    private var morphCarryOn = false
    private var morphCarryGrowing = true
    private var morphScaleOn = false
    private var morphOpacityFollowsShape = false
    /** Whether this morph ENDS at the expanded card, i.e. whether the row is meant to be seen at the end. */
    private var morphTowardCard = false
    private var morphScaleFrom = 0.88f
    private var morphSwapAt = 0.5f
    private var morphOutBy = 0.4f
    /** The glass form: the content arrives blurred and sharpens with the box; the icon stays sharp. */
    private var morphGlassOn = false
    /** How out of focus the glass form starts, in dp. Small on purpose: text must stay readable throughout. */
    private val GLASS_BLUR_DP = 6
    private var morphBlurPx = 0f
    private var lastBlurPx = -1f
    /** This frame's shape progress, kept for the log and for anyone who wants to see what a frame thought. */
    private var morphProgress = 0f
    /**
     * The two outside designs' per-morph state. Everything here is read ONCE, at the start of the morph, and
     * every frame of that morph uses those values - which is the opposite of what the previous round did, where
     * the style was consulted in one place and the Lab's labels implied another. A verdict he gives has to be a
     * verdict about a known set of numbers, and the [MORPH] log line now prints them so the two can be compared.
     */
    private var morphVariant = 0
    private var morphVariantOn = false
    private var morphLiquidOn = false
    private var morphHyperOn = false
    private var morphGate = 0.6f
    private var morphMagnet = 0.6f
    private var morphSqueeze = 0f
    private var morphRipple = 0f
    private var morphResponseSec = 0.42f
    private var morphDamping = 1f
    private var morphDurationMs = 360L
    private var morphOvershootPx = 0
    /** The card's own height, without the spring's headroom: what the content is centred against. */
    private var morphNaturalH = 0
    /** The thickness the curve is expected to add at its peak, in px. Printed in the trace; see `travelV=`. */
    private var morphTravelV = 0
    private var morphFromH = 0
    private var morphToH = 0
    private var lastShelfCheckAt = 0L
    /**
     * One daemon thread for the shelf probe, and a single-flight flag. The thread exists because a binder round
     * trip to the window manager has no business being on the draw path; the flag exists because without it, a
     * burst of window changes would queue fifty probes of the same question and the "fix" would be a backlog.
     */
    private val shelfProbe = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "hip-shelf-probe").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    private val shelfProbeBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    /** Which app we are waiting to take the foreground after a quick action, and since when. */
    private var actionTakeoverPkg: String? = null
    private var actionTakeoverAt = 0L
    /**
     * How long the card waits for the app it acted on to come forward before the wait itself closes the card.
     * The takeover is the precise trigger and this is only its bound: "the action landed, the tick is visible
     * for four seconds, and then the island is done with it" - leaving the card up indefinitely because the app
     * chose not to cancel its own notification is what he reported.
     */
    private val ACTION_TAKEOVER_FALLBACK_MS = 4_000L
    /** Minimum spacing between two shelf-state probes; each one costs binder round trips on the main thread. */
    private val SHELF_CHECK_MIN_INTERVAL_MS = 500L
    private var morphFromW = 0
    private var morphToW = 0
    private var lastRingOp = "init"
    private var morphIconRide = true
    private var morphContentDropPx = 0f
    private var morphEntryDrop = true
    /** Spring styles only: the content rides its own slower, underdamped spring on the way in (buoyancy -
     * his ask, round 33), so it lands after the box, dips past its seat, and floats back. */
    private var morphBuoyOn = false
    /** The card's text column - everything the morph's fade may touch, and all it touches since round 33.
     * The icon section fades with nobody: it is the shared element. */
    private var gridContentSec: android.view.View? = null
    /** The card's icon cell: the rigid asset of his hierarchy (no stretch, the smaller bob). */
    private var gridIconSec: android.view.View? = null
    /** The pull lives on expands of the spring styles only; set per morph, read by the tween and the carry. */
    private var morphV2On = false
    private var v2HapticDone = false
    private var v2GateLogged = false
    private var v2OverlayLogged = false
    private var v2TraceFrames = 0
    private var v2TraceNextIdx = 0
    private var v2TraceSamples = ""
    private val v2TraceMarks = floatArrayOf(0.2f, 0.4f, 0.6f, 0.8f, 0.90f, 0.999f)
    /** Step-4 float loop: active only on a settled FULL card in a spring style; cancelled on any new morph. */
    private var morphStagger = 0f
    private var morphOpenFrame = 0f
    private var lastMorphBoxLeft = 0
    private var morphIconLauncher: android.graphics.drawable.Drawable? = null
    private var morphIconPill: android.graphics.drawable.Drawable? = null

    /** The size the view is pinned to for this morph: start and target, per axis, whichever is larger. */
    private var morphPinW: Int? = null
    private var morphPinH: Int? = null
    private var morphFinalW = 0
    private var morphFinalH = 0
    private var morphFinalR = 0f
    /**
     * Whole-window frame watcher. The morph meter only knows about the frames it is handed, and a report of
     * "the whole island is sluggish" once arrived while every single morph line said the frames were fine -
     * so the window gets watched from the vsync callback itself, plus a layout-pass counter and the card's
     * own draw time. See [FrameWatch].
     */
    private val frameWatch = FrameWatch()
    private var frameWatcher: Choreographer.FrameCallback? = null
    private var frameLayoutWatcher: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var displayWatcher: DisplayManager.DisplayListener? = null
    /**
     * What the live TextViews actually hold, plus the icon package and the tile list. The content update runs
     * from nine places (queue, ring swap, swipe-back, drag-undo, reply exit...) and each one used to re-measure
     * every string and re-ask PackageManager for the icon - the log named that as the cost, not the drawing.
     * Rebuilding the view tree clears this, so a stale cache can only cause an extra update, never a missed one.
     */
    private var lastIconPkg: String? = null
    private var lastAppNameShown: CharSequence? = null
    private var lastStampShown: CharSequence? = null
    private var lastTitleShown: CharSequence? = null
    private var lastUnreadShown = -1
    private var lastTitleWasHidden = false
    private var lastMessageShown: CharSequence? = null
    private var lastMessageWasHidden = false
    private var lastTiles: List<Notification.Action>? = null

    private fun invalidateContentCache() {
        lastIconPkg = null; lastAppNameShown = null; lastStampShown = null
        lastTitleShown = null; lastMessageShown = null; lastTiles = null
    }

    private var lastSeenHertz = 0
    private var stallThread: Thread? = null
    @Volatile private var stallRunning = false
    private val stallSeenNs = java.util.concurrent.atomic.AtomicLong(0L)
    private var morphGcStart: GcSnapshot? = null

    /** Monotonic: a wall clock that jumps (time zones, NITZ) must not open a 40-hour watch window. */
    private fun nowMs(): Long = SystemClock.elapsedRealtime()
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
    private var outsideGestureActive = false
    private val outlineRect = Rect()
    private var outlineRadius = 0f

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(WindowManager::class.java)
        startTracing()
        if (AppSettings.isIslandEnabled(this)) postShowIsland()
    }

    private val tracePrefs by lazy { getSharedPreferences("hip_trace", MODE_PRIVATE) }
    private var traceLinesSinceSave = 0

    /**
     * Every decision the island makes goes into [TraceLog], and TraceLog mirrors it to logcat. That is
     * the difference between "the swipe did nothing, probably because ..." and a line naming which branch
     * ran. The buffer is also persisted, because the failures worth catching (an OEM killing the service,
     * a stranded animation) frequently end the process, and a log that dies with the bug proves nothing.
     * On the phone: main screen -> TRACE LOG.
     */
    private fun startTracing() {
        TraceLog.restore(tracePrefs.getString("tail", null))
        TraceLog.sink = { line -> Log.i(TRACE_TAG, line) }
        startStallWatch()
        startDisplayWatch()
        TraceLog.onLine = {
            if (++traceLinesSinceSave >= 20) {
                traceLinesSinceSave = 0
                tracePrefs.edit().putString("tail", TraceLog.persisted()).apply()
            }
        }
        // The version belongs in the log because a trace that cannot say which build it came from is how
        // one round of this got argued about instead of measured.
        val build = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" } catch (_: Exception) { "?" }
        TraceLog.line("BOOT", "island service connected (build=$build, sdk=${Build.VERSION.SDK_INT}, model=${Build.MODEL})")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            val eventPkg = event.packageName?.toString().orEmpty()
            // A quick action leaves the card up on purpose - the tick used to be followed by a 500 ms timer that
            // hid the island before it could be seen - but "on purpose" was only half a rule, and the other half
            // is what he is reporting now: nothing ever brought it back down. What brings it down is the app
            // taking over. The action asked that app to do something, so its window coming forward IS the moment
            // the island has done its job; no delay we have to guess, and a page whose notification gets
            // cancelled still closes through the ring's own path. Not every action brings a window - "mark as
            // read" is the obvious one - so the wait has a bounded fallback too, see markActionTakeoverWait.
            val awaiting = actionTakeoverPkg
            if (awaiting != null && eventPkg == awaiting) {
                val waitedFor = System.currentTimeMillis() - actionTakeoverAt
                actionTakeoverPkg = null
                TraceLog.line("ACTION", "$awaiting took the foreground ${waitedFor}ms after the action - back to the pill")
                returnToPillAfterAction()
            }
            // Whether the shelf is down decides one thing: the pill hides. Getting that answer costs a binder
            // round trip per system window, and this event fires for every window change on the phone - the IME,
            // an app coming forward, a heads-up. b1396's capture had 220 STALL lines from this call, so it got a
            // throttle; b1406's capture shows the throttle was the wrong half of the answer: 80 calls, 3 750 ms
            // of main-thread blocking in ten minutes, and a stall inside 33 of his 74 morph windows - which is
            // why it reads as "bahut kuch glitchy" on EVERY style and not only the new two. So now: ask only when the
            // event could even be the shelf, at most twice a second, and never on the thread that draws. A verdict
            // that arrives ~40 ms later hides the pill 40 ms later; a verdict asked synchronously costs the frames
            // of whatever the island is doing at that moment.
            val cls = event.className?.toString().orEmpty()
            val couldBeShelf = eventPkg.isEmpty() || eventPkg == "com.android.systemui" ||
                cls.contains("shade", true) || cls.contains("notification", true) || cls.contains("panel", true)
            val shelfNow = System.currentTimeMillis()
            // (nested on purpose: an early return from onAccessibilityEvent here would also skip the
            // ingestion fallback below it, which is a behaviour change dressed up as a performance fix.)
            if (couldBeShelf && shelfNow - lastShelfCheckAt >= SHELF_CHECK_MIN_INTERVAL_MS) {
                lastShelfCheckAt = shelfNow
                probeShadeOffMainThread()
            }
        }
        // Notification ingestion used to have a second source: this event's CharSequence list,
        // which is all AccessibilityEvent exposes for a notification. It produced a *degraded* card -
        // title taken from a text line (which is why the headline read "Instagram"), postTime stamped
        // as "now", and `actions = emptyList()`, i.e. never any quick actions - and it fired for every
        // notification whether or not the listener had also seen it. Its only brake was "the listener
        // showed something in the last 1500 ms", so anything the listener *deliberately* dropped (an
        // echo, noise) came back through here. A fallback should run when the primary path fails; this
        // one ran when the primary path said "no", which is why the same chat looked right sometimes and
        // wrong other times. The listener is the ingestion path; it carries the real postTime, the
        // reply actions and the MessagingStyle bundles, and it re-syncs on connect (see
        // HyperNotificationListenerService). Accessibility stays for what only it can do: typing a
        // reply into another app, and the island's own touch handling.
    }

    private fun markIslandNotificationsSeenFromShade() {
        // Opening the shelf used to mean "everything was read": the ring was emptied here, and separately every
        // notification that arrived while the shade was open was refused at the listener's door. During a
        // notification rain those two rules together are exactly what he saw - "kholta bhi nahi aur notification
        // kam ho ja rahe hain" - and neither of them was logged. Now the default hides the pill while he reads
        // and keeps the pages, because the shelf reports read-ness notification by notification; wiping is a
        // setting he can pick, not a law I assumed.
        if (isReplyMode) return
        autoCollapseRunnable?.let { mainHandler.removeCallbacks(it) }
        autoCollapseRunnable = null
        if (AppSettings.getShadeOpenPolicy(this) == AppSettings.SHADE_POLICY_WIPES) {
            clearNotificationRing("shade-open")
        } else {
            ringEvent("shade open: pill hidden, ${notificationRing.size} pages kept (policy=quiet)")
        }
        isProcessingQueue = false

        val root = pillPreviewRoot
        if (root != null && root.visibility == View.VISIBLE && root.alpha > 0f) {
            animatePillShadePull(root) {
                pillPreviewCount?.visibility = View.GONE
                if (currentStage == IslandStage.STAGE2_PING) {
                    setStageAnimated(IslandStage.STAGE1_IDLE, ExpandReason.MANUAL_USER)
                }
            }
        } else {
            pillPreviewCount?.visibility = View.GONE
            pillPreviewRoot?.visibility = View.GONE
            pillPreviewRoot?.alpha = 0f
            if (currentStage == IslandStage.STAGE2_PING) {
                setStageAnimated(IslandStage.STAGE1_IDLE, ExpandReason.MANUAL_USER)
            }
        }
    }

    private fun animatePillShadePull(root: View, onEnd: () -> Unit) {
        // Shade Pull Lab: selected preset controls how pill content is absorbed upward.
        root.animate()?.setListener(null)
        root.animate()?.cancel()
        root.visibility = View.VISIBLE
        root.alpha = 1f
        root.translationY = 0f
        root.scaleX = 1f
        root.scaleY = 1f
        root.pivotX = root.width / 2f
        root.pivotY = 0f
        root.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        val mode = AppSettings.getShadePullAnimationMode(this)
        if (mode == AppSettings.SHADE_PULL_SIMPLE) {
            root.animate()
                ?.alpha(0f)
                ?.scaleX(0.92f)
                ?.scaleY(0.92f)
                ?.setDuration(220L)
                ?.setInterpolator(collapseInterpolator)
                ?.setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        root.setLayerType(View.LAYER_TYPE_NONE, null)
                        resetPillShadePullView(root)
                        onEnd()
                    }
                })
                ?.start()
            return
        }

        val config = when (mode) {
            AppSettings.SHADE_PULL_MY_ABSORB -> ShadePullConfig(
                duration = 260L,
                translationDp = -16f,
                scaleTarget = 0.92f,
                moveStart = 0.08f,
                moveEnd = 1.00f,
                alphaStart = 0.00f,
                alphaEnd = 0.84f,
                scaleStart = 0.12f,
                scaleEnd = 0.94f,
                moveCurve = PathInterpolator(0.16f, 1.0f, 0.30f, 1.0f),
                fadeCurve = PathInterpolator(0.30f, 0.0f, 0.70f, 1.0f),
                scaleCurve = PathInterpolator(0.22f, 0.0f, 0.0f, 1.0f)
            )
            AppSettings.SHADE_PULL_KIMI_VACUUM -> ShadePullConfig(
                duration = 240L,
                translationDp = -16f,
                scaleTarget = 0.94f,
                moveStart = 0.16f,
                moveEnd = 1.00f,
                alphaStart = 0.16f,
                alphaEnd = 0.84f,
                scaleStart = 0.16f,
                scaleEnd = 1.00f,
                moveCurve = PathInterpolator(0.20f, 0.0f, 0.0f, 1.0f),
                fadeCurve = PathInterpolator(0.00f, 0.0f, 0.20f, 1.0f),
                scaleCurve = PathInterpolator(0.40f, 0.0f, 0.20f, 1.0f)
            )
            AppSettings.SHADE_PULL_DEEPSEEK_MAGNETIC -> ShadePullConfig(
                duration = 240L,
                translationDp = -12f,
                scaleTarget = 0.92f,
                moveStart = 0.25f,
                moveEnd = 1.00f,
                alphaStart = 0.25f,
                alphaEnd = 1.00f,
                scaleStart = 0.00f,
                scaleEnd = 1.00f,
                moveCurve = PathInterpolator(0.55f, 0.0f, 0.20f, 1.0f),
                fadeCurve = PathInterpolator(0.00f, 0.0f, 0.20f, 1.0f),
                scaleCurve = PathInterpolator(0.55f, 0.0f, 0.20f, 1.0f)
            )
            else -> ShadePullConfig(
                duration = 240L,
                translationDp = -16f,
                scaleTarget = 0.94f,
                moveStart = 0.10f,
                moveEnd = 0.92f,
                alphaStart = 0.18f,
                alphaEnd = 0.82f,
                scaleStart = 0.00f,
                scaleEnd = 1.00f,
                moveCurve = PathInterpolator(0.35f, 0.0f, 0.12f, 1.0f),
                fadeCurve = PathInterpolator(0.00f, 0.0f, 0.20f, 1.0f),
                scaleCurve = PathInterpolator(0.20f, 0.0f, 0.20f, 1.0f)
            )
        }

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = config.duration
            addUpdateListener { animator ->
                val raw = animator.animatedValue as Float
                val moveT = config.moveCurve.getInterpolation(segment(raw, config.moveStart, config.moveEnd))
                val fadeT = config.fadeCurve.getInterpolation(segment(raw, config.alphaStart, config.alphaEnd))
                val scaleT = config.scaleCurve.getInterpolation(segment(raw, config.scaleStart, config.scaleEnd))
                val targetTranslation = dp(kotlin.math.abs(config.translationDp).toInt()).toFloat() * if (config.translationDp < 0f) -1f else 1f
                val scale = 1f - ((1f - config.scaleTarget) * scaleT)
                root.translationY = targetTranslation * moveT
                root.alpha = 1f - fadeT
                root.scaleX = scale
                root.scaleY = scale
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    root.setLayerType(View.LAYER_TYPE_NONE, null)
                    resetPillShadePullView(root)
                    onEnd()
                }
                override fun onAnimationCancel(animation: Animator) {
                    root.setLayerType(View.LAYER_TYPE_NONE, null)
                }
            })
            start()
        }
    }

    private data class ShadePullConfig(
        val duration: Long,
        val translationDp: Float,
        val scaleTarget: Float,
        val moveStart: Float,
        val moveEnd: Float,
        val alphaStart: Float,
        val alphaEnd: Float,
        val scaleStart: Float,
        val scaleEnd: Float,
        val moveCurve: PathInterpolator,
        val fadeCurve: PathInterpolator,
        val scaleCurve: PathInterpolator
    )

    private fun resetPillShadePullView(root: View) {
        root.visibility = View.GONE
        root.alpha = 0f
        root.translationY = 0f
        root.scaleX = 1f
        root.scaleY = 1f
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        // HARD BACK BUTTON TO EXIT REPLY
        if (isReplyMode && event?.keyCode == KeyEvent.KEYCODE_BACK) {
            exitReplyMode()
            return true
        }
        return super.onKeyEvent(event)
    }

    /** The window that made the last shade decision, in words, for the log line that follows it. */
    private var lastShadeEvidence = "no check yet"

    private fun checkNotificationShadeState(): Boolean {
        val (open, evidence) = shadeVerdictOf(windows, resources.displayMetrics.heightPixels)
        lastShadeEvidence = evidence
        return open
    }

    /**
     * The shelf question, asked on its own thread, with the answer posted back. One flight at a time: two
     * overlapping probes would be two binder round trips for one verdict, which is the cost this exists to
     * remove. Nothing here touches a view - the only reason the answer is applied in a `post` and not where it
     * is computed.
     */
    private fun probeShadeOffMainThread() {
        if (!shelfProbeBusy.compareAndSet(false, true)) return
        val screenHeight = resources.displayMetrics.heightPixels
        shelfProbe.execute {
            val verdict = runCatching { shadeVerdictOf(windows, screenHeight) }
                .getOrDefault(false to "probe failed")
            mainHandler.post {
                shelfProbeBusy.set(false)
                lastShadeEvidence = verdict.second
                val open = verdict.first
                if (open != isShadeOpen) {
                    // What decided it, not only that it changed: a heads-up tall enough to read as a pulled shade
                    // used to wipe the count on its own, and the log could not tell a real pull from that.
                    TraceLog.gesture(
                        "shade -> ${if (open) "OPEN" else "closed"} ($lastShadeEvidence) " +
                            "policy=${AppSettings.getShadeOpenPolicyName(this)}"
                    )
                    isShadeOpen = open
                    this@HyperAccessibilityService.visualRoot?.animate()?.alpha(1f)?.setDuration(120)?.start()
                    if (isShadeOpen) markIslandNotificationsSeenFromShade()
                }
            }
        }
    }

    /**
     * The one implementation of "is the shade down", and it takes its rect from nowhere but itself: the old
     * version measured into `outlineRect`, the shared rect the island's outline provider reads while drawing.
     * Harmless on the main thread, a race the moment the probe moves off it, and a race with the render thread is
     * exactly the kind of bug that shows up as a card with a wrong corner once a week.
     */
    private fun shadeVerdictOf(windowList: List<AccessibilityWindowInfo>?, screenHeight: Int): Pair<Boolean, String> {
        if (windowList == null) return false to "windows=null"
        val r = android.graphics.Rect()
        var candidates = 0
        var decidedPct = 0
        var open = false
        for (w in windowList) {
            val systemUi = w.type == AccessibilityWindowInfo.TYPE_SYSTEM && w.root?.packageName == "com.android.systemui"
            if (!systemUi) continue
            candidates++
            // AccessibilityWindowInfo.getBoundsInScreen returns Unit before API 33, so it cannot sit inside a
            // condition: it fills the rect, then we read it.
            w.getBoundsInScreen(r)
            if (r.height() > screenHeight * 0.35f) {
                if (!open) decidedPct = r.height() * 100 / screenHeight
                open = true
            }
        }
        // Height as a share of the screen is the only thing separating a pulled shade from a big heads-up, so
        // the number that made the call is kept with the verdict.
        return open to (if (open) "systemui window ${decidedPct}% of screen"
            else "$candidates systemui window(s), none over 35%")
    }

    override fun onInterrupt() = Unit

    private fun postShowIsland() = mainHandler.post { showIslandInternal() }
    private fun postHideIsland() = mainHandler.post { hideIslandInternal() }
    private fun postUpdateIsland() = mainHandler.post { ensureIslandAwake(); updateAllToCurrentState() }
    private fun postExpandIsland() = mainHandler.post { setStageAnimated(IslandStage.STAGE3_FULL, ExpandReason.MANUAL_USER) }
    /** Remember which app should take the foreground after a quick action, so the card comes down when it does. */
    private fun markActionTakeoverWait(pkg: String?) {
        if (pkg.isNullOrBlank()) {
            TraceLog.line("ACTION", "action sent but the package is unknown - no takeover to wait for")
            return
        }
        actionTakeoverPkg = pkg
        actionTakeoverAt = System.currentTimeMillis()
        val actedOnKey = currentNotificationKey
        TraceLog.line(
            "ACTION",
            "$pkg acted on - card stays open, ring=${notificationRing.size}, page=$actedOnKey; waiting for its " +
                "window, bound ${ACTION_TAKEOVER_FALLBACK_MS}ms"
        )
        mainHandler.postDelayed({ maybeCloseAfterActionWait(pkg, actedOnKey) }, ACTION_TAKEOVER_FALLBACK_MS)
    }

    /**
     * The bound on [markActionTakeoverWait]. It only closes the card when nothing better has happened in the
     * meantime: if the page that was acted on is no longer the one on screen, a new message has arrived and it
     * has earned its own time on the island, so the wait is dropped rather than acted on.
     */
    private fun maybeCloseAfterActionWait(pkg: String, actedOnKey: String?) {
        if (actionTakeoverPkg != pkg) return
        if (currentStage != IslandStage.STAGE3_FULL) {
            // Somebody already put the island away - a reply's own collapse, a swipe, a tap. Re-opening the
            // pill from a timer would be worse than the bug this whole path exists to fix.
            actionTakeoverPkg = null
            TraceLog.line("ACTION", "the card was closed while waiting for $pkg - wait cleared")
            return
        }
        if (currentNotificationKey != actedOnKey) {
            actionTakeoverPkg = null
            TraceLog.line("ACTION", "the card moved on to another page while waiting for $pkg - wait dropped, nothing to close")
            return
        }
        actionTakeoverPkg = null
        TraceLog.line(
            "ACTION",
            "$pkg did not take the foreground within ${ACTION_TAKEOVER_FALLBACK_MS}ms - the action is done, so back to the pill"
        )
        returnToPillAfterAction()
    }

    /**
     * The island's own answer to "maine quick action use kiya, island ko wapas pill banna chahiye tha": back to
     * the pill when the app has taken over, and all the way to idle when the ring has nothing left in it. The
     * page itself is deliberately NOT deleted here - if the app cancels its notification the ring drops that
     * page in the same moment, and a page removed because we *hoped* the action worked is a chat the user loses
     * when it did not.
     */
    private fun returnToPillAfterAction() {
        if (isReplyMode) exitReplyMode()
        if (notificationRing.isEmpty()) {
            TraceLog.line("ACTION", "ring is empty after the action - idle")
            setStageAnimated(IslandStage.STAGE1_IDLE, expandReason)
        } else {
            TraceLog.line("ACTION", "${notificationRing.size} page(s) still in the ring - back to the pill")
            setStageAnimated(IslandStage.STAGE2_PING, expandReason)
        }
    }

    private fun postCollapseIsland() = mainHandler.post { if (isReplyMode) exitReplyMode() else setStageAnimated(IslandStage.STAGE1_IDLE, expandReason) }
    private fun postToggleExpanded() = mainHandler.post {
        // Tap expands/collapses the island shell only.
        // Do NOT auto-open/collapse a notification from a generic tap while full expanded;
        // that breaks action buttons and reply taps.
        setStageAnimated(
            if (currentStage == IslandStage.STAGE3_FULL) IslandStage.STAGE1_IDLE else IslandStage.STAGE3_FULL,
            ExpandReason.MANUAL_USER
        )
    }
    private fun postSwipeUpIsland() = mainHandler.post {
        if (isReplyMode) {
            exitReplyMode()
            return@post
        }
        autoCollapseRunnable?.let { mainHandler.removeCallbacks(it) }
        autoCollapseRunnable = null
        if (currentStage == IslandStage.STAGE3_FULL && notificationMode && notificationRing.isNotEmpty()) {
            getCurrentRingModel()?.let { updatePillBadge(it) }
            pillPreviewRoot?.visibility = View.VISIBLE
            pillPreviewRoot?.alpha = 1f
            setStageAnimated(IslandStage.STAGE2_PING, ExpandReason.MANUAL_USER)
        } else {
            clearNotificationRing("swipe-up")
            pillPreviewCount?.visibility = View.GONE
            setStageAnimated(IslandStage.STAGE1_IDLE, ExpandReason.MANUAL_USER)
        }
    }

    private fun postNotificationEvent(packageName: String, notificationKey: String?, appName: String, title: String, message: String, unreadCount: Int, conversationKey: String?, conversationKeySource: String?, postTime: Long, contentIntent: PendingIntent?, actions: List<Notification.Action>, smallIcon: Icon?, isMessagingStyle: Boolean = false, displayTimeMs: Long = 0L) {
        mainHandler.post {
            if (!AppSettings.isIslandEnabled(this)) return@post
            // Two separate questions used to be one: "should the island pop while he reads the shelf?" and
            // "should this message exist in the ring?". The answer to the first is no; the answer to the second
            // is always yes, and refusing it is what made messages vanish during a rain.
            val quietForShade = isShadeOpen &&
                AppSettings.getShadeOpenPolicy(this) == AppSettings.SHADE_POLICY_COUNT_QUIETLY
            if (isShadeOpen && !quietForShade) {
                // Notification shade is already open; user is looking at notifications.
                // Do not create/update the pill badge for messages arriving while shade is open.
                markIslandNotificationsSeenFromShade()
                return@post
            }
            val now = System.currentTimeMillis()
            val display = buildDisplayText(appName, title, message)
            TraceLog.ingest("show $packageName '${display.title}' ${display.message.take(40)}")
            // Safety net: NotificationListener should suppress echoes first, but Accessibility fallback can still duplicate.
            if (ReplyEchoSuppressor.shouldSuppress(packageName, display.title, display.message, null, notificationKey)) {
                TraceLog.ingest("drop reply-echo from $packageName")
                return@post
            }
            val fingerprint = "$packageName|${display.title}|${display.message}"
            if (fingerprint == lastIslandFingerprint && now - lastIslandFingerprintTime < 1000L) {
                TraceLog.ingest("drop duplicate from $packageName (${display.title})")
                return@post
            }
            lastIslandFingerprint = fingerprint; lastIslandFingerprintTime = now
            if (isReplyMode) {
                TraceLog.ingest("hold while replying: $packageName (${display.title})")
                return@post
            }
            val finalConversationKey = conversationKey ?: "$packageName|title|${title.lowercase(Locale.getDefault()).trim()}"
            val finalConversationKeySource = conversationKeySource ?: "serviceFallback"
            val incomingModel = NotificationModel(packageName, notificationKey, appName, title, message, unreadCount, finalConversationKey, finalConversationKeySource, postTime, contentIntent, actions, smallIcon, isMessagingStyle, displayTimeMs)
            addOrUpdateNotificationRing(incomingModel)
            if (quietForShade) {
                TraceLog.ingest("quiet: shade open, ring=${notificationRing.size}, no pop from $packageName")
                return@post
            }
            if (currentStage == IslandStage.STAGE3_FULL) {
                // User is actively reading expanded island; don't auto-shrink/replace it.
                TraceLog.ingest("expanded already open - ring updated, no morph: $packageName")
                return@post
            }
            processNextInQueue()
        }
    }


    private fun postPreviewReplyAnimation(replySecond: Boolean) {
        mainHandler.post {
            if (!AppSettings.isIslandEnabled(this)) return@post
            ensureIslandAwake()
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
            unreadCount = 1,
            conversationKey = "test|reply|${if (replySecond) "second" else "first"}",
            conversationKeySource = "preview",
            postTime = System.currentTimeMillis(),
            contentIntent = null,
            actions = actions,
            smallIcon = null
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

    private fun postPreviewPillIcon(targetPackageName: String, count: Int) {
        mainHandler.post {
            if (!AppSettings.isIslandEnabled(this)) return@post
            ensureIslandAwake()
            val app = getAppName(targetPackageName)
            pillChatCount = count.coerceIn(1, 99)
            val model = NotificationModel(
                packageName = targetPackageName,
                notificationKey = null,
                appName = app,
                title = app,
                message = "Pill icon preview",
                unreadCount = count.coerceIn(1, 99),
                conversationKey = "preview|pill|$targetPackageName",
                conversationKeySource = "preview",
                postTime = System.currentTimeMillis(),
                contentIntent = null,
                actions = emptyList(),
                smallIcon = null
            )
            updateNotificationContent(model)
            updatePillBadge(model)
            triggerPillPreview()
        }
    }

    private fun postPreviewShadePull() {
        mainHandler.post {
            if (!AppSettings.isIslandEnabled(this)) return@post
            ensureIslandAwake()
            pillChatCount = 7
            pillPreviewIcon?.setImageDrawable(loadGenericPillGlyph("org.telegram.messenger"))
            pillPreviewCount?.text = "7"
            pillPreviewCount?.visibility = View.VISIBLE
            pillPreviewRoot?.visibility = View.VISIBLE
            pillPreviewRoot?.alpha = 1f
            pillPreviewRoot?.translationY = 0f
            pillPreviewRoot?.scaleX = 1f
            pillPreviewRoot?.scaleY = 1f
            currentStage = IslandStage.STAGE2_PING
            updateIslandLayout(
                dp(getTargetWidth(IslandStage.STAGE2_PING)),
                dp(getTargetHeight(IslandStage.STAGE2_PING)),
                dp(getTargetRadius(IslandStage.STAGE2_PING)).toFloat()
            )
            mainHandler.postDelayed({ markIslandNotificationsSeenFromShade() }, 450L)
        }
    }

    private fun buildPreviewAction(title: String, requestCode: Int): Notification.Action {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent().setClassName(packageName, "$packageName.ui.TestLabActivity")
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getActivity(this, 7300 + requestCode, launchIntent, flags)
        val icon = Icon.createWithResource(this, android.R.drawable.ic_menu_send)
        return Notification.Action.Builder(icon, title, pi).build()
    }

    /**
     * Insert at the front, merged with any existing page for the same conversation.
     *
     * Two things this had wrong and now fixes:
     *  - the list was described as "bounded" but nothing capped it, so a chatty group grew it
     *    without limit while the island stayed open: the "1/N" counter only ever went up and the
     *    badge sum kept adding unread counts of pages nobody could reach.
     *  - it reset currentRingIndex to 0 unconditionally, i.e. a fresh message yanked a reader who
     *    was on page 2/3 back to the newest page mid-swipe. We now re-find the page they were
     *    reading by conversation key and stay on it; only someone already on the newest page follows
     *    the new arrival. (If you prefer always-jump-to-newest, that is this whole when-block -> 0.)
     */
    private fun addOrUpdateNotificationRing(model: NotificationModel) {
        val readingKey = notificationRing.getOrNull(currentRingIndex)?.conversationKey
        val index = notificationRing.indexOfFirst { it.conversationKey == model.conversationKey }
        val merged = if (index >= 0) {
            val old = notificationRing[index]
            model.copy(unreadCount = maxOf(old.unreadCount, model.unreadCount))
        } else {
            model
        }
        if (index >= 0) notificationRing.removeAt(index)
        notificationRing.add(0, merged)
        ringEvent("${if (index >= 0) "merge" else "new page"} ${model.packageName} '${model.title}' unread=${merged.unreadCount} ring=${notificationRing.size}")
        while (notificationRing.size > MAX_RING_ITEMS) {
            // Eviction order matters more than the cap. On this device Snapchat posts eight promo
            // notifications that carry CATEGORY_MESSAGE but no conversation extras; with a plain
            // tail-drop that flood pushed every real chat out of the ring, which is part of what felt
            // like "my messages disappear". Non-conversations go first, newest real chat survives.
            val junk = notificationRing.indexOfLast { !it.isMessagingStyle }
            val dropped = if (junk >= 0) junk else notificationRing.lastIndex
            ringEvent("over capacity (${notificationRing.size} > $MAX_RING_ITEMS) dropping ${notificationRing[dropped].packageName}")
            notificationRing.removeAt(dropped)
        }
        currentRingIndex = when {
            readingKey == null || currentRingIndex == 0 -> 0
            else -> notificationRing.indexOfFirst { it.conversationKey == readingKey }.coerceAtLeast(0)
        }
        pillChatCount = notificationRing.size
        notificationQueue.clear()
        notificationQueue.add(merged)
    }

    private fun getCurrentRingModel(): NotificationModel? {
        if (notificationRing.isEmpty()) return null
        currentRingIndex = currentRingIndex.coerceIn(0, notificationRing.lastIndex)
        return notificationRing[currentRingIndex]
    }

    private fun clearNotificationRing(cause: String) {
        if (notificationRing.isNotEmpty()) {
            // A wipe used to be the one change nobody could see coming, so it names its victims.
            val killed = notificationRing.take(4).joinToString(", ") { "'${it.title}'" } +
                if (notificationRing.size > 4) ", +${notificationRing.size - 4} more" else ""
            ringEvent("CLEAR ${notificationRing.size} pages [$killed] because=$cause")
        }
        notificationRing.clear()
        currentRingIndex = 0
        notificationQueue.clear()
        pillChatCount = 0
        // A collapse/clear mid-push would otherwise strand the snapshot layer and the guard.
        ringSwapInFlight = false
        detachRingPushLayer()
    }

    /**
     * Discard ONE conversation from the ring. This is the operation the app never had: tapping a card
     * used to hide the whole island while keeping the tapped chat, and the source app cancelling its
     * own notification (measured: Instagram does it ~0.5 s after posting, reason REASON_APP_CANCEL)
     * went completely unnoticed - so the read chat came back with the next message and the unread
     * badge only ever went up.
     */
    private fun dismissConversationFromRing(conversationKey: String, reason: Int = -1) {
        var index = notificationRing.indexOfFirst { it.conversationKey == conversationKey }
        if (index < 0) {
            // A cancel arrives with the status-bar key (`pkg|tag|id`), while pages are filed under the
            // conversation key the extractor derived (`pkg|sender|NAME`). They only match when the extractor
            // happens to produce the same shape - and when it does not, the cancel is dropped on the floor. His
            // log has exactly that, 96 ms after a reply: `dismiss: no page for key=com.instagram.android
            // |thread|kish.ank001 ring=1`, and the read chat then stayed on the island for the rest of the
            // session - which is also part of why the card never went back to being a pill. Fall back to the key
            // that was stored WITH the page: the same string, a different field name.
            index = notificationRing.indexOfFirst { it.notificationKey == conversationKey }
            if (index < 0) {
                ringEvent("dismiss: no page for key=$conversationKey ring=${notificationRing.size}")
                return
            }
            ringEvent("dismiss: matched the stored notification key, not a conversation key ($conversationKey)")
        }
        val gone = notificationRing[index].title
        notificationRing.removeAt(index)
        if (ringSwapInFlight) {
            // A swipe push owns a frozen snapshot of the page that was just deleted.
            ringSwapInFlight = false
            detachRingPushLayer()
        }
        currentRingIndex = if (notificationRing.isEmpty()) 0 else index.coerceAtMost(notificationRing.lastIndex)
        pillChatCount = notificationRing.size
        notificationQueue.clear()
        val current = getCurrentRingModel()
        endRingSwap("dismiss")
        ringEvent(
            "dismiss dropped 1 page remaining=${notificationRing.size} chats=$pillChatCount " +
                "gone='$gone' because=${removalReasonName(reason)}"
        )
        if (current == null) {
            postCollapseIsland()
            return
        }
        notificationQueue.add(current)
        updateNotificationContent(current)
        updatePillBadge(current)
        // Deliberately no stage change: expanded stays expanded and shows the next chat, a pill stays
        // a pill with the new badge. Answering one chat must never hide the others.
    }

    private fun processNextInQueue() {
        if (isReplyMode) return // GOAL: Pause queue during reply process

        // New premium default: notifications do NOT auto-expand.
        // They update a compact pill badge; user taps the pill to expand.
        val latest = notificationQueue.pollLast() ?: run { isProcessingQueue = false; return }
        notificationQueue.clear()
        isProcessingQueue = true
        notificationMode = true
        updateNotificationContent(latest)
        updatePillBadge(latest)
        triggerPillPreview()
    }

    /**
     * Every ring mutation is announced through here so the badge can say *why* it moved. Without a cause
     * the count going down reads as the app taking notifications back - "counting badh ghat kaise raha
     * hai ... ye wapas ghar kaise ja rha hai? Notification telegram wapas le rha hai kya" - and from
     * inside the phone a correct drop and a lost page are otherwise indistinguishable.
     */
    private fun ringEvent(message: String) {
        lastRingOp = message.substringBefore(' ')
        TraceLog.ring(message)
    }

    /**
     * Names, not numbers: he reads this on a phone screen. The numeric codes are only printed when nothing
     * matches, because guessing that a constant equals 12 without compiling against it would be worse than
     * saying "reason=12".
     */
    private fun removalReasonName(reason: Int): String = when (reason) {
        0 -> "opened-from-island"
        android.service.notification.NotificationListenerService.REASON_CANCEL -> "he-swiped-it-away"
        android.service.notification.NotificationListenerService.REASON_APP_CANCEL -> "app-cancelled-it-itself"
        else -> "reason=$reason"
    }

    private fun updatePillBadge(model: NotificationModel) {
        armFrameWatch() // a badge change redraws the island with no animation to watch it under
        pillPreviewIcon?.background = null
        pillPreviewIcon?.imageTintList = null
        pillPreviewIcon?.clearColorFilter()
        pillPreviewIcon?.setImageDrawable(loadPillNotificationIcon(model.packageName, model.smallIcon))
        // The digit and its visibility belong to the `pillChatCount` property now - one writer, one log line.
        // This function owns the icon, which is the only part of the badge that used to be re-decided here.
    }

    private fun triggerPillPreview() {
        // Flood rule, straight off the trace: a notification arriving while the pill is already popping used
        // to cancel that pop, tear the layout and layer down, and start again - 44 setups in 6.7 s of a
        // Telegram blast, 33 of them not producing a single frame, ~2 s of main thread burned on nothing
        // the user could see. The callers have already written the new content and badge by the time this
        // runs, so a pill that is already the right size needs a nudge, not a rebuild.
        val pillW = dp(getTargetWidth(IslandStage.STAGE2_PING))
        val pillH = dp(getTargetHeight(IslandStage.STAGE2_PING))
        if (visualRoot != null && currentStage == IslandStage.STAGE2_PING &&
            islandLayoutParams?.width == pillW && islandLayoutParams?.height == pillH) {
            isProcessingQueue = false // the caller armed it; the morph that will not run is what clears it
            when {
                ringSwapInFlight || dragMode != DRAG_NONE -> TraceLog.morph("ping skipped: swipe owns the island")
                morphAnimator?.isRunning == true -> TraceLog.morph("ping skipped: pop already running")
                else -> {
                    TraceLog.morph("ping re-pop (no layout, no layer)")
                    popPillOnly()
                }
            }
            armFrameWatch()
            return
        }
        morphAnimator?.cancel()
        autoCollapseRunnable?.let { mainHandler.removeCallbacks(it) }
        endRingSwap("pill preview")
        clearDragVisuals()
        currentStage = IslandStage.STAGE2_PING
        expandReason = ExpandReason.AUTO_NOTIFICATION
        notificationMode = true

        gridRoot?.animate()?.cancel()
        gridRoot?.visibility = View.GONE
        gridRoot?.alpha = 0f
        pillPreviewRoot?.visibility = View.VISIBLE
        pillPreviewRoot?.alpha = 0f
        pillPreviewRoot?.scaleX = 0.92f
        pillPreviewRoot?.scaleY = 0.92f

        val curW = islandLayoutParams?.width ?: dp(AppSettings.getIslandWidthDp(this))
        val curH = islandLayoutParams?.height ?: dp(AppSettings.getIslandHeightDp(this))
        val curR = islandBackground?.cornerRadius ?: dp(AppSettings.getIslandCornerRadiusDp(this)).toFloat()
        val targetW = dp(getTargetWidth(IslandStage.STAGE2_PING))
        val targetH = dp(getTargetHeight(IslandStage.STAGE2_PING))
        val targetR = dp(getTargetRadius(IslandStage.STAGE2_PING)).toFloat()

        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = morphWindowFor(360L)
            interpolator = morphCurve // the spring styles need this; the old four get the same curve as before
            addUpdateListener {
                val t = it.animatedValue as Float
                updateIslandLayoutForMorph(lerpEven(curW, targetW, t), lerpEven(curH, targetH, t), lerp(curR, targetR, t), t, ((it.currentPlayTime.toFloat() / it.duration.toFloat()).coerceIn(0f, 1f)))
                applyMorphCarry(t)
                morphMeter?.frame(System.nanoTime(), t)
                pillPreviewRoot?.alpha = t
                pillPreviewRoot?.scaleX = 0.92f + 0.08f * t
                pillPreviewRoot?.scaleY = 0.92f + 0.08f * t
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    endMorphPerf("notify->ping")
                    pillPreviewRoot?.alpha = 1f
                    pillPreviewRoot?.scaleX = 1f
                    pillPreviewRoot?.scaleY = 1f
                    isProcessingQueue = false
                    forceRegionUpdate()
                }
            })
        }
        morphAnimator = anim
        beginMorphPerf("notify->ping", anim.duration, curW, curH, targetW, targetH, targetR, towardCard = false)
        anim.start()
    }

    /**
     * The pill's pop at the size the pill already is: alpha and scale on a drawable, no layout pass, no
     * hardware layer churn. Same gesture the morph performs, minus the machinery that cannot be seen.
     */
    private fun popPillOnly() {
        pillPreviewRoot?.let { v ->
            v.animate().cancel()
            v.visibility = View.VISIBLE
            v.alpha = 0.5f
            v.scaleX = 0.96f
            v.scaleY = 0.96f
            v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(150L)
                .setInterpolator(DecelerateInterpolator(2f)).start()
        }
        gridRoot?.animate()?.cancel()
        gridRoot?.visibility = View.GONE
        gridRoot?.alpha = 0f
    }

    private fun playFluidTransitionAnimation(next: NotificationModel) {
        val isFlash = notificationQueue.size >= 35
        val exit = ValueAnimator.ofFloat(0f, 1f).apply { duration = if (isFlash) 120L else 300L; interpolator = AccelerateInterpolator(); addUpdateListener { this@HyperAccessibilityService.gridRoot?.alpha = 1f - it.animatedValue as Float; this@HyperAccessibilityService.gridRoot?.translationY = it.animatedValue as Float * 20f } }
        val entry = ValueAnimator.ofFloat(0f, 1f).apply { duration = if (isFlash) 150L else 400L; interpolator = morphInterpolator; addUpdateListener { this@HyperAccessibilityService.gridRoot?.alpha = it.animatedValue as Float; this@HyperAccessibilityService.gridRoot?.translationY = -20f * (1f - it.animatedValue as Float) } }
        exit.addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(a: Animator) { updateNotificationContent(next); entry.start() } })
        entry.addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(a: Animator) { scheduleAutoCollapse() } })
        exit.start()
    }

    private fun buildTitleWithUnreadCount(title: String, unreadCount: Int): CharSequence {
        // Keep message line clean; unread metadata lives in the title as subtle styled text.
        if (unreadCount <= 1 || title.isBlank()) return title
        // "new", not "messages": this is the app's own unread badge for this thread, which is not the
        // same number as how many lines the notification happens to carry.
        val suffix = "  $unreadCount new"
        val full = "$title$suffix"
        return SpannableString(full).apply {
            val start = title.length
            setSpan(ForegroundColorSpan(Color.rgb(145, 165, 178)), start, full.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(RelativeSizeSpan(0.72f), start, full.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.NORMAL), start, full.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun updateNotificationContent(model: NotificationModel) {
        currentPendingIntent = model.contentIntent; currentPackageName = model.packageName; currentNotificationKey = model.notificationKey; currentReplyAction = null
        // `getApplicationIcon` is a binder round trip; it used to run on every one of these calls, and the
        // stall sampler caught the main thread sitting in `transactNative` 93 times for 23.3 s total.
        if (model.packageName != lastIconPkg) {
            lastIconPkg = model.packageName
            appIconView?.setImageDrawable(loadAppIcon(model.packageName))
        }
        if (UpdateGate.textChanged(lastAppNameShown, model.appName)) {
            lastAppNameShown = model.appName
            appNameText?.text = model.appName
        }
        val ringIndicator = if (notificationRing.size > 1) " · ${currentRingIndex + 1}/${notificationRing.size}" else ""
        val stamp = "${timeLabelFor(model)}$ringIndicator"
        if (UpdateGate.textChanged(lastStampShown, stamp)) {
            lastStampShown = stamp
            timeStampText?.text = stamp
        }
        // The spanned title is the expensive one: three spans, so every setText rebuilt the MeasuredText
        // (nAddStyleRun/nBuildMeasuredText in his log). It is only rebuilt when title or count really moved.
        if (UpdateGate.textChanged(lastTitleShown, model.title) || model.unreadCount != lastUnreadShown) {
            lastTitleShown = model.title; lastUnreadShown = model.unreadCount
            titleText?.text = buildTitleWithUnreadCount(model.title, model.unreadCount)
            val hidden = model.title.isBlank()
            if (hidden != lastTitleWasHidden) { lastTitleWasHidden = hidden; titleText?.visibility = if (hidden) View.GONE else View.VISIBLE }
        }
        if (UpdateGate.textChanged(lastMessageShown, model.message)) {
            lastMessageShown = model.message
            messageText?.text = model.message
            val hidden = model.message.isBlank()
            if (hidden != lastMessageWasHidden) { lastMessageWasHidden = hidden; messageText?.visibility = if (hidden) View.GONE else View.VISIBLE }
        }
        if (!UpdateGate.sameElements(lastTiles, model.actions)) {
            lastTiles = model.actions
            setupActionTiles(model.actions)
        }
        forceRegionUpdate()
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
        TraceLog.reply("send tapped: pkg=$currentPackageName text=${replyEditorEditText?.text?.toString()?.take(40).orEmpty()}")
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
        lastTitleShown = null; lastUnreadShown = -1 // the gate must not conclude the title is already right
            messageText?.text = replyText
            exitReplyMode()
            // A reply is the one case where waiting for the app is wrong: sending does not bring the chat app
            // forward, it leaves us looking at the island, and the 420 ms collapse is behaviour he has already
            // accepted. The takeover wait is armed as well so a reply fired while the app is still in the
            // background comes down the same way the quick actions do.
            markActionTakeoverWait(currentPackageName)
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
                        // This used to be: show a tick, wait 500 ms, *then* send the action and collapse the
                        // card. The tester's log has seven of those - up, then STAGE3_FULL -> STAGE1_IDLE
                        // 503-537 ms later, every time he pressed a button - so the action landed, the island
                        // vanished before he could see it, and repeated taps read as "tick tick, nothing
                        // happening". Send now, mark it now, and leave the card up: if the app cancels the
                        // notification, the ring's own dismiss path closes the page, which is the honest
                        // moment to collapse - not a timer we guessed at.
                        val oldT = text
                        text = "✓ $oldT"
                        setTextColor(Color.GREEN)
                        TraceLog.gesture("action tapped: '$oldT' sent immediately, card stays open")
                        try {
                            action.actionIntent.send()
                            markActionTakeoverWait(currentPackageName)
                        } catch (_: Exception) {
                            text = oldT
                            setTextColor(Color.WHITE)
                            TraceLog.line("ACTION", "action '$oldT' send FAILED - label reverted, nothing to wait for")
                        }
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

    // Ring carousel state: one push in flight at a time, plus the frozen outgoing page.
    private var ringSwapInFlight = false
    private var ringSwapStartedAt = 0L
    private var ringSwapGuard: Runnable? = null

    /** What the current touch is doing to the card. NONE until the finger proves it is a drag. */
    private val DRAG_NONE = 0
    private val DRAG_PAGES = 1
    private val DRAG_NUDGE = 2
    private var dragMode = DRAG_NONE
    private var dragOlder = true
    private var dragPrevIndex = 0
    private var dragPrevModel: NotificationModel? = null
    private var ringPushLayer: ImageView? = null
    private var ringPushBitmap: Bitmap? = null

    private fun detachRingPushLayer() {
        ringPushLayer?.let { layer -> (layer.parent as? FrameLayout)?.removeView(layer) }
        ringPushLayer = null
        ringPushBitmap?.recycle()
        ringPushBitmap = null
    }

    /**
     * Ends a page push from whichever path gets there first: the settle animation, its guard timeout, a
     * stage change, a dismiss, or the ring being cleared. Idempotent, and it always puts the live page back
     * at translationX/Y = 0.
     *
     * This exists because of b1333 on a real phone. The settle used to be undone only by `withEndAction`,
     * which is *skipped* when the animation is cancelled - and triggerPillPreview()/setStageAnimated()
     * both call `gridRoot?.animate()?.cancel()`. So a message arriving (or a swipe-up) during a push left
     * the card's content parked a full card-width outside the pill - a blank card with "1/2" still
     * counting - and left ringSwapInFlight true, which then ignored every swipe after it. The tester's
     * words: "left right kuchh work nahi kiya" + "jagah khali hai". One owner for the reset, plus a guard
     * that is a posted Runnable rather than an animation callback, is what stops that recurring.
     */
    private fun endRingSwap(reason: String) {
        ringSwapGuard?.let { mainHandler.removeCallbacks(it) }
        ringSwapGuard = null
        val busy = ringSwapInFlight || dragMode != DRAG_NONE || ringPushLayer != null
        val moved = (gridRoot?.translationX ?: 0f) != 0f || (gridRoot?.translationY ?: 0f) != 0f ||
            (pillPreviewRoot?.translationX ?: 0f) != 0f || (pillPreviewRoot?.translationY ?: 0f) != 0f
        if (!busy && !moved) return
        dragMode = DRAG_NONE
        dragPrevModel = null
        ringSwapInFlight = false
        detachRingPushLayer()
        gridRoot?.let { it.animate().cancel(); it.translationX = 0f; it.translationY = 0f }
        pillPreviewRoot?.let { it.animate().cancel(); it.translationX = 0f; it.translationY = 0f }
        if (busy) ringEvent("swap ended ($reason)")
    }

    /**
     * Push Slide Carousel V2.
     *
     * V1 was a cross-fade: gridRoot faded to alpha 0 while sliding 28dp, the content swapped,
     * then it faded back in — and the swap re-wrapped text at whatever width the card happened
     * to be mid-morph. V2 never touches opacity and pushes two pages in lock-step:
     *   - outgoing = a frozen snapshot of the live view, drawn on top inside the same card
     *   - incoming = the live gridRoot, updated once, already laid out at the locked width
     *   - both travel the same distance on the same frames; the card clipToOutline masks edges
     * 220ms total (110 + 110, no gap). A second flick is ignored until the push settles, so a
     * half-swapped page is no longer reachable.
     */
    /**
     * The live touch decision for one gesture; the rules themselves are Android-free in [IslandGesture]
     * so CI can hold them to the report. Rebuilt on every DOWN because its thresholds are proportions
     * of the card, and the card changes width with the stage.
     */
    private var islandGesture: IslandGesture? = null

    private fun ensureGesture(): IslandGesture {
        val cfg = ViewConfiguration.get(this)
        val cardW = (islandView?.width ?: 0).coerceAtLeast(dp(220)).toFloat()
        return IslandGesture(
            touchSlopPx = cfg.scaledTouchSlop.toFloat(),
            minFlingPxPerS = cfg.scaledMinimumFlingVelocity.toFloat(),
            // 18% of the card, not 24%: with the neighbour now visible during the drag the page change is
            // obvious, so a small deliberate flick should be enough. Committing also needs either this
            // travel or a real fling velocity - see IslandGesture.end.
            pageCommitPx = maxOf(dp(20).toFloat(), cardW * 0.18f),
            maxDragPx = cardW * 0.45f,
            maxLiftPx = dp(44).toFloat()
        )
    }

    private fun draggableView(): View? =
        if (currentStage == IslandStage.STAGE3_FULL) gridRoot else (pillPreviewRoot ?: islandView)

    /**
     * Finger-follow. Two modes, and which one applies depends on whether there is a page to push:
     *  - DRAG_PAGES - the page under the finger is frozen into a layer and the live view already holds
     *    the neighbour, so both travel together inside the pill. This is the fix for "left ya right side
     *    kuchh nahi tha": the neighbour used to be built only at release, and translating just gridRoot
     *    (the pill background belongs to islandView and never moves) slid the content out of a fixed
     *    black card and left the side being dragged towards holding nothing. Now the page you are pulling
     *    in is on screen while you pull.
     *  - DRAG_NUDGE - no neighbour (ring edge, pill stage, vertical swipe): shift the content a little as
     *    resistance, hard-capped so no empty band can appear inside the pill. The cap is *rendering only*;
     *    the recogniser still decides on its own uncapped numbers, so a small visible nudge does not make
     *    a swipe harder to commit.
     */
    private fun showDragOffset(g: IslandGesture) {
        if (ringSwapInFlight) return // a settle owns the transform until it ends
        if (dragMode == DRAG_NONE && g.axisIsHorizontal && g.offsetX != 0f &&
            currentStage == IslandStage.STAGE3_FULL
        ) {
            dragMode = if (startRingPush(older = g.offsetX < 0f)) DRAG_PAGES else DRAG_NUDGE
        }
        if (dragMode == DRAG_PAGES) {
            val host = gridRoot
            val layer = ringPushLayer
            if (host == null || layer == null || host.width <= 0) return
            val dir = if (dragOlder) -1f else 1f
            val width = host.width.toFloat()
            host.animate().cancel()
            host.translationX = g.offsetX - dir * width
            host.translationY = 0f
            layer.animate().cancel()
            layer.translationX = g.offsetX
            layer.translationY = 0f
            return
        }
        val v = draggableView() ?: return
        val capX = dp(18).toFloat()
        v.animate().cancel()
        v.translationX = g.offsetX.coerceIn(-capX, capX)
        v.translationY = g.offsetY.coerceIn(-dp(20).toFloat(), dp(6).toFloat())
    }

    /** Release without a commit: the pushed page goes back the way it came, on the settle's own curve. */
    private fun springBackDrag() {
        if (dragMode == DRAG_PAGES) {
            finishRingPush(ringPushLayer?.translationX ?: 0f, commit = false)
            return
        }
        val v = draggableView() ?: return
        v.animate().cancel()
        v.animate().translationX(0f).translationY(0f)
            .setDuration(150L).setInterpolator(ghostMagneticInterpolator)
            .start()
    }

    private fun clearDragVisuals() {
        val v = draggableView()
        v?.animate()?.cancel()
        v?.translationX = 0f
        v?.translationY = 0f
    }

    /**
     * Puts the card into "two pages under the finger": the visible page is snapshotted into
     * [ringPushLayer], the live view is re-pointed at the neighbour and parked one card-width away, where
     * a continuing drag would have it. Returns false when there is nothing to push (ring edge, reply
     * mode, collapsed stage, no measurable card, or a snapshot the system refused to allocate) so the
     * caller falls back to a nudge instead of showing a half-built carousel.
     *
     * The snapshot is the whole trick: text is static while a drag is happening, so a frozen image is
     * indistinguishable from a live second card - and it costs no layout, no reflow, and no re-wrapped
     * paragraph mid-gesture (the thing that made the old cross-fade look cheap).
     */
    private fun startRingPush(older: Boolean): Boolean {
        if (dragMode != DRAG_NONE || ringSwapInFlight) return false
        if (isReplyMode || currentStage != IslandStage.STAGE3_FULL || notificationRing.size <= 1) return false
        val nextIndex = if (older) {
            (currentRingIndex + 1).coerceAtMost(notificationRing.lastIndex)
        } else {
            (currentRingIndex - 1).coerceAtLeast(0)
        }
        if (nextIndex == currentRingIndex) return false
        val host = gridRoot
        val card = host?.parent as? FrameLayout
        if (host == null || card == null || host.width <= 0 || host.height <= 0) return false

        val dir = if (older) -1f else 1f
        val width = host.width.toFloat()
        val prevIndex = currentRingIndex
        val prevModel = getCurrentRingModel()
        dragOlder = older
        dragPrevIndex = prevIndex
        dragPrevModel = prevModel
        ringSwapStartedAt = System.currentTimeMillis()

        val layer: ImageView? = try {
            val shot = Bitmap.createBitmap(host.width, host.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(shot)
            canvas.translate(-host.translationX, -host.translationY)
            host.draw(canvas)
            ringPushBitmap = shot
            ImageView(card.context).apply {
                setImageBitmap(shot)
                scaleType = ImageView.ScaleType.FIT_XY
                translationX = host.translationX
                // No translationZ here on purpose: a child with Z is drawn in the parent's overlay pass,
                // which escapes clipToOutline. That leaked the frozen page outside the pill (the "ghost
                // content" seen after a swipe that got cancelled mid-push).
            }.also { view ->
                card.addView(view, FrameLayout.LayoutParams(host.width, host.height, Gravity.START or Gravity.TOP).apply {
                    leftMargin = host.left
                    topMargin = host.top
                })
                ringPushLayer = view
                view.bringToFront() // draw last = on top, without opting out of the parent clip
            }
        } catch (_: Throwable) {
            null // allocation refused
        }
        if (layer == null) {
            // No frozen page means no push to show: undo the content swap and let the nudge handle it.
            dragOlder = older
            dragPrevModel = null
            currentRingIndex = prevIndex
            getCurrentRingModel()?.let { updateNotificationContent(it) }
            ringEvent("push refused: snapshot unavailable")
            return false
        }

        currentRingIndex = nextIndex
        getCurrentRingModel()?.let { updateNotificationContent(it) }
        host.alpha = 1f
        host.animate().cancel()
        host.translationY = 0f
        // off is 0 at this instant, so the live neighbour sits exactly one card-width from where the
        // frozen page will be when the finger reaches it.
        host.translationX = -dir * width
        ringEvent("push started ${if (older) "older" else "newer"} -> page ${currentRingIndex + 1}/${notificationRing.size}")
        return true
    }

    /**
     * Ends a push: settle onto the neighbour (commit) or send it back (spring). Both layers always run
     * the same distance, the same duration and the same curve - different curves for the two pages is
     * what made the seam slide, which was the whole complaint about the old animation. Duration scales
     * with the travel that is left, so a flick that was nearly finished finishes quickly and a slow drag
     * takes its time.
     */
    private fun finishRingPush(fromOffsetPx: Float, commit: Boolean) {
        val host = gridRoot
        if (host == null || host.width <= 0) { endRingSwap("no host"); return }
        val width = host.width.toFloat()
        val dir = if (dragOlder) -1f else 1f
        val off = fromOffsetPx.coerceIn(-width * 0.45f, width * 0.45f)
        val layer = ringPushLayer
        val layerTarget = if (commit) dir * width else 0f
        val hostTarget = if (commit) 0f else -dir * width
        val travel = abs(hostTarget - (off - dir * width)).coerceAtLeast(1f)
        val settleMs = (90L + (150L * (travel / width))).toLong().coerceIn(90L, 240L)

        ringSwapInFlight = true
        ringSwapStartedAt = System.currentTimeMillis()
        ringEvent("settle ${if (commit) "commit" else "spring back"} off=${off.toInt()} ${settleMs}ms")

        host.animate().cancel()
        layer?.animate()?.cancel()
        layer?.translationX = off
        host.translationX = off - dir * width

        layer?.animate()
            ?.translationX(layerTarget)
            ?.setDuration(settleMs)
            ?.setInterpolator(ghostMagneticInterpolator)
            ?.setListener(object : AnimatorListenerAdapter() {
                // withEndAction alone is NOT enough: it is skipped when the animation is cancelled (e.g.
                // the user collapses mid-push), which strands the frozen page on screen.
                override fun onAnimationEnd(animation: Animator) = detachRingPushLayer()
                override fun onAnimationCancel(animation: Animator) = detachRingPushLayer()
            })
            ?.start()
        // Belt and braces: a posted Runnable, because an animation callback is exactly the thing that can
        // go missing when a stage morph cancels this settle.
        ringSwapGuard?.let { mainHandler.removeCallbacks(it) }
        ringSwapGuard = Runnable { endRingSwap("guard timeout") }.also { mainHandler.postDelayed(it, settleMs + 350L) }
        host.animate()
            ?.translationX(hostTarget)
            ?.setDuration(settleMs)
            ?.setInterpolator(ghostMagneticInterpolator)
            ?.withEndAction {
                if (!commit) {
                    // The live view was holding the neighbour; give the page back to the finger's origin.
                    currentRingIndex = dragPrevIndex
                    dragPrevModel?.let { updateNotificationContent(it) }
                }
                endRingSwap(if (commit) "settled" else "sprung back")
            }
            ?.start()
    }

    /** Kept for callers without a drag behind them (and as the ring-edge answer: false = spring back). */
    private fun navigateRingBySwipe(older: Boolean, fromOffsetPx: Float = 0f): Boolean {
        if (dragMode == DRAG_PAGES) { finishRingPush(fromOffsetPx, commit = true); return true }
        if (!startRingPush(older)) return false
        finishRingPush(fromOffsetPx, commit = true)
        return true
    }

    private fun triggerFluidExpansion() {
        morphAnimator?.cancel()
        endRingSwap("expand")
        clearDragVisuals()
        val startW = dp(AppSettings.getIslandWidthDp(this)); val pingW = dp(AppSettings.getIslandStage2WidthDp(this)); val targetW = dp(AppSettings.getIslandExpandedWidthDp(this))
        val startH = dp(AppSettings.getIslandHeightDp(this)); val targetH = dp(AppSettings.getIslandExpandedHeightDp(this))
        val startR = dp(AppSettings.getIslandCornerRadiusDp(this)).toFloat(); val targetR = dp(AppSettings.getIslandExpandedCornerRadiusDp(this)).toFloat()
        currentStage = IslandStage.STAGE3_FULL; expandReason = ExpandReason.AUTO_NOTIFICATION
        val ping = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 120L
            addUpdateListener {
                val t = it.animatedValue as Float
                updateIslandLayoutForMorph(lerpEven(startW, pingW, t), startH, startR, t, ((it.currentPlayTime.toFloat() / it.duration.toFloat()).coerceIn(0f, 1f)))
                applyMorphCarry(0f) // the ping half still wears the pill's glyph and pill's icon size
                morphMeter?.frame(System.nanoTime(), t)
            }
        }
        // Auto-notification expand, cleaned up for the mask approach:
        //  - 650 -> 360ms, so a defect can't hide inside a slow morph
        //  - no gridRoot alpha ramp (the outline clips the content; a fade just looked like blur)
        //  - no islandView scaleY squash (that scaled the TEXT, which is what read as jitter)
        val expand = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = morphWindowFor(if (AppSettings.getMorphStyle(this@HyperAccessibilityService) == AppSettings.MORPH_STYLE_BLUEPRINT)
                MotionVariant.V2_EXPAND_TOTAL_MS else 360L)
            interpolator = morphCurve // same reason as setStageAnimated: and the pin is widened so it is NOT eaten
            addUpdateListener {
                val t = it.animatedValue as Float
                updateIslandLayoutForMorph(lerpEven(pingW, targetW, t), lerpEven(startH, targetH, t), lerp(startR, targetR, t), t, ((it.currentPlayTime.toFloat() / it.duration.toFloat()).coerceIn(0f, 1f)))
                applyMorphCarry(0.5f + 0.5f * t) // the expand half finishes the travel the ping phase started
                morphMeter?.frame(System.nanoTime(), t)
                gridRoot?.visibility = View.VISIBLE
                // The other expand path, the other owner rule: when the carry drives the opacity it owns it for
                // the whole morph here too, or the last writer of the frame wins and the TestLab choice is a lie.
                // Except their own alpha now has exactly one writer for blueprint v2 morphs: the shape-fade's
                // gate below was a second writer on gridContentSec.alpha and it overrode the v2 strike ramp
                // on every frame of the auto path (D2 in the code-truth audit he asked for).
                if (!morphOpacityFollowsShape && !(morphV2On && morphTowardCard)) setMorphContentAlpha(t)
            }
        }
        val set = AnimatorSet().apply {
            playSequentially(ping, expand)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(a: Animator) {
                    // The EXPAND half's duration, not the pair's: this animator owns the shape, so it owns the
                    // spring's window. 720L here is what made the auto-notification morph arrive short and snap.
                    beginMorphPerf(
                        "notify->full", expand.duration, startW, startH, targetW, targetH, targetR,
                        towardCard = true,
                    )
                }

                override fun onAnimationEnd(a: Animator) {
                    endMorphPerf("notify->full")
                    gridRoot?.alpha = 1f
                    gridRoot?.translationY = 0f
                    islandView?.scaleX = 1f; islandView?.scaleY = 1f
                    syncContentWidth()
                    flushDeferredRegionUpdate()
                    scheduleAutoCollapse()
                    updateOutsideWatcherForState()
                }
            })
        }
        morphAnimator = set; set.start()
    }

    private fun setStageAnimated(target: IslandStage, reason: ExpandReason) {
        if (currentStage == target && morphAnimator?.isRunning == true) return
        TraceLog.stage("$currentStage -> $target ($reason)")
        // A stage change cancels in-flight view animations; if a page push is mid-settle that skips its
        // end action, so hand it to the one function that always restores the content position.
        endRingSwap("stage")
        clearDragVisuals()
        if (target == IslandStage.STAGE3_FULL && reason == ExpandReason.AUTO_NOTIFICATION) { triggerFluidExpansion(); return }
        morphAnimator?.cancel()
        val curW = this@HyperAccessibilityService.islandLayoutParams?.width ?: dp(AppSettings.getIslandWidthDp(this))
        val curH = this@HyperAccessibilityService.islandLayoutParams?.height ?: dp(AppSettings.getIslandHeightDp(this))
        val curR = this@HyperAccessibilityService.islandBackground?.cornerRadius ?: dp(AppSettings.getIslandCornerRadiusDp(this)).toFloat()
        currentStage = target
        expandReason = reason

        if (target == IslandStage.STAGE3_FULL) {
            // Cancel only - never start a second animator on pillPreviewRoot.alpha. There was one here
            // (to 0f over 140ms) while this same morph's update listener wrote `1f - t` to the very same
            // property, so which one landed on a given frame was a race, and the 140ms run finishing hid
            // the pill content with a pop mid-morph. One property, one owner per frame.
            pillPreviewRoot?.animate()?.cancel()
            gridRoot?.visibility = View.VISIBLE
            // Kept fully opaque so the mask reveal, not a fade, is what you see.
            gridRoot?.alpha = 1f
            islandView?.scaleY = 1f
            islandView?.scaleX = 1f
        } else if (target == IslandStage.STAGE2_PING) {
            // Full -> pill: keep badge visible and fade expanded content away.
            // Without this, expanded title/message gets clipped inside the small pill.
            pillPreviewRoot?.animate()?.cancel()
            pillPreviewRoot?.visibility = View.VISIBLE
            pillPreviewRoot?.alpha = 1f
            pillPreviewRoot?.bringToFront()
            gridRoot?.animate()?.cancel()
            gridRoot?.visibility = View.VISIBLE
        } else if (target == IslandStage.STAGE1_IDLE) {
            // The pill is the DESTINATION of this morph, not something it happens to end at - the collapse is
            // the hand-back of the icon, and a destination that only exists after the animation means the icon
            // rides into a capsule that is not drawn: goneBy onward the box shrinks around nothing and the pill
            // pops in after. Visible, opaque and on top from frame one, exactly like the ping branch, and the
            // frame loop is the one owner of its alpha below (the 140/160 ms pop race stays dead).
            pillPreviewRoot?.animate()?.cancel()
            pillPreviewRoot?.visibility = View.VISIBLE
            pillPreviewRoot?.alpha = 1f
            pillPreviewRoot?.bringToFront()
        }

        val targetW = dp(getTargetWidth(target))
        val targetH = dp(getTargetHeight(target))
        val targetR = dp(getTargetRadius(target)).toFloat()
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = morphWindowFor(
                if (AppSettings.getMorphStyle(this@HyperAccessibilityService) == AppSettings.MORPH_STYLE_BLUEPRINT) {
                    // his clock, not the Lab's: 950 ms in, 380 ms back - constants are the spec rn
                    if (target == IslandStage.STAGE3_FULL) MotionVariant.V2_EXPAND_TOTAL_MS else MotionVariant.V2_COLLAPSE_TOTAL_MS
                } else when (target) { IslandStage.STAGE1_IDLE -> 320L; IslandStage.STAGE2_PING -> 340L; else -> 380L })
            // Was: expandInterpolator out (0.34,1.56,0.64,1) and collapse in (0.55,0,0.1,1). Both were wrong
            // for the *drawn* box, and the arithmetic is in the commit: the expand curve overshoots past 1.0,
            // IslandMorphFrame.compute clamps it to the final size, and 29 of 46 frames at 120 Hz moved the
            // box by exactly 0.0% - the shape was finished in ~57 ms and the rest of the animation was content
            // fading inside a static box, which is the "masking lag raha hai" he keeps describing. The collapse
            // curve spent its first 4 frames on 0.1/0.3/0.5/0.7% of the distance and then covered 10.1% in one
            // frame: nothing, nothing, jhatka. One house curve both ways = 4-5% every frame, no dead frames.
            interpolator = morphCurve
            addUpdateListener {
                val t = it.animatedValue as Float
                updateIslandLayoutForMorph(lerpEven(curW, targetW, t), lerpEven(curH, targetH, t), lerp(curR, targetR, t), t, ((it.currentPlayTime.toFloat() / it.duration.toFloat()).coerceIn(0f, 1f)))
                applyMorphCarry(t)
                morphMeter?.frame(System.nanoTime(), t)
                // One owner of the pill's fade for every stage change, notification or not - and which way it
                // goes depends on which end of the morph the pill is. Expanding, it is the thing being LEFT:
                // it fades with t. Collapsing, it is the thing being ARRIVED at, and "1f - t on everything
                // that is not ping" faded the destination away for the whole shrink - the glyph swap rode into
                // a capsule that was never shown. That is "jab pill banta hai to icon gayab ho jata hai".
                if (target == IslandStage.STAGE3_FULL) pillPreviewRoot?.alpha = 1f - t
                // Morph opacity policy. The `&& notificationMode` that used to gate each branch is gone, and
                // his log is why it had to go: a tap-expand runs with notificationMode = false, so **no branch
                // ran at all** - the expanded content sat at alpha 1.0 through the whole collapse and was
                // switched to GONE in a single frame at the settle. His export prints the cut: the settle line
                // of a manual collapse reads `end stage->STAGE1_IDLE ... end-state size=366x104 clip=true
                // grid=V/1.0 pill=G/0.0` = fully opaque content one frame, nothing the next. "wapas collapse
                // ek jhatke se hota hai", verbatim. Same on the way out: the fade-in existed only for the
                // notification path, so a manual expand revealed the text with the moving clip edge instead
                // of cross-fading it - which is the other half of "masking lag rha hai".
                // The gate is not a preference: with it, the shape animates and the content does not.
                // The content column is width-locked (see expandedContentWidthPx) so none of these
                // fades hide a re-wrap; the fade is not the anti-jitter mechanism, the lock is.
                // One owner per frame, on purpose: while the carry drives the opacity these branches stay
                // shut, because two writers on gridRoot.alpha is the exact bug that made the pill pop
                // mid-morph. Expanding, the content fades across the whole morph (a short 0-35% ramp read
                // as "no animation at all" - the text was already opaque while the card was still tiny);
                // collapsing, it must be GONE by ~40-45% of the shrink, or expanded text hangs below the
                // pill in the last frames. Both rules were measured on hardware.
                // Both ramps go through the one owner: with the icon riding, the fade has to land on the content
                // views and not on the row, and this path is not allowed to disagree about that.
                // Blueprint v2's own 0-137 strike is that same one owner while V2 is active AND expanding;
                // on a COLLAPSE the fade below is alpha's only owner - round 37 gated by style instead of by
                // direction and left collapsed text frozen visible on the shrinking pill (his "ghost/masked
                // content"). Classic styles see no change (the added predicates are the v2 gate only).
                if (!morphOpacityFollowsShape && !(morphV2On && morphTowardCard)) {
                    if (target == IslandStage.STAGE3_FULL) {
                        setMorphContentAlpha(t)
                    } else if (target == IslandStage.STAGE2_PING) {
                        // Round 43-2 (his order, "try clip-only first"): the v2 collapse has NO dedicated alpha
                        // fade at all any more. The old 0-45% fade let content vanish in open space before the
                        // box shrank around it; now the dispatchDraw containment clip IS the disappearance -
                        // the box's own shrinking edges cut the content away ("content retreating into the
                        // shrinking pill", verbatim). Classic/carry styles keep the fade they always had.
                        if (!(morphV2On && !morphTowardCard)) {
                            setMorphContentAlpha(1f - (t / 0.45f).coerceIn(0f, 1f))
                        }
                        // The settle-in sink, deepened on his numbers and synced to the CONTAINER'S OWN curve
                        // (this t is exactly what sizes the box - not a separate faster timeline): 1.00 -> 0.92
                        // across the full 380 ms, monotone (the coerced collapse-spring output cannot bounce
                        // back), restoring at end while the pill takes the stage - provably invisible.
                        if (morphV2On && !morphTowardCard) {
                            val sink = 1f - MotionVariant.V2_COLLAPSE_CONTENT_SINK * t.coerceIn(0f, 1f)
                            gridContentSec?.scaleX = sink
                            gridContentSec?.scaleY = sink
                        }
                    } else if (target == IslandStage.STAGE1_IDLE) {
                        setMorphContentAlpha(1f - (t / 0.4f).coerceIn(0f, 1f))
                    }
                }
                // The destination rides from frame one (positioned, owning the box's edge) but is HELD BACK
                // until the arrival window: the un-faded rider IS the icon for the middle of the trip - that
                // is the b1378 feel he asked back by name ("pehle icon move hoke udhar jata tha") - and a
                // second copy drawn beside it, ~70 px away, is the "duplicate icon" he has rejected twice.
                // In the last 15 % the copy materialises where the rider lands and takes it over; the end
                // listener then owns its visibility outright, with no frame in between.
                if (target != IslandStage.STAGE3_FULL) pillPreviewRoot?.alpha = MorphCarry.partProgress(t, 0.85f, 1f)
                // Was: islandView?.scaleY = 1f - (0.04f * sin(t * Math.PI)) — a whole-card 4% vertical
                // squash on every morph. Scaling the card scales the TEXT, so it blurred and "breathed"
                // on both expand and collapse. The rounded-corner growth alone carries the motion now.
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    // Round 43-3: capture BEFORE endMorphPerf resets the v2 flags - the gulp fires exactly
                    // at the hard lock (this end is the lock), separate from the crisp collapse curve.
                    val v2CollapseLocked = morphV2On && target == IslandStage.STAGE2_PING
                    endMorphPerf("stage->$target")
                    if (v2CollapseLocked) runV2GulpPulse()
                    gridRoot?.translationY = 0f
                    syncContentWidth()
                    flushDeferredRegionUpdate()
                    if (target == IslandStage.STAGE3_FULL) {
                        // Where the deleted pill animator left it, minus the mid-morph pop.
                        pillPreviewRoot?.alpha = 0f
                        pillPreviewRoot?.visibility = View.GONE
                    }
                    if (target == IslandStage.STAGE1_IDLE) {
                        gridRoot?.visibility = View.GONE
                        // Keep what the morph arrived at. Hiding the pill here made a tap-collapse end as an
                        // empty black capsule whenever the notification queue had nothing left to repaint into
                        // it - his own export shows the state twice: `grid=V/0.0 pill=G/0.0`. The dominant,
                        // intended end-state in that same log is `pill=V/1.0`: the pill visible and owning its
                        // icon again, which is also what b1378's hand-off was built to mean.
                        pillPreviewRoot?.visibility = View.VISIBLE
                        pillPreviewRoot?.alpha = 1f
                        pillPreviewRoot?.bringToFront()
                        notificationMode = false
                    }
                    if (target == IslandStage.STAGE2_PING) {
                        gridRoot?.visibility = View.GONE
                        gridRoot?.alpha = 0f
                        pillPreviewRoot?.visibility = View.VISIBLE
                        pillPreviewRoot?.alpha = 1f
                        pillPreviewRoot?.bringToFront()
                    }
                    if (target == IslandStage.STAGE3_FULL) {
                        gridRoot?.alpha = 1f
                        gridRoot?.visibility = View.VISIBLE
                    }
                    updateOutsideWatcherForState()
                    isProcessingQueue = false
                }
            })
        }
        morphAnimator = anim
        beginMorphPerf(
            "stage->$target", anim.duration, curW, curH, targetW, targetH, targetR,
            towardCard = target == IslandStage.STAGE3_FULL,
        )
        anim.start()
    }

    /** Content column width: always the expanded width, so the morph never re-wraps the text. */
    private fun expandedContentWidthPx(): Int = dp(AppSettings.getIslandExpandedWidthDp(this))

    /**
     * Re-assert the locked content width if the expanded-width slider moved mid-session.
     * Width only, on purpose: the earlier version also pinned the height and re-assigned
     * layoutParams at onAnimationEnd, which forced one more layout pass right when the morph
     * landed — a visible snap at the end. It is a no-op when nothing changed, so it is free.
     */
    private fun syncContentWidth() {
        val host = gridRoot ?: return
        val lp = host.layoutParams as? FrameLayout.LayoutParams ?: return
        val want = expandedContentWidthPx()
        if (lp.width == want) return
        lp.width = want
        host.layoutParams = lp
    }

    private fun updateIslandLayout(w: Int, h: Int, r: Float) {
        val lp = islandLayoutParams
        if (lp != null && lp.width == w && lp.height == h && outlineRadius == r) {
            // Nothing changed, and "calling it anyway" is not free: a layoutParams write requests a layout
            // of the whole card. That is what the settle cost - the tester's own trace put every hitch at
            // t=1.0x, i.e. one frame after the animation, and grew it from 17 ms to 66-124 ms as the ring
            // filled from 0 to 8 chats. During a morph the size is already the final one (it was applied
            // once in beginMorphPerf), so the end of a morph takes this branch and does no layout at all.
            return
        }
        islandLayoutParams?.width = w; islandLayoutParams?.height = h; islandBackground?.cornerRadius = r
        outlineRadius = r; islandView?.layoutParams = islandLayoutParams; islandView?.invalidateOutline(); forceRegionUpdate()
    }

    /**
     * The per-frame entry point while a morph runs, and it is *not* the same as [updateIslandLayout]:
     *  - the eased curve lands on the same even-pixel size for its last frames, and every redundant
     *    `layoutParams` assignment is a fresh requestLayout traversal of a screen-sized overlay window.
     *    Those tail frames are where the tester saw the text finally "set" after the glitch, so when the
     *    size did not change only the radius moves.
     *  - `visualRoot.invalidateOutline()` is gone from the path above as well: the outline belongs to the
     *    card, and invalidating the root's was asking RenderThread to redo the whole overlay per frame.
     */
    /**
     * One row, riding the shape. The card's content starts at the box's left inner edge - which is where the
     * pill's own content sits - and slides into its final layout as the box opens, instead of standing still
     * at the final position while the island uncovers it. That stillness is what he named: "saara content draw
     * ho ja rha hai but invisible, and island me visible kar deta hai". The shift is the box's own left edge, so
     * it vanishes by itself when the box reaches full width; no reset to get wrong, no snap at the settle. And
     * because a translation is a render transform, the width lock that stopped the text jitter stays intact -
     * nothing here re-measures a line.
     *
     * The icon additionally changes size and, at the half-way point, swaps the pill's small glyph for the
     * launcher badge, so it reads as the same element travelling and transforming rather than two images
     * cross-fading in different corners.
     */
    /** The rider's vertical travel, in window coordinates, from the card's icon box to the pill's icon box. */
    private fun morphShapeProgress(): Float =
        MorphCarry.shapeProgress(lastMorphBoxLeft, morphFromW, morphToW, morphPinW())

    private fun applyMorphCarry(t: Float) {
        // The one place a morph's direction is decided; every rule below reads the result. Stashed for the
        // styles whose fade is written by the stage animator, so both owners read the same number.
        val open = MorphCarry.openProgress(morphShapeProgress(), morphTowardCard)
        morphOpenFrame = open
        // v2 enters here even with every classic flag off: it owns the sections, the flags are only opt-outs
        // for THEIR writers (and "nothing visible on its own style" is exactly how the rounds-34/35 pull died).
        if (!morphCarryOn && !morphScaleOn && !morphIconRide && !morphGlassOn && !morphV2On) return
        val g = gridRoot
        if (morphEntryDrop && !morphV2On) {
            // No sideways travel: the box widens evenly on both sides, so any x movement reads as the content
            // arriving from one side. The row hangs from the box's top edge - where the pill is - and settles
            // into its centred rest place as the box completes, so the entry is on the axis the shape grows on
            // and there is no snap at the end: the offset is a multiple of the leftover it has to cover.
            // gridRoot is WRAP_CONTENT in a full-height host, so `g.top` IS the centring leftover the layout
            // had to give it: 0 would glue the row to the box's top edge, g.top is where it rests. The host
            // wrote frame.contentOffsetY on this view a few lines earlier in the SAME frame and this replaces
            // it for the row - with the round-32 anchor they land on the same number at open = 1 (the host's
            // offset there is -headroom/2 too), so nothing snaps at the hand-off.
            val leftover = if (g != null) g.top.toFloat() else 0f
            // Half the spring's headroom comes out of every ride value below. The layout prepared the row for
            // a view that is natural + headroom tall, but the curve was authored against the natural card;
            // without this the whole ride floated half the headroom low, and the settle pulled the content
            // across that gap right where his eye was ("spring content pe jerky feel deta hai", b1422). With
            // it, the spring's extra surface is room the content never has to know about: every intermediate
            // value is bit-identical to the headroom-free curve of the classic styles, and the last frame
            // lands on exactly the offset the host writes for the other children. Zero for the classics, so
            // their numbers are untouched.
            val headroomHalf = morphOvershootPx / 2f
            if (g != null) {
                // Literal 0 now. The subtraction this used to share with the pill's ride existed to undo the
                // horizontal headroom's shift - and after the axis rule there IS no horizontal headroom, so on
                // a collapse (pinW - finalW)/2 came out as the whole travel: the fading row was dragged ~350 px
                // right while the box shrank around it. The content exits where it stands.
                g.translationX = 0f
                // The buoyancy: spring styles on the way IN ride the content on its own spring - a third
                // slower and underdamped ([MotionVariant.buoy]) - so it lands after the box, dips past its
                // seat and floats back: "jaise content liquid mein hai". The exact-endings guarantee is the
                // shared spring's (it is 1.0 at t = 1), so the hand-off still cannot snap; the classic
                // styles are untouched because isSpring gates it.
                val travel = if (morphBuoyOn) MotionVariant.buoy(t, morphDurationMs, morphResponseSec) else open
                g.translationY = if (morphHyperOn && !morphTowardCard && morphMagnet > 0f) {
                    // "Magnetic anchors", the one idea in his design that no launcher has: on the way home the
                    // content is not interpolated to the pill, it is PULLED - attraction rising as the anchors get
                    // closer, so the early frames hang and the last ones snap. Only the content's own offset
                    // takes this curve; the box keeps the spring, which is what makes them read as attached to
                    // different things on purpose rather than desynced by accident.
                    val travelled = MotionVariant.magnetic(1f - open, morphMagnet)
                    MorphCarry.contentEntryOffset(1f - travelled, leftover, morphContentDropPx) - headroomHalf
                } else MorphCarry.contentEntryOffset(travel, leftover, morphContentDropPx) - headroomHalf
            }
        } else {
            // The old entry: the host's per-frame centring is left alone, so nothing here owns translationY.
            if (morphCarryOn && g != null) g.translationX = MorphCarry.translationX(lastMorphBoxLeft)
        }
        // The pill copy is glued to the box's own left edge, whichever way the box is moving: on the way out
        // it is the source the card grows away from, on the way in it is the destination the icon rides into.
        // The subtraction this line carried ((pinW - finalW) / 2) belonged to the horizontal-headroom world;
        // after the axis rule that difference IS the travel, so the destination's icon began every collapse
        // about 350 px off-screen and only slid in for the last frames - the concrete half of "in between
        // animation icon gayab ho jata hai". Unconditional now: the destination riding into place is not a
        // personality of the carry styles, it is the hand-off's contract.
        pillPreviewRoot?.translationX = MorphCarry.translationX(lastMorphBoxLeft)
        var rowScale = 1f
        if (morphScaleOn && g != null) {
            // Scale about the anchor the box grows from: its top edge when the row hangs there, its middle when
            // the row is being centred. A pivot in the wrong corner is a slide in disguise.
            rowScale = MorphCarry.contentScale(open, morphScaleFrom)
            g.pivotX = 0f
            g.pivotY = if (morphEntryDrop) 0f else g.height / 2f
            g.scaleX = rowScale
            g.scaleY = rowScale
        }
        if (morphOpacityFollowsShape) {
            if (morphLiquidOn && morphTowardCard) {
                // Claude's whole point: the shape leads and the content follows. Only on the way OUT - the gate
                // is a rule about the box reaching its size before text appears in it, and running it backwards
                // on a collapse would snap the text off at 60 % instead of fading it, which is the jhatka this
                // whole file exists to avoid. The crossfade does not START
                // until the box is morphGate of the way to its final width, so text is never shown at a width
                // that clips it - and it reads the box's progress, not the animator's, so an overshoot cannot
                // start the fade early.
                setMorphContentAlpha(
                    MorphCarry.contentOpen(MotionVariant.gated(open, morphGate), morphTowardCard, morphOutBy)
                )
            } else if (morphGlassOn) {
                // The blur is the entrance here, so the fade must not eat it: readable (though out of focus)
                // from about a third of the travel onward, and sharpening all the way to the box's final size.
                setMorphContentAlpha(MorphCarry.partProgress(open, 0f, 0.6f))
                applyMorphBlur(open)
            } else {
                setMorphContentAlpha(MorphCarry.contentOpen(open, morphTowardCard, morphOutBy))
            }
        }
        // The ride is its own switch now, so it can sit on top of any style - he asked for exactly that
        // combination once he had felt "scale + fade" without it.
        if (!morphIconRide) return
        val icon = appIconView ?: return
        val pill = pillPreviewIcon
        // RECOVERED, mechanism for mechanism, from b1378 (`5d54874`) - the build whose icon ride is the first
        // visual change he ever praised ("wo icon morph jo tha kaafi mast hai, ekdum badhiya feel deta hai") and
        // the thing he asked back this round: "baki ke jo do the unka icon morph pichhle do-teen build se kharab
        // kar diya … jo ek maine praise kiya tha usko recover karo". Three of my own improvements cost him that,
        // and each one is a lesson worth keeping in words:
        //   * a vertical travel measured between the two icon slots. `riderY=-150px` in his log: 150 px of drift
        //     inside a 260 ms morph is literally "notice ho ja rha hai change", and b1378 never moved the glyph
        //     up or down at all - the two rows sit on one line.
        //   * standing the pill's own copy down for the ride's whole duration, so the badge appeared mid-flight
        //     instead of being one continuous object.
        //   * dividing the shift and the size by the row's scale, which re-derived a path the row was already
        //     drawing. The row IS the traveller; the icon is part of it, and that is what "one element" means.
        // The price, so nobody "fixes" it again: in the styles that scale the row, the icon's travel is scaled
        // with it (about 12 % short early on, exact where it lands). Riding is not being driven separately.
        val ratio = if (icon.width > 0 && pill != null && pill.width > 0) {
            (pill.width.toFloat() / icon.width.toFloat()).coerceIn(0.4f, 1f)
        } else {
            32f / 38f // dp(32) in the pill vs dp(38) in the card, if a view has not been laid out yet
        }
        val scale = MorphCarry.iconScale(if (morphCarryGrowing) t else 1f - t, ratio)
        icon.pivotX = icon.width / 2f
        icon.pivotY = icon.height / 2f
        icon.scaleX = scale
        icon.scaleY = scale
        // The box's own left edge, undivided: 0 at the card's rest size and the pill's edge at pill size, so the
        // travel ends by itself - nothing to reset, nothing to snap. When the row travels too (entry=centred) the
        // icon writes no offset at all, because the row is already carrying it.
        icon.translationX = if (morphEntryDrop) MorphCarry.translationX(lastMorphBoxLeft) else 0f
        icon.translationY = 0f
        val launcher = morphIconLauncher
        val glyph = morphIconPill
        if (launcher != null && glyph != null) {
            val want = if (MorphCarry.showsPillGlyph(t, morphCarryGrowing, morphSwapAt)) glyph else launcher
            if (icon.drawable !== want) icon.setImageDrawable(want)
        }
    }

    /**
     * "Precise Snap, Organic Breath" - the blueprint v2 he sent, written as one overlay per frame and as the
     * FINAL word on the two sections it owns (round 34-35's writers scattered across three functions, which is
     * half of why the style it served never rendered it): the text column rides the anchor pendulum
     * (-40 arrival, +26 deep sink locked against the container's own lock, the -5 / +2 bounce pair, rigid at
     * the hard lock), carries the pull-window stretch (his 1.15 / 0.95 over the first 247 ms) and the strike
     * opacity (0 -> 1 over the first 130 ms); the icon rides the same pendulum at exactly half buoyancy, 40 ms
     * later, NEVER stretched (his rigid-brand-asset rule); and exactly one haptic lands the instant the
     * container hard-locks (t = 0.337 of the 950 ms clock - exactly once per expand, re-armed at the next begin).
     */
    private fun applyBlueprintV2(t: Float) {
        if (!v2OverlayLogged) { v2OverlayLogged = true; TraceLog.morph("v2 overlay firing: pendulum path ACTIVE, first t=" + "%.3f".format(t)) }
        gridContentSec?.let { sec ->
            sec.translationY = MotionVariant.v2TextOffsetPx(t)
            sec.scaleY = MotionVariant.v2StretchScaleY(t)
            sec.scaleX = MotionVariant.v2StretchScaleX(t)
            sec.alpha = if (t < 0.137f) t / 0.137f else 1f
        }
        gridIconSec?.let { icon ->
            icon.translationY = MotionVariant.v2IconOffsetPx(t)
        }
        if (!v2HapticDone && MotionVariant.v2HapticAt(t)) {
            islandView?.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
            v2HapticDone = true
        }
    }

    /**
     * The only writer of the card content's opacity while a morph runs, for both styles - the shape-driven ramp
     * from [applyMorphCarry] and the clock-driven one from the stage animator both come through here, because
     * two owners of one alpha is the bug this file keeps meeting.
     *
     * With the icon riding, the fade goes on the content VIEWS and not on the row. That is the whole of the
     * regression he reported - "pehle ekdum seamlessly tha ... abhi notice ho ja rha hai change": the row's ramp
     * is over at goneBy (25-45% of the shape in his own log), so a fade on the row ended the ride a third of the
     * way in, and the pill's own icon appeared while the rider still had travel left. A shared element must not
     * be faded by the container it is leaving. The stagger lives here too, in the same place, because it writes
     * the very same views: each one gets the row's opacity times its own slice of the travel.
     */
    private fun setMorphContentAlpha(a: Float) {
        // One writer, one number, on the TEXT SECTION - never on the row. The row contains the shared
        // element (the icon, in iconSec), and a shared element must not be faded by the container it is
        // leaving: with the fade on the row, goneBy = 45 % erased the icon half-way into every collapse,
        // which is "in between animation icon gayab ho jata hai" (his report on b1424). The text exits
        // early - that part of the design was measured on hardware and stays - while the icon rides the
        // whole way down, swaps to the pill glyph, and lands where the pill takes it over. Not a new look:
        // the b1378 hand-off recovered one mechanism at a time, like the last time a refactor lost it.
        (gridContentSec ?: gridRoot ?: return).alpha = a
        if (morphStagger > 0f) applyMorphStagger(a)
    }

    /**
     * Glass settle, the fourth form - and the reason it is a LOOK and not a timing difference, which is what his
     * verdict on the last one was ("4th mei kuchh to alag hai he nahi, 3rd jaisa he to hai").
     *
     * Apple's glass material is defined by its lensing: iOS 26 exposes "Reduce Motion" partly because the glass
     * distorts as elements move, and the material is described as responding to what is under it and morphing
     * between states. Android has carried the same idea as a GPU-side node property since API 12
     * (`RenderEffect.createBlurEffect`), which is applied on the render thread rather than by us on the main
     * one. So the content arrives legible-but-out-of-focus and sharpens exactly as the box completes; on the way
     * out it blurs back out instead of only dimming. The icon is never blurred - it rides through in focus,
     * because it is the element that carries the app's identity.
     *
     * Two rules keep it cheap and keep it honest: the radius is quantised to half-pixel steps, so one effect is
     * built per step and not per frame (a frame in this morph is 8 ms at 120 Hz); and anything under half a
     * pixel clears the effect entirely, so a settled card has no render effect attached to it at all - the last
     * frames of a morph cannot leave a soft edge behind, and the next redraw is the plain one.
     */
    private fun applyMorphBlur(open: Float) {
        val c = MorphCarry.contentOpen(open, morphTowardCard, morphOutBy)
        setGlassBlur(MorphCarry.blurRadiusPx(c, morphBlurPx))
    }

    /**
     * One place where a radius becomes a RenderEffect, shared by the glass settle and ChatGPT's ripple. The
     * quantisation is the whole reason this is affordable: `setRenderEffect` is cheap per call, so rounding to
     * half a pixel turns sixty frames of a blur into a handful of them, and a radius under half a pixel is
     * cleared rather than animated, so no faint blur can linger.
     */
    private fun setGlassBlur(raw: Float) {
        val q = if (raw < 0.6f) 0f else (Math.round(raw * 2f) / 2f)
        if (q == lastBlurPx) return
        lastBlurPx = q
        val effect = if (q > 0f) RenderEffect.createBlurEffect(q, q, Shader.TileMode.CLAMP) else null
        headerLine?.setRenderEffect(effect)
        titleText?.setRenderEffect(effect)
        messageText?.setRenderEffect(effect)
        actionScroll?.setRenderEffect(effect)
    }

    /** Header, title, message, actions - each with its own slice of the shape's travel, reading order first. */
    private fun applyMorphStagger(base: Float) {
        val kids = arrayOf<android.view.View?>(headerLine, titleText, messageText, actionScroll)
        val n = kids.size
        if (n == 0) return
        val on = morphStagger > 0f
        for (i in 0 until n) {
            val v = kids[i] ?: continue
            if (!on) { v.alpha = 1f; continue }
            // Arriving, the reading order leads. Leaving, it folds back the way it came: the actions go first.
            val idx = if (morphTowardCard) i else n - 1 - i
            v.alpha = base * MorphCarry.childOpen(idx, n, morphOpenFrame, morphStagger)
        }
    }

    /**
     * Apple's `contentTransition(.numericText())` on the one number that changes while the island is open: the
     * digits travel in the direction the value moved instead of blinking into place. Two properties on a view
     * that was redrawing for the new text anyway, so this costs no measure pass and no layout.
     */
    private fun rollBadge(from: Int, to: Int) {
        if (!AppSettings.getMorphCountRoll(this)) return
        val v = pillPreviewCount ?: return
        val dir = com.hyperisland.pro.core.PillBadge.rollDirection(from, to)
        if (dir == 0f) return
        v.animate()?.cancel()
        v.translationY = dir * v.height * 0.55f
        v.alpha = 0f
        v.animate().translationY(0f).alpha(1f).setDuration(190L).setInterpolator(morphInterpolator).start()
    }

    /** Every carry value has an identity state; the settle and any cancellation both land here. */
    private fun clearMorphCarry() {
        morphCarryOn = false
        morphScaleOn = false
        morphGlassOn = false
        // The two designs' geometry is a per-morph property, not a setting that leaks into the next draw: an
        // eased offset or a ripple blur still applied outside a morph is a card that has stopped obeying the
        // stage it is in.
        morphVariantOn = false
        morphLiquidOn = false
        morphHyperOn = false
        morphRipple = 0f
        setGlassBlur(0f)
        // A blur that survives the morph is a card nobody can read, and it would stay until the next text
        // change. -1 forces the next morph to write its first step whatever this one ended on.
        lastBlurPx = -1f
        headerLine?.setRenderEffect(null)
        titleText?.setRenderEffect(null)
        messageText?.setRenderEffect(null)
        actionScroll?.setRenderEffect(null)
        gridRoot?.scaleX = 1f
        gridRoot?.scaleY = 1f
        gridRoot?.translationX = 0f
        pillPreviewRoot?.translationX = 0f
        gridRoot?.translationY = 0f // the host resets its own centring right after; this is the drop term
        morphOpenFrame = 0f
        // The row's alpha is the settle path's business; these four are ours and must never stay faded, or the
        // next card is drawn with a half-transparent message and no explanation in the log.
        gridRoot?.let { g -> for (i in 0 until g.childCount) g.getChildAt(i).alpha = 1f }
        headerLine?.alpha = 1f
        titleText?.alpha = 1f
        messageText?.alpha = 1f
        actionScroll?.alpha = 1f
        // The glass form leaves no offsets, but the ride does: an icon still holding the box's left edge draws
        // the next card's badge outside the row, which is the same class of bug as a stale alpha.
        titleText?.translationX = 0f
        messageText?.translationY = 0f
        actionScroll?.translationY = 0f
        appIconView?.translationX = 0f
        appIconView?.translationY = 0f
        gridContentSec?.alpha = 1f // the fade's home since round 33: restored here like every other alpha
        gridContentSec?.scaleX = 1f
        gridContentSec?.scaleY = 1f
        gridContentSec?.setLayerType(View.LAYER_TYPE_NONE, null)
        gridContentSec?.translationY = 0f
        gridIconSec?.translationY = 0f
        islandView?.translationY = 0f
        pillPreviewIcon?.alpha = 1f
        appIconView?.scaleX = 1f
        appIconView?.scaleY = 1f
        pillPreviewIcon?.alpha = 1f // the hand-off always ends with the pill owning its own icon again
        morphIconLauncher?.let { d -> if (appIconView?.drawable !== d) appIconView?.setImageDrawable(d) }
        morphIconLauncher = null
        morphIconPill = null
        lastMorphBoxLeft = 0
        // Hidden at begin for the travel; revealed by the hand-off through the one policy writer, so a
        // collapse to the badge pill ends with the counter and a collapse away from it ends without one -
        // decided by the ring's contents, not by whoever happened to show or hide the pill last.
        if (currentStage != IslandStage.STAGE3_FULL) pillChatCount = pillChatCount
    }

    /** The pinned bound, falling back to the target for any frame that arrives outside a morph. */
    private fun morphPinW(): Int = morphPinW ?: morphFinalW


    /**
     * The one funnel every morph animator writes its size through, and it is given the animator's own clock [t]
     * as well as the geometry. Both are needed and they are not interchangeable: the content gate is a rule
     * about the *box* (Claude's "~60 % of the target width"), while the compression, the ripple and the settle are
     * rules about *time* (his "30-45 ms", his "40-70 ms"). Measuring the time-boxed ones on the box's progress is
     * what made them vanish in b1411 - a spring's progress is 0.11 after one frame and 0.32 after two, so every
     * window keyed to it is sampled once, past its peak.
     */
    private fun updateIslandLayoutForMorph(w: Int, h: Int, r: Float, t: Float, rawT: Float = t) {
        // TWO clocks, on purpose and at last named as two parameters: `t` is the shape (the interpolator's
        // output - what the DRAWN size follows, including the spring's own pace), `rawT` is the TIME (the
        // animator's uncurved fraction). Window-authored effects (the pendulum, the stretch, the haptic;
        // anything whose table says "0-130 ms") consume rawT, because on b1437 `t` was the spring's output
        // and the entire 0-0.9 pendulum executed inside the spring's first 113 ms of a 950 ms morph - 7
        // frames, which is why "plain resize, normal content" was the exact history it drew. The haptic
        // window (0.335-0.345) sat between frames 2 and 3 in spring-time and the tick mostly never fired.
        // Rule (triage #32): the interpolator's output is a shape, not a clock; anything timed reads rawT.
        // Round 43: the squeeze voice moved from WIDTH-uniform (round 42, retired - a uniform factor cannot
        // express "zero at the icon edge") to the NECK silhouette. Same time window by design: active
        // 40-300 ms on the raw clock, sin envelope peaking at the centre = 170 ms, 5% of the CURRENT drawn
        // width ("9% was too strong" - his number). Collapse gets 0, classic styles get 0.
        val v2SqueezeNow = 0f   // retired runtime path; the constant stays pinned in MotionVariant
        val v2NeckNow = if (morphV2On && morphTowardCard) {
            val ms = rawT * morphDurationMs
            if (ms >= MotionVariant.V2_NECK_START_MS && ms <= MotionVariant.V2_NECK_END_MS) {
                val span = (MotionVariant.V2_NECK_END_MS - MotionVariant.V2_NECK_START_MS).toFloat()
                MotionVariant.V2_NECK_MAX_INSET * w *
                    kotlin.math.sin(Math.PI * (ms - MotionVariant.V2_NECK_START_MS) / span).toFloat()
            } else 0f
        } else 0f
        if (!v2GateLogged && morphPinH != null) {
            v2GateLogged = true
            TraceLog.morph("frame1 gate evidence: v2On=" + morphV2On + " toward=" + morphTowardCard + " pinH=true style=" + AppSettings.getMorphStyleName(morphVariant) + " t=" + "%.3f".format(t) + " rawT=" + "%.3f".format(rawT))
        }
        if (morphV2On) {
            // The auditor's demand: per-frame proof, not endpoint faith. Count every overlay-eligible frame,
            // sample the section at 20/40/60/80/100% of the morph's real time, print the whole line at end.
            v2TraceFrames++
            while (v2TraceNextIdx < v2TraceMarks.size && rawT >= v2TraceMarks[v2TraceNextIdx]) {
                v2TraceSamples += "|@" + "%.2f".format(v2TraceMarks[v2TraceNextIdx]) + ": ty=" +
                    "%.1f".format(gridContentSec?.translationY ?: -999f) + " sy=" + "%.3f".format(gridContentSec?.scaleY ?: -1f) +
                    " sx=" + "%.3f".format(gridContentSec?.scaleX ?: -1f) + " a=" + "%.2f".format(gridContentSec?.alpha ?: -1f) +
                    " box=" + (((if (morphV2On) (if (morphTowardCard) w.coerceAtMost(morphToW) else w.coerceAtLeast(morphToW)) else w) * (1f - v2SqueezeNow)).toInt()) +
                    "x" + h + " neck=" + "%.1f".format(v2NeckNow) +    // DRAWN numbers (post clamp; neck is a silhouette, not a width): render view only
                    "@" + "%.0fms".format(rawT * morphDurationMs)
                v2TraceNextIdx++
            }
        }
        // Blueprint v2 FIRST THING: THE CLOCK IS THE ANIMATOR'S, not the carry's. applyMorphCarry receives
        // a legacy-scaled progress on the auto path (0.5 + 0.5 * t, kept for glyph hand-off semantics)
        // which on b1434 fed the pendulum a half-eaten timeline: the stretch window skipped, the haptic
        // window itself never reached (auto haptic dead), and 'kuchh dikh nahi raha' held on half the
        // triggers. This is the one function every expand funnel hits with its OWN animator's raw t, so
        // the overlay's clock is the same number for auto and manual morphs again: the fluid expand
        // listener and the generic path both hand this function their animator's untransformed t
        // (see the two updateIslandLayoutForMorph(...) calls inside the animators' update listeners).
        if (morphV2On && morphTowardCard && morphPinH != null) applyBlueprintV2(rawT)
        var bw = w
        // The container's own axis rule for v2: the spring may only overshoot DOWNWARD. `0.78` spends its
        // whole overshoot on the lerp's shape, and if the width were left free it would draw +14 px
        // (1.99% of 701 px of width-travel) past the final card - off-material by his own spec's anchor.
        // Height gets the full bounce (+6.3 px at ~176 ms, visibly "2-3 dp-ish", hard-caught by 320 ms);
        // the width is pinned to never pass its destination, whichever direction the shape is moving.
        if (morphV2On) bw = (if (morphTowardCard) bw.coerceAtMost(morphToW) else bw.coerceAtLeast(morphToW))
        // Round 42: and then the width speaks with the height's voice - squeeze pulls it inward while the
        // height arcs, always inward (factor <= 1), so the never-overshoot rule is safe by construction.
        bw = (bw * (1f - v2SqueezeNow)).toInt()
        var bh = h
        var br = r
        if (morphVariantOn) {
            // Both designs redraw the box each frame and both do it in phases; this is the only place the drawn
            // size is decided, so the phases go here rather than into the six animators that call it. The two
            // clocks are kept apart on purpose, because they are two different questions: where the *shape* is
            // (p, read off the box - what the content gate is keyed to, since it was specified as a share of the
            // target width) and how much *time* has gone (tc, the animator's own t - what the compression, the
            // ripple and the settle are keyed to, since each was specified in milliseconds). Keying the timed ones
            // to the shape is the bug that made them invisible: a spring is already at 0.11 after one frame.
            val p = MotionVariant.progressOf(morphFromW, morphFromH, morphToW, morphToH, w, h).coerceIn(0f, 1f)
            morphProgress = p
            // The clock, clamped the same way: it is the animator's own 0..1, so a spring that overshoots does
            // not run these windows past their end.
            val tc = t.coerceIn(0f, 1f)
            if (morphHyperOn) {
                // Phase A, only on the way OUT: during a collapse the box is already at the pin, so a squeeze
                // there clamps to nothing and reads as a hitch instead of a compression.
                if (morphCarryGrowing && morphSqueeze > 0f) {
                    val c = MotionVariant.compression(tc, MotionVariant.COMPRESSION_WINDOW, morphSqueeze)
                    bw = (w * (1f - c)).toInt()
                    bh = (h * (1f + 2f * c)).toInt()
                }
                // "Energy ripple": a 40-70 ms wave spent as a breath of size on the box and a pass of blur over
                // the content. Blur is what makes it felt and not seen, and the blur helper is already quantised
                // for the glass style, so the ripple costs no new render work beyond a radius write.
                // Expand only. Running it on the return too (which is what b1406 did) puts a fog-and-unfog flash
                // on the first five frames of every collapse, which neither design asked for.
                val rip = if (morphCarryGrowing) MotionVariant.ripple(tc, MotionVariant.RIPPLE_WINDOW) else 0f
                morphRipple = rip
                if (rip > 0.01f) {
                    bh = (bh * (1f + 0.012f * rip)).toInt()   // height only: [MotionVariant.SPRING_AXIS]
                }
                // Phase D, "micro-settle" on the container (100 -> 102 -> 100): the content is deliberately not
                // scaled in this style - Phase C forbids a fade-replace - so the box is what settles. Width
                // only, like the overshoot: the height leftover of the card is what centres the row.
                if (morphCarryGrowing) {
                    val settle = MotionVariant.microSettle(
                        tc, MotionVariant.SETTLE_WINDOW, maxOf(0.015f, morphSqueeze * 0.5f)
                    )
                    if (settle > 0.0005f) bh = (bh * (1f + settle)).toInt()   // and so does his 100 -> 102 -> 100
                }
                setGlassBlur(rip * morphBlurPx * MotionVariant.RIPPLE_BLUR_FRACTION)
            }
            // The axis rule. Width may not overshoot, in either direction: on the way out it stops at the card's
            // own width, on the way back it stops at the pill's. Everything elastic - the spring's settle, the
            // ripple's breath, the tap at the end - is spent on the height instead, because that is the direction
            // this surface has room in (see [MotionVariant.SPRING_AXIS]). Horizontal bounce is not a smaller
            // motion here, it is a rectangle drawn off-screen and a content row sliding sideways, which is what
            // b1415 shipped and what he reported: "left right spring effect kon dalta hai island mei?"
            bw = MotionVariant.axisWidth(bw, morphToW, morphCarryGrowing)
            // Both: "cornerRadius = height / 2, radius ko independently animate mat karo". One rule instead of
            // a second animator, and it is what keeps a growing capsule a capsule; the final value is whatever
            // the style asked for, so a card with 22 dp corners still lands on 22 dp.
            br = MotionVariant.tensionRadius(bh.toFloat(), r)
        }
        islandBackground?.cornerRadius = br
        outlineRadius = br
        val host = islandMorph
        if (host != null) {
            // The card is not resized at all during a morph: this frame is one small invalidate inside the
            // view, which is why the overlay window stops being laid out 60 times a second.
            val frame = IslandMorphFrame.compute(morphPinW(), morphNaturalH, bw, bh, morphOvershootPx, v2NeckNow)
            lastMorphBoxLeft = frame.left
            host.applyMorphFrame(frame, br)
            return
        }
        if (bw == morphLayoutW && bh == morphLayoutH) return
        morphLayoutW = bw; morphLayoutH = bh
        updateIslandLayout(bw, bh, br)
    }


    /**
     * What the morph costs per frame, made explicit. Two things are pinned for the animation and restored
     * when it lands, both aimed at "text glitch hota hai, tab jake text set hota hai":
     *  - the content column and the pill content are MATCH_PARENT in a box whose height is animating, so
     *    they are re-centred on *every* frame: that is the text sliding while it fades in, and it throws
     *    away the cached text raster each time. Pinned to their own height with a vertical centering
     *    gravity they sit on the same pixel they would have landed on anyway - the end state is identical,
     *    the frames in between stop moving.
     *  - the text column gets a hardware layer, so the cross-fade is a GPU blend of one cached raster
     *    instead of 60 re-renders a second of every TextView, span and emoji in it.
     */
    private fun setContentPinnedForMorph(pinned: Boolean) {
        val want = if (pinned) ViewGroup.LayoutParams.WRAP_CONTENT else ViewGroup.LayoutParams.MATCH_PARENT
        // Both live directly in islandView, so their parameters are FrameLayout's - the type that carries
        // gravity. The static type of getLayoutParams() is ViewGroup.LayoutParams, which does not, and
        // View has no LayoutParams of its own at all (CI: "Unresolved reference 'LayoutParams'").
        gridRoot?.let { host ->
            val lp = host.layoutParams as? FrameLayout.LayoutParams ?: return@let
            // The width joins the height for one reason: the surface can now be wider than the card (the spring's
            // headroom), and a MATCH_PARENT row measured against that surface gets re-measured at the *real*
            // width the moment the morph lands - a text reflow in the last frame, which is b1343's "text finally
            // sets with a snap" walking back in through a door I had just opened. Pin the row to the final card
            // width and the two numbers are the same number at the hand-off.
            // Note the `maxOf`: on a collapse the row is *supposed* to be measured at the card's width and
            // re-measured at the pill's when the morph lands - that reflow is old, accepted, and hidden by the
            // fade. The only re-measuring that has to be prevented is the one my own headroom introduces, so the
            // row is pinned to the natural width of the surface (start and target, whichever is larger), and for
            // the four classic styles that number is exactly the view's width: nothing changes for them.
            // Height only, as it was before the spring styles: the width pin existed solely because the view was
            // widened for horizontal headroom. With the surface no broader than the widest state, MATCH_PARENT
            // *is* the expanded content width - so the row still cannot be re-measured mid-morph (b1343's snap
            // stays fixed) and there is one correction less standing between the two new styles and the four.
            if (lp.height != want) {
                lp.height = want
                host.layoutParams = lp
            }
        }
        pillPreviewRoot?.let { host ->
            val lp = host.layoutParams as? FrameLayout.LayoutParams ?: return@let
            if (lp.height != want) {
                lp.height = want
                // The pill used to be stretched to the box and centered inside it; once it is its own height
                // it has to be centered *in* the box or it jumps to the top edge.
                lp.gravity = if (pinned) Gravity.CENTER_VERTICAL else Gravity.NO_GRAVITY
                host.layoutParams = lp
            }
        }
    }

    /**
     * Opens or extends a frame-watch window. Cheap: while it is open we spend one lambda per vsync, and it
     * is open only while the island is doing something (a touch, a morph, a badge change), so an idle pill
     * costs nothing even on a 120 Hz panel.
     */
    /**
     * The log used to see only what I thought to measure - and that is precisely how the last three rounds of
     * this went wrong. A per-animation counter can honestly report "no frames were dropped" while the tester
     * watches content jump, because what hurts is a 448 ms block on the same thread from somewhere else: a
     * binder reply, a blocking GC, a relayout of the full-screen root. No callback-gap meter can see that,
     * since it only ever sees the frames it is handed.
     *
     * So while the service lives, a second thread watches the main thread as a whole. Every
     * [STALL_POLL_MS] it posts a tick to the main looper and measures how long that tick waits. If it waits longer than [STALL_MS], the
     * main thread is inside something - and we ask it what, by reading its stack. That is attribution without
     * me predicting the culprit, and it is all public API: `Looper.setMessageLogging`, which would have been
     * the tidier way, is not in the SDK (CI proved it: unresolved reference), and RenderThread/GPU timings
     * need `View.addFrameMetricsListener`, which is @hide. adb/perfetto is the only way past this line.
     */
    private fun startStallWatch() {
        if (stallRunning) return
        stallRunning = true
        stallSeenNs.set(System.nanoTime())
        val main = Handler(Looper.getMainLooper())
        val victim = Looper.getMainLooper().thread
        stallThread = Thread {
            // State lives in the loop: a stall is only worth one line, and the line is worth writing when
            // the block ends, because that is when we know how long it actually was.
            var inStall = false
            var peakMs = 0L
            var frames: List<StackTraceElement> = emptyList()
            var state = "?"
            while (stallRunning) {
                main.post { stallSeenNs.set(System.nanoTime()) }
                try {
                    Thread.sleep(STALL_POLL_MS)
                } catch (_: InterruptedException) {
                    break
                }
                // How long the main thread has gone without completing one of our ticks. If it is inside
                // anything - our relayout, a binder reply, a GC, the OEM's input pipeline - the tick waits.
                val stuckMs = (System.nanoTime() - stallSeenNs.get()) / 1_000_000L
                if (!inStall) {
                    if (stuckMs < STALL_MS) continue
                    inStall = true
                    peakMs = stuckMs
                    state = victim.state.name
                    frames = try { victim.stackTrace.toList() } catch (_: Exception) { emptyList() }
                } else if (stuckMs >= STALL_MS) {
                    peakMs = stuckMs
                } else {
                    inStall = false
                    // A block this long is not a blocked main thread, it is the process being frozen
                    // (screen off, doze, OEM battery policy). Nothing here explains that, and pretending
                    // otherwise is how a build gets "fixed" for a symptom that never happened.
                    val logged = peakMs
                    if (logged < STALL_FREEZE_MS) {
                        if (stallShouldPrint(logged, frames)) {
                            TraceLog.line("STALL", stallLine(logged, state, frames))
                        // Counted on the main thread: FrameWatch belongs to it, and a window is only as
                        // trustworthy as its counters being written by one hand. `logged` is a copy on
                        // purpose - peakMs is reset below and a closure would read it after that.
                            mainHandler.post { frameWatch.noteStall(logged) }
                        } else {
                            mainHandler.post { frameWatch.notePollerStall(logged) }
                        }
                    }
                    peakMs = 0L
                    frames = emptyList()
                }
            }
            stallRunning = false
        }.also { it.isDaemon = true; it.start() }
    }

    private fun stopStallWatch() {
        stallRunning = false
        stallThread?.interrupt()
        stallThread = null
    }

    /**
     * The other half of the judder story: the panel's rate is not ours to keep. The tester's log carried
     * `hz=120`, `90`, `72` and `60` on consecutive morph lines with nothing else changing, i.e. the display
     * was rescaling under the animation - and a mode switch drops a frame or two no matter how cheap our
     * draw is. Watching the display turns that from an inference into a logged event.
     */
    private fun startDisplayWatch() {
        val dm = getSystemService(DisplayManager::class.java) ?: return
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) return
                val rate = try { dm.getDisplay(displayId)?.mode?.refreshRate ?: 0f } catch (_: Exception) { 0f }
                val snap = if (rate > 0f) FrameWatch.snapHertz((1000f / rate).toLong()) else 0
                if (snap == lastSeenHertz) return
                val was = lastSeenHertz
                lastSeenHertz = snap
                TraceLog.display("panel rate $was -> $snap hz (${rate}Hz reported by the display)")
            }
        }
        displayWatcher = listener
        try { dm.registerDisplayListener(listener, mainHandler) } catch (_: Exception) { }
    }

    private fun stopDisplayWatch() {
        val l = displayWatcher ?: return
        displayWatcher = null
        try { getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(l) } catch (_: Exception) { }
    }

    /** The runtime's GC counters. Public API, and the one stall source we can name without a stack sample. */
    private fun gcSnapshot(): GcSnapshot = GcSnapshot(
        gcStat("art.gc.gc-count"),
        gcStat("art.gc.gc-time"),
        gcStat("art.gc.blocking-gc-count"),
        gcStat("art.gc.blocking-gc-time"),
        gcStat("art.gc.bytes-allocated"),
    )

    private fun gcStat(name: String): Long = try {
        android.os.Debug.getRuntimeStat(name)?.toLongOrNull() ?: 0L
    } catch (_: Exception) {
        0L
    }

    private fun visName(v: View?): String = when (v?.visibility) {
        View.VISIBLE -> "V"
        View.INVISIBLE -> "I"
        View.GONE -> "G"
        else -> "-"
    }

    private fun armFrameWatch() {
        if (!frameWatch.noteActivity(nowMs())) return
        val cb = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                val now = nowMs()
                frameWatch.frame(System.nanoTime(), now)
                if (frameWatch.expired(now)) closeFrameWatch(now) else Choreographer.getInstance().postFrameCallback(this)
            }
        }
        frameWatcher = cb
        Choreographer.getInstance().postFrameCallback(cb)
    }

    private fun closeFrameWatch(now: Long) {
        frameWatcher?.let { Choreographer.getInstance().removeFrameCallback(it) }
        frameWatcher = null
        if (!frameWatch.running) return
        TraceLog.frame(frameWatch.end(now))
    }

    private fun detachFrameWatchers() {
        closeFrameWatch(nowMs())
        val w = frameLayoutWatcher
        frameLayoutWatcher = null
        if (w != null) islandView?.viewTreeObserver?.let { if (it.isAlive) it.removeOnGlobalLayoutListener(w) }
    }

    /**
     * What this panel can run at, and what we ask it for. `preferredRefreshRate` is the public vote a window
     * makes; without it an overlay is free to be held at 60 Hz by the platform's policy and no amount of
     * in-app tuning changes that. It has to be one of the panel's own rates before API 34, so we hand over
     * the maximum. The trace line is what makes `hz=` readable as a fact instead of a hope.
     */
    private fun refreshRateVote(): Float = try {
        val display = getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        val rates = display?.supportedModes?.map { it.refreshRate }?.distinct()?.sorted() ?: emptyList()
        val current = display?.mode?.refreshRate ?: 0f
        TraceLog.display("modes=${rates.joinToString("/")} current=${current} vote=${rates.lastOrNull() ?: 0f}")
        rates.lastOrNull() ?: 0f
    } catch (t: Throwable) {
        TraceLog.display("panel rates unreadable: $t")
        0f
    }

    /**
     * The length a morph animator gets. For the four original styles this is the caller's number untouched - the
     * house curve has no window to respect, and nothing he has already judged may be resized by a new design.
     * For the two spring styles it is that number with a floor, because a spring whose window is shorter than its
     * own settle is cut off at the door and reads as an ease (round 27's "dono new options ek he hai").
     */
    private fun morphWindowFor(callerMs: Long): Long =
        if (MotionVariant.isSpring(AppSettings.getMorphStyle(this))) {
            callerMs.coerceAtLeast(MotionVariant.MIN_MORPH_WINDOW_MS)
        } else {
            callerMs
        }

    private fun beginMorphPerf(
        label: String, durationMs: Long, fromW: Int, fromH: Int, toW: Int, toH: Int, toR: Float,
        towardCard: Boolean,
    ) {
        morphLayoutW = -1; morphLayoutH = -1
        morphFinalW = toW; morphFinalH = toH; morphFinalR = toR
        armFrameWatch()
        // The budget is the period this panel is actually running at, not a hardcoded 16: at 120 Hz a 16 ms
        // gap is two dropped frames and the old constant would have called that clean.
        morphMeter = MorphJankMeter(frameWatch.periodMs())
        setContentPinnedForMorph(true)
        gridRoot?.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        // --- which of the six looks this morph is, and the numbers the two outside designs brought with it.
        morphVariant = AppSettings.getMorphStyle(this)
        // Blueprint v2 rides under style #6 ("precise snap, organic breath"), gated early because the headroom
        // and profile machinery below read it. A confession worth keeping where the code changed: rounds 34-35's
        // pull for this style was dead code for its own style (morphVariantOn gated every writer out) - his
        // "kuchh effect nahi hai" on b1430 was reading the truth twice over.
        morphV2On = morphVariant == AppSettings.MORPH_STYLE_BLUEPRINT
        v2HapticDone = false
        v2GateLogged = false
        v2OverlayLogged = false
        v2TraceFrames = 0
        v2TraceNextIdx = 0
        v2TraceSamples = ""
        // Round 42: the inherited `squeeze` voice (field service:550 = legacy carry-growing consumer at :3838,
        // gated behind morphCarryGrowing, never armed for v2) is now armed by the blueprint itself - expand
        // only. Collapse stays 0f: its content voice is the new monotone settle-in sink further below.
        morphSqueeze = if (morphV2On && towardCard) MotionVariant.V2_CONTAINER_SQUEEZE else 0f
        if (!towardCard) TraceLog.morph("collapse begin evidence: contentSec alpha=" + "%.2f".format(gridContentSec?.alpha ?: -1f) + " tY=" + "%.1f".format(gridContentSec?.translationY ?: -999f) + " scaleY=" + "%.3f".format(gridContentSec?.scaleY ?: -1f) + " layerType=" + (gridContentSec?.layerType ?: -9))
        morphLiquidOn = morphVariant == AppSettings.MORPH_STYLE_LIQUID
        morphHyperOn = morphVariant == AppSettings.MORPH_STYLE_HYPERMORPH
        morphVariantOn = morphLiquidOn || morphHyperOn
        // The window the spring is sized against. It must be the animator's own duration - and that pairing is a
        // rule, not a coincidence: this path used to be told 720 ms (the whole sequential set, ping included) while
        // the animator that owns the shape ran 360, so the spring reached ~0.86 of its travel and the last frame
        // snapped. Every call site below now takes its number from the animator it belongs to.
        morphDurationMs = durationMs
        val motionProfile = AppSettings.getMotionProfile(this)
        // Scaled against THIS morph's window: at the 380 ms default the spring should land its last pixel on its
        // last frame, and at a 650 ms slider position it should still be a spring, not a 200 ms snap followed by
        // 450 ms of nothing.
        morphResponseSec = MotionVariant.responseFor(morphVariant, motionProfile, towardCard, durationMs)
        morphDamping = MotionVariant.dampingFor(morphVariant, motionProfile, towardCard)
        morphGate = AppSettings.getMotionGatePct(this) / 100f
        morphMagnet = AppSettings.getMotionMagnetPct(this) / 100f
        morphSqueeze = if (morphHyperOn) AppSettings.getMotionSqueezePct(this) / 100f else 0f
        morphRipple = 0f
        morphProgress = 0f
        val startR = islandBackground?.cornerRadius ?: toR
        // One layout for the whole morph, and the growth or shrink is drawn from here on. Starting the box
        // at the *old* size in the same call is what keeps this from flashing a full-size black card for a frame.
        // One layout for the whole morph, at the size the drawn box has to be able to reach - which on a
        // shrink is the card we are leaving, not the pill we are heading to. Pinning to the target instead is
        // what made every collapse frame clamp to the final size, so the shape never moved at all.
        morphPinW = maxOf(fromW, toW); morphPinH = maxOf(fromH, toH)
        // Measured on the axis the motion uses now: the vertical travel this curve is expected to add, printed
        // in the trace so the claim is checkable. It no longer sizes the view - a card pinned to the top edge
        // has the whole screen below it, which is the entire reason the axis changed - so the pin widening and
        // the anchor correction it forced are gone with it.
        morphOvershootPx = if (MotionVariant.isSpring(morphVariant)) {
            maxOf(
                maxOf(fromH, toH) * MotionVariant.peakOvershoot(morphDamping),
                maxOf(fromH, toH) * MotionVariant.DEFAULT_SETTLE,
            ).toInt()
        } else 0
        // The view gets the headroom the curve will use, because `IslandMorphFrame.compute` clamps the drawn box
        // to the view - a card pinned at 421 px can never be drawn 461 px tall, and the bounce would be eaten
        // exactly the way the horizontal one was. The box's own centring still uses `morphNaturalH` (the card's
        // real height), so the extra surface is only ever room to draw in, never a layout the content has to
        // match, and the last frame lands with a zero offset instead of a snap.
        morphNaturalH = maxOf(fromH, toH)
        // The number worth checking with his eyes is not the sizing bound above but the actual change in
        // thickness: the curve overshoots the *travel*, so on a 104 -> 421 card the peak is 317 x 9.5 % and not
        // 421 x 9.5 %. Printing the bigger figure would be another claim he could not verify.
        morphTravelV = maxOf(
            (kotlin.math.abs(toH - fromH) * MotionVariant.peakOvershoot(morphDamping)).toInt(),
            // Only the second design taps on purpose; the first one's spring is the whole story.
            if (morphHyperOn) (maxOf(fromH, toH) * MotionVariant.DEFAULT_SETTLE).toInt() else 0,
        )
        // The v2 container's own 2-3 px overshoot is already inside this budget (same formula, its damping).
        if (morphOvershootPx > 0) morphPinH = morphPinH!! + morphOvershootPx
        updateIslandLayout(morphPinW!!, morphPinH!!, startR)
        val startFrame = IslandMorphFrame.compute(morphPinW!!, morphNaturalH, fromW, fromH, morphOvershootPx)
        lastMorphBoxLeft = startFrame.left
        islandMorph?.applyMorphFrame(startFrame, startR)
        morphTowardCard = towardCard
        morphGcStart = gcSnapshot()
        // The carry only makes sense while the box is what moves: on the fallback path the view itself is
        // resized per frame, so the row already sits against its left edge.
        val style = morphVariant
        // The liquid capsule keeps the row's travel, because "the shape leads and the content follows" is a
        // statement about where the content comes FROM. Gated alpha alone would mean text fading in while
        // standing still - a timing difference, and he has told me twice that a timing difference is not a look.
        morphCarryOn = islandMorph != null &&
            (style == AppSettings.MORPH_STYLE_BALANCED || style == AppSettings.MORPH_STYLE_CARRY ||
                style == AppSettings.MORPH_STYLE_LIQUID)
        // The glass form scales with the shape as well: size and focus are the same idea - content settling into
        // place - and without the scale it differs from "scale + fade" only by the blur, which is the one thing
        // he would not see at the end of a 260 ms morph.
        morphScaleOn = style == AppSettings.MORPH_STYLE_BALANCED || style == AppSettings.MORPH_STYLE_SHAPE_ONLY ||
            style == AppSettings.MORPH_STYLE_GLASS
        // The glass form needs the shape-driven pass (the blur is written there), and it keeps the row's scale:
        // focus and size are the same idea - the content settling into place.
        morphGlassOn = style == AppSettings.MORPH_STYLE_GLASS
        morphOpacityFollowsShape = morphScaleOn || morphGlassOn
        morphScaleFrom = (100 - AppSettings.getMorphContentScalePct(this)) / 100f
        morphSwapAt = AppSettings.getMorphGlyphSwapPct(this) / 100f
        // Not a switch any more. His log had `ride=off` on every morph of this session - the Lab checkbox was
        // the one control that silently deleted the praised icon morph from all four styles, which is precisely
        // the complaint. A property he has praised does not hide behind a checkbox he has to remember.
        morphIconRide = true
        // The glass look used to defocus the labels while the box moved and sharpen them at the end. His verdict
        // on b1415: "pill ko tap karo to smooth hai, but content starting mei blur rehta hai, fully expand pe
        // clear, collapse mei bhi blur" - the smooth part is the style, the blur is a defect he has to look
        // through. Apple's lensing bends the material *behind* a translucent panel; this island has nothing behind
        // it, so the only thing my blur could bend was the text. Cut: the style keeps the timing he praised and
        // stops touching the content views at all.
        morphBlurPx = if (morphGlassOn) 0f else dp(GLASS_BLUR_DP).toFloat()
        morphContentDropPx = dp(AppSettings.getMorphContentDropDp(this)).toFloat()
        morphEntryDrop = AppSettings.getMorphEntry(this) == AppSettings.MORPH_ENTRY_DROP
        // The content floats in only when the surface under it floats too: the box has to be a spring for the
        // buoyancy to read as liquid instead of latency, and only the way IN has a box that arrives first -
        // on the way OUT the content leaves on its own axis (the magnet, or the classic exit), which he has
        // never complained about.
        morphBuoyOn = morphEntryDrop && towardCard && MotionVariant.isSpring(morphVariant) &&
            morphVariant != AppSettings.MORPH_STYLE_BLUEPRINT  // the pendulum owns blueprint's content
        islandView?.translationY = 0f
        // The column gets its own cached layer while the morph owns its scale: stretching a texture is one
        // matrix multiply per frame, stretching a software view is a re-raster of every TextView it holds.
        gridContentSec?.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        // The stagger fades the same views the blur is resolving; two entrances on one element is a third
        // thing nobody can name, so the glass form runs on focus alone.
        // The liquid style times the content with a gate instead of a stagger; two windows on one fade is a
        // third thing nobody can name, and he has already told me that.
        morphStagger = if (morphGlassOn || morphLiquidOn) 0f else AppSettings.getMorphStaggerPct(this) / 100f
        // ChatGPT's Phase E is a *staged* return: the content is gone in 100-140 ms while the container takes
        // 170-210 ms. So this style owns its own goneBy rather than letting the Lab's slider undo the design -
        // below about 0.55 the two halves read as one event, above it the card empties and then shrinks.
        morphOutBy = if (morphHyperOn && !morphTowardCard) 0.65f else AppSettings.getMorphGoneByPct(this) / 100f
        morphCarryGrowing = toW + toH >= fromW + fromH
        morphFromW = fromW; morphToW = toW; morphFromH = fromH; morphToH = toH
        morphIconLauncher = appIconView?.drawable
        morphIconPill = pillPreviewIcon?.drawable
        // The badge belongs to the pill the morph ENDS on, not the middle of the trip: while the box travels,
        // the destination rides without its counter, and the cleanup below puts it back through the one
        // policy writer. "In this process wo count badge bhi dikh jata hai" - since the destination is
        // visible from frame one, the counter came with it at frame one; his rule for the badge was always
        // "earn the attention", and a morph in flight has not earned it.
        if (!towardCard) pillPreviewCount?.visibility = View.GONE
        applyMorphCarry(0f)
        TraceLog.morph(
            "start $label dur=${durationMs}ms style=${AppSettings.getMorphStyleName(this)} " +
            "toward=${if (morphTowardCard) "card" else "pill"} " +
                "carry=${if (morphCarryOn) "on" else "off"} scale=${if (morphScaleOn) "%.2f".format(morphScaleFrom) else "off"} " +
                "swap=${(morphSwapAt * 100).toInt()}% ride=" + (if (morphV2On) "v2(blueprint,0.78) " else "classic(b1378) ") +
                "glass=${if (morphGlassOn) "on defocus=cut" else "off"} " +
                "entry=${if (morphEntryDrop) "drop" else "centred"} drop=${AppSettings.getMorphContentDropDp(this)}dp " +
                "stagger=${(morphStagger * 100).toInt()}% goneBy=${(morphOutBy * 100).toInt()}% " +
                // The travel is still printed, as a fact about the box rather than a measured slot: 0 px means
                // the two rows are on one line, and any non-zero entry there would mean a vertical drift that
                // b1378 never had. If "ride=classic" ever shows up with the drop off and a box edge of 0, the
                // icon is again being carried by someone else's transform.
                "boxLeft=${lastMorphBoxLeft}px roll=${if (AppSettings.getMorphCountRoll(this)) "on" else "off"}" +
            // The variant's own numbers, printed rather than implied: when he says "3rd jaisa hi hai", the log
            // now answers whether the style he picked is the style that ran, and with what curve.
            if (morphVariantOn || morphV2On) " variant=${AppSettings.getMorphStyleName(morphVariant)}" +
                " profile=${MotionVariant.profileName(motionProfile)}" +
                " response=${"%.2fs".format(morphResponseSec)} damping=${"%.2f".format(morphDamping)}" +
                " gate=${(morphGate * 100).toInt()}%" +
                " magnet=${(morphMagnet * 100).toInt()}% squeeze=${(morphSqueeze * 100).toInt()}%" +
                " axis=${MotionVariant.SPRING_AXIS} travelV=${morphTravelV}px down" +
                " buoy=${"%.2fs".format(morphResponseSec * MotionVariant.BUOY_RESPONSE_SCALE)}/${"%.2f".format(MotionVariant.BUOY_DAMPING)}"
            else if (morphV2On) " variant=blueprint-v2 container=${morphResponseSec}s/deep:${morphDamping}" +
                " text=px(-40/+26/-5/+2/0) icon=0.5x,+40ms haptic=1x@t0.337" +
                " collapse=${MotionVariant.V2_COLLAPSE_TOTAL_MS}ms@0.90"
            else "" 
        )
    }

    /**
     * Round 43-3 (his keyframes verbatim): a 220 ms confirmation pulse on the pill itself, AFTER the
     * collapse's hard lock - not part of the collapse curve (that stays crisp/zero-overshoot). Center
     * origin, volume-constant: sy 1.0 -> 0.86 (@35%) -> 1.04 (@65%) -> 1.0, sx the inverse-louder partner
     * 1.0 -> 1.08 -> 0.97 -> 1.0, so area reads constant at every keyframe pair. The pulse target is the
     * pill views + the island shell: islandView carries the capsule background, pillPreviewRoot carries
     * the icon at lock time; both scale together so silhouette and content gulp as ONE body.
     */
    private var v2GulpAnim: ValueAnimator? = null
    private fun runV2GulpPulse() {
        v2GulpAnim?.cancel()
        val shell = islandView ?: return
        val pill = pillPreviewRoot
        val targets = if (pill != null) listOf(shell, pill) else listOf(shell)
        for (v in targets) { v.pivotX = v.width / 2f; v.pivotY = v.height / 2f }
        val keyF = floatArrayOf(0f, 0.35f, 0.65f, 1f)
        val keySy = floatArrayOf(1f, 0.86f, 1.04f, 1f)
        val keySx = floatArrayOf(1f, 1.08f, 0.97f, 1f)
        fun at(keys: FloatArray, f: Float): Float {
            for (i in 1..3) if (f <= keyF[i]) {
                val span = keyF[i] - keyF[i - 1]
                return keys[i - 1] + (keys[i] - keys[i - 1]) * ((f - keyF[i - 1]) / span)
            }
            return keys[3]
        }
        TraceLog.morph("v2 gulp: lock+0ms - 220ms confirm pulse begins (sy 1.0->0.86->1.04->1.0, sx 1.0->1.08->0.97->1.0)")
        v2GulpAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = MotionVariant.V2_GULP_MS
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { an ->
                val f = an.animatedValue as Float
                val sy = at(keySy, f); val sx = at(keySx, f)
                for (v in targets) { v.scaleY = sy; v.scaleX = sx }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    for (v in targets) { v.scaleY = 1f; v.scaleX = 1f }
                    TraceLog.morph("v2 gulp: done - pill back at 1.0/1.0")
                }
            })
            start()
        }
    }

    private fun endMorphPerf(label: String) {
        if (v2TraceFrames > 0) TraceLog.morph("v2 trajectory: frames=" + v2TraceFrames + " " + v2TraceSamples)
        // Read the flight-end values BEFORE clearMorphCarry/pin-restore rewrite the tree to the next-morph
        // baseline - the b1440 "alpha 0.00 at 40% vs 1.00 at end" contradiction lived exactly in this order.
        val endPreA = gridContentSec?.alpha ?: -1f
        val endPreTy = gridContentSec?.translationY ?: -999f
        val endPreSy = gridContentSec?.scaleY ?: -1f
        clearMorphCarry()
        morphV2On = false
        v2HapticDone = false
        setContentPinnedForMorph(false)
        TraceLog.morph("morph end evidence (FLIGHT-END, read PRE-restore): contentSec alpha=" + "%.2f".format(endPreA) + " tY=" + "%.1f".format(endPreTy) + " scaleY=" + "%.3f".format(endPreSy) + " | post-restore baseline alpha=1.00 is next-morph prep only; a collapse keeps hitting island-sleep at +90 ms so a frame of restored card content can never surface (overlay bed + top-pinned layout)")
        gridRoot?.setLayerType(View.LAYER_TYPE_NONE, null)
        // Size first, then release the drawn box - same message, one traversal after both. On a collapse the
        // pinned view is still card-sized at this instant, so clearing the frame before the resize would
        // paint a full-size black card for exactly one frame: a new ghost bought by fixing the old one.
        updateIslandLayout(morphFinalW, morphFinalH, morphFinalR)
        islandMorph?.clearMorphFrame()
        morphPinW = null; morphPinH = null
        morphNaturalH = 0
        morphTravelV = 0
        val meter = morphMeter
        morphMeter = null
        // Armed again on purpose: the settle is where the teardown lands and where the reported jumps sit,
        // and the last frames of a morph are the ones a window closed at the start would miss.
        armFrameWatch()
        if (meter != null) TraceLog.morph(
            "end $label ${meter.summary()} hz=${FrameWatch.snapHertz(frameWatch.periodMs())} " +
                "layouts=${frameWatch.layoutPasses}/${frameWatch.midMorphLayouts} regions=${frameWatch.regionPasses} " +
                "gc=${morphGcStart?.let { gcSnapshot().deltaText(it) } ?: "off"}"
        )
        // The state the card is left in, at the exact moment the drawn box stops clipping. This is the line
        // that answers "the last frame shows the expanded content": if `clip` is on but `grid` is still
        // visible at full alpha, what leaks is ours and not the renderer's.
        TraceLog.morph(
            "end-state size=${islandLayoutParams?.width}x${islandLayoutParams?.height} " +
                "clip=${islandView?.clipToOutline} " +
                "grid=${visName(gridRoot)}/${gridRoot?.alpha} pill=${visName(pillPreviewRoot)}/${pillPreviewRoot?.alpha}"
        )
    }

    private fun getPillBadgeWidthDp(): Int {
        // Pill size is stable. Notifications only change icon/count inside the pill,
        // never the pill's outer width.
        return AppSettings.getIslandWidthDp(this)
    }

    private fun getTargetWidth(s: IslandStage) = when(s) { IslandStage.STAGE1_IDLE -> AppSettings.getIslandWidthDp(this); IslandStage.STAGE2_PING -> getPillBadgeWidthDp(); IslandStage.STAGE3_FULL -> AppSettings.getIslandExpandedWidthDp(this) }
    private fun getTargetHeight(s: IslandStage) = when(s) { IslandStage.STAGE1_IDLE -> AppSettings.getIslandHeightDp(this); IslandStage.STAGE2_PING -> AppSettings.getIslandHeightDp(this); IslandStage.STAGE3_FULL -> AppSettings.getIslandExpandedHeightDp(this) }
    private fun getTargetRadius(s: IslandStage) = if (s == IslandStage.STAGE3_FULL) AppSettings.getIslandExpandedCornerRadiusDp(this) else AppSettings.getIslandCornerRadiusDp(this)

    private fun scheduleAutoCollapse() {
        if (isReplyMode) return
        autoCollapseRunnable?.let { mainHandler.removeCallbacks(it) }
        val size = notificationQueue.size
        val delay = when { size == 0 -> 5200L; size in 1..5 -> 3000L; size in 6..19 -> 1500L; size in 20..34 -> 800L; else -> 250L }
        autoCollapseRunnable = Runnable { if (notificationQueue.isNotEmpty()) processNextInQueue() else setStageAnimated(IslandStage.STAGE1_IDLE, ExpandReason.AUTO_NOTIFICATION) }.also { mainHandler.postDelayed(it, delay) }
    }

    private fun openCurrentNotification() {
        if (isReplyMode) return
        val openedKey = getCurrentRingModel()?.conversationKey
        try {
            currentPendingIntent?.send() ?: currentPackageName?.let { pkg ->
                packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        } catch (_: Exception) {}
        // "Tap a card" means "I handled THIS one", so this one is discarded and the rest stay visible.
        // It used to be postCollapseIsland(), which hid the expanded view and the pill badge together -
        // every other unread chat vanished unopened, while the chat you had just read stayed in the ring
        // and came back on the next notification.
        ringEvent("open tapped chat key=$openedKey")
        if (openedKey != null) dismissConversationFromRing(openedKey, reason = 0) else postCollapseIsland()
    }

    private fun showIslandInternal() {
        invalidateContentCache() // the live views are new, so what they hold is unknown until the first update
        hideIslandInternal()
        if (visualRoot != null && islandView != null) {
            // WARM WAKE: the window survived the hide - wake it instead of building a twin of it.
            setIslandTouchable(true)
            visualRoot?.visibility = View.VISIBLE
            updateAllToCurrentState()
            TraceLog.line("ISLAND", "wake: showIsland warm path - no build run")
            return
        }
        val h = dp(AppSettings.getIslandHeightDp(this)); val w = dp(AppSettings.getIslandWidthDp(this)); val r = dp(AppSettings.getIslandCornerRadiusDp(this)).toFloat()
        val rateVote = refreshRateVote()
        // Seed the frame budget from the vote, so a 120 Hz panel is not judged on an assumed 16 ms frame.
        if (rateVote > 1f) frameWatch.notePanelPeriod((1000f / rateVote).toLong())
        visualParams = WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WINDOW_FLAGS_MASTER, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP; y = 0; if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = 1; windowAnimations = 0; title = "HyperIslandProVisual"; if (rateVote > 0f) preferredRefreshRate = rateVote }
        
        // THE INTERCEPTOR: Detects touches based on Reply State
        // Phase 3.5 Fluid — Off-Switch + Reflection Hack (compile-safe)
        visualRoot = object : FrameLayout(this) {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                val action = event.actionMasked
                val wantsOutsideTap = isReplyMode || currentStage == IslandStage.STAGE3_FULL

                if (!wantsOutsideTap) {
                    outsideGestureActive = false
                    return super.dispatchTouchEvent(event)
                }

                val rect = Rect()
                this@HyperAccessibilityService.islandView?.getGlobalVisibleRect(rect)
                val insideIsland = rect.contains(event.rawX.toInt(), event.rawY.toInt())

                when (action) {
                    MotionEvent.ACTION_DOWN -> {
                        outsideGestureActive = !insideIsland
                        if (outsideGestureActive) {
                            if (isReplyMode) exitReplyMode() else postSwipeUpIsland()
                            return true
                        }
                    }
                    MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (outsideGestureActive) {
                            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                                outsideGestureActive = false
                            }
                            return true
                        }
                    }
                }

                return super.dispatchTouchEvent(event)
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

                            if (isReplyMode || currentStage == IslandStage.STAGE3_FULL) {
                                // Full screen interception during Reply or Expanded mode:
                                // root dispatchTouchEvent handles outside-tap collapse/exit without a second watcher window stealing child taps.
                                val rootW = resources.displayMetrics.widthPixels
                                val rootH = resources.displayMetrics.heightPixels
                                region.set(0, 0, rootW, rootH)
                            } else {
                                // Normal/Pill island bounds — Touch Pass-through
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
        this@HyperAccessibilityService.islandView = object : FrameLayout(this), MorphFrameHost {
            // --- the drawn morph box; math lives in core/IslandMorphFrame.kt (tested) -----------------
            // While a morph runs this view is laid out at its FINAL size and the box you see is drawn here
            // at the animated rect. No `layoutParams` write per frame, so the screen-sized overlay window is
            // no longer measured and laid out 60 times a second - the frame drops the tester reported on
            // b1343 - and only the island area is dirtied.
            private var morphFrame: MorphFrame? = null
            private var morphRadius = 0f
            private val morphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
            private val morphClipPath = Path()

            init {
                setWillNotDraw(false)
            }

            /**
             * Builds the frame's silhouette into [morphClipPath]: the plain round rect when neckPx <= 0,
             * the neck profile otherwise - top corners rounded and FULL WIDTH, edges walking inward as
             * IslandMorphFrame.neckInset(yFraction, neckPx), bottom corners rounded and back at full width.
             * onDraw and dispatchDraw both call this so the background and the containment clip can never
             * describe two different shapes (his wedge warning: the profile, not a linear taper, is what
             * makes it read as elastic).
             */
            private fun fillMorphSilhouette(f: MorphFrame, out: Path) {
                val l = f.left.toFloat(); val t = f.top.toFloat(); val r = f.right.toFloat(); val b = f.bottom.toFloat()
                out.reset()
                if (f.neckPx <= 0.5f) {
                    out.addRoundRect(RectF(l, t, r, b), morphRadius, morphRadius, Path.Direction.CW)
                    return
                }
                val h = (b - t).coerceAtLeast(1f)
                val rad = morphRadius.coerceAtMost(h / 2f)
                val steps = 20
                out.moveTo(l + rad, t)
                // top edge (full width - the icon edge never insets) and the top-right corner
                out.lineTo(r - rad, t)
                out.quadTo(r, t, r, t + rad)
                // right edge down: first corner-exit point then the neck profile
                for (i in 1..steps) {
                    val y = t + rad + (h - 2f * rad) * i / steps
                    val inRight = IslandMorphFrame.neckInset((y - t) / h, f.neckPx)
                    out.lineTo(r - inRight, y)
                }
                out.quadTo(r - IslandMorphFrame.neckInset(1f, f.neckPx), b, r - rad, b)
                // bottom edge back at full width (neckInset(1f) == 0, so the corner lands at r)
                out.lineTo(l + rad, b)
                out.quadTo(l - IslandMorphFrame.neckInset(1f, f.neckPx) * 0f, b, l, b - rad)
                for (i in steps downTo 0) {
                    val y = t + rad + (h - 2f * rad) * i / steps
                    out.lineTo(l + IslandMorphFrame.neckInset((y - t) / h, f.neckPx), y)
                }
                out.quadTo(l, t, l + rad, t)
                out.close()
            }

            override fun applyMorphFrame(frame: MorphFrame, cornerRadius: Float) {
                val previous = morphFrame
                morphFrame = frame
                morphRadius = cornerRadius
                if (background != null) background = null
                if (clipToOutline) clipToOutline = false
                for (i in 0 until childCount) getChildAt(i).translationY = frame.contentOffsetY.toFloat()
                val d = IslandMorphFrame.dirtyBounds(previous, frame)
                invalidate(d[0], d[1], d[2], d[3])
            }

            override fun clearMorphFrame() {
                if (morphFrame == null) return
                morphFrame = null
                background = this@HyperAccessibilityService.islandBackground
                clipToOutline = true
                for (i in 0 until childCount) getChildAt(i).translationY = 0f
                invalidate()
            }

            override fun onDraw(canvas: Canvas) {
                val f = morphFrame ?: run { super.onDraw(canvas); return }
                fillMorphSilhouette(f, morphClipPath)
                canvas.drawPath(morphClipPath, morphPaint)
            }

            override fun dispatchDraw(canvas: Canvas) {
                val t0 = if (frameWatch.measuring) System.nanoTime() else 0L
                val f = morphFrame ?: run {
                    super.dispatchDraw(canvas) // timed after the draw, so the number includes it
                    if (t0 != 0L) frameWatch.noteDrawNanos(System.nanoTime() - t0)
                    return
                }
                // Content stays inside the drawn box, which is what clipToOutline was doing for us. It is a
                // containment clip on a subtree that is fading, not a mask that reveals it - the reveal
                // look that was rejected earlier is a different thing and stays out.
                fillMorphSilhouette(f, morphClipPath)
                val layer = canvas.save()
                canvas.clipPath(morphClipPath)
                super.dispatchDraw(canvas)
                canvas.restoreToCount(layer)
                // Only our own slice of the frame, so "the renderer was busy" and "we were slow" stop being
                // the same number. The RenderThread's side is still invisible to us - said so in the log.
                if (t0 != 0L) frameWatch.noteDrawNanos(System.nanoTime() - t0)
            }
            override fun dispatchTouchEvent(e: MotionEvent): Boolean {
                val action = e.actionMasked
                // A drag is animation too: it is the case the morph meter never saw, and the one the
                // "everything is laggy" report was about as often as the morph.
                if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) armFrameWatch()
                // Children first: reply/action buttons must get the touch before the island claims a gesture.
                val childHandled = super.dispatchTouchEvent(e)

                when (action) {
                    MotionEvent.ACTION_DOWN -> {
                        // Fresh machine per gesture: the thresholds are proportions of the current card,
                        // and the card is a different width in every stage.
                        val g = ensureGesture()
                        islandGesture = g
                        TraceLog.gesture("down ${e.rawX.toInt()},${e.rawY.toInt()} stage=$currentStage ring=${notificationRing.size}")
                        // A new touch must not inherit a push that never finished settling.
                        if (dragMode != DRAG_NONE || ringSwapInFlight) endRingSwap("new touch")
                        g.begin(
                            x = e.rawX,
                            y = e.rawY,
                            timeMs = e.eventTime,
                            // A press that a button took must never turn into "open this chat".
                            allowTap = !childHandled,
                            pagesEnabled = currentStage == IslandStage.STAGE3_FULL && notificationRing.size > 1,
                            atFirstPage = currentRingIndex == 0,
                            atLastPage = currentRingIndex >= notificationRing.lastIndex
                        )
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> {
                        // A second finger is not a swipe; abandon so its movements cannot be misread.
                        TraceLog.gesture("second pointer - gesture abandoned")
                        islandGesture?.cancel()
                        springBackDrag()
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val g = islandGesture
                        if (g != null && g.move(e.rawX, e.rawY, e.eventTime)) showDragOffset(g)
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        islandGesture?.cancel()
                        islandGesture = null
                        springBackDrag()
                    }
                    MotionEvent.ACTION_UP -> {
                        val g = islandGesture
                        islandGesture = null
                        if (g == null) {
                            TraceLog.gesture("UP with no gesture (DOWN was not ours) child=$childHandled")
                            return childHandled
                        }
                        g.move(e.rawX, e.rawY, e.eventTime)
                        val offX = g.offsetX
                        val act = g.end(e.eventTime)
                        TraceLog.gesture(
                            "up action=$act off=${offX.toInt()} travel=${g.travelXAtRelease.toInt()} " +
                                "vel=${g.velocityXAtRelease.toInt()} horizontal=${g.axisIsHorizontal} " +
                                "child@down=$childHandled drag=$dragMode stage=$currentStage ring=${notificationRing.size}"
                        )
                        when (act) {
                            IslandGesture.Action.TAP -> {
                                clearDragVisuals()
                                // STAGE3: the card is the thing, so a tap opens it (this case was missing
                                // once and the island looked dead). Anywhere else a tap opens the card.
                                if (currentStage == IslandStage.STAGE3_FULL) openCurrentNotification() else postToggleExpanded()
                            }
                            IslandGesture.Action.SWIPE_UP -> {
                                // Snap the offset to zero instead of animating it back: the stage morph
                                // starts on the same frame and is what the eye is following.
                                clearDragVisuals()
                                postSwipeUpIsland()
                            }
                            IslandGesture.Action.PAGE_OLDER, IslandGesture.Action.PAGE_NEWER -> {
                                val older = act == IslandGesture.Action.PAGE_OLDER
                                when {
                                    dragMode == DRAG_PAGES && dragOlder == older -> finishRingPush(offX, commit = true)
                                    dragMode == DRAG_PAGES -> {
                                        // Started one way, finished the other: the push is undone, not
                                        // hijacked. Finishing it in the new direction would need a second
                                        // neighbour snapshot mid-flight, which is how ghosts get drawn.
                                        TraceLog.gesture("direction flipped mid-drag - springing back")
                                        finishRingPush(offX, commit = false)
                                    }
                                    else -> {
                                        // Nudge mode: no neighbour was on that side when the drag began. If
                                        // the finger came back and went off the other way, let it push -
                                        // otherwise a direction reversal would be silently swallowed.
                                        if (dragMode == DRAG_NUDGE) {
                                            clearDragVisuals()
                                            dragMode = DRAG_NONE
                                        }
                                        if (!navigateRingBySwipe(older = older, fromOffsetPx = 0f)) springBackDrag()
                                    }
                                }
                            }
                            IslandGesture.Action.NONE -> springBackDrag()
                        }
                    }
                }
                // Always consume while the island is up. The touchable region is already limited to the
                // island rect outside the expanded stages, so nothing outside the pill is being stolen -
                // and returning false for STAGE1 (the old behaviour) starved the gesture of the MOVE
                // events it needs to follow the finger, which is why a pill swipe was judged by a single
                // DOWN-to-UP distance and could come out as "tap".
                return true
            }
        }.apply {
            background = this@HyperAccessibilityService.islandBackground; clipToOutline = true; outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, this@HyperAccessibilityService.outlineRadius) } }
            this@HyperAccessibilityService.islandMorph = this
            this@HyperAccessibilityService.gridRoot = LinearLayout(this@HyperAccessibilityService).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(1), dp(1), dp(1), dp(1)); visibility = View.GONE; alpha = 0f; weightSum = 1f
                val iconSec = FrameLayout(context).also { this@HyperAccessibilityService.gridIconSec = it }.apply { this@HyperAccessibilityService.appIconView = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }; addView(this@HyperAccessibilityService.appIconView, FrameLayout.LayoutParams(dp(38), dp(38), Gravity.CENTER)) }
                val contentSec = LinearLayout(context).also { this@HyperAccessibilityService.gridContentSec = it }.apply {
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
            // Content is locked to the EXPANDED width for its whole life. The card clips it
            // (clipToOutline is on this container), so expanding/collapsing reveals or masks text
            // instead of re-measuring it at 48 intermediate widths. That re-wrap per frame was the
            // expand/collapse jitter — MATCH_PARENT here was the root cause.
            addView(this@HyperAccessibilityService.gridRoot, FrameLayout.LayoutParams(expandedContentWidthPx(), -1, Gravity.START or Gravity.CENTER_VERTICAL))

            // Compact pill badge preview: stable pill, spread content.
            // Icon stays left, count badge stays right — no cramped center cluster.
            this@HyperAccessibilityService.pillPreviewRoot = FrameLayout(this@HyperAccessibilityService).apply {
                visibility = View.GONE
                alpha = 0f
                setPadding(dp(14), 0, dp(14), 0)
                setClipChildren(false)
                setClipToPadding(false)

                this@HyperAccessibilityService.pillPreviewIcon = ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    background = null
                    imageTintList = null
                    clearColorFilter()
                }

                this@HyperAccessibilityService.pillPreviewCount = TextView(context).apply {
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    minWidth = dp(28)
                    minHeight = dp(26)
                    setPadding(dp(7), 0, dp(7), 0)
                    visibility = View.GONE
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        setColor(Color.rgb(0, 150, 255))
                        cornerRadius = dp(13).toFloat()
                    }
                    setIncludeFontPadding(false)
                }

                addView(
                    this@HyperAccessibilityService.pillPreviewIcon,
                    FrameLayout.LayoutParams(dp(32), dp(32), Gravity.START or Gravity.CENTER_VERTICAL).apply {
                        leftMargin = dp(0)
                    }
                )
                addView(
                    this@HyperAccessibilityService.pillPreviewCount,
                    FrameLayout.LayoutParams(-2, dp(26), Gravity.END or Gravity.CENTER_VERTICAL).apply {
                        rightMargin = dp(0)
                    }
                )
            }
            addView(this@HyperAccessibilityService.pillPreviewRoot, FrameLayout.LayoutParams(-1, -1))

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

        }
        this@HyperAccessibilityService.islandLayoutParams = FrameLayout.LayoutParams(w, h).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL }
        visualRoot?.addView(this@HyperAccessibilityService.islandView, this@HyperAccessibilityService.islandLayoutParams); updateOutlineForIsland(w, h, r)
        // Every layout pass of the card counts, whoever asked for it: a relayout storm is invisible to a
        // meter that watches only animations, and it is the failure mode we keep rediscovering.
        val layoutWatcher = ViewTreeObserver.OnGlobalLayoutListener {
            frameWatch.noteLayoutPass(midMorph = morphAnimator?.isRunning == true)
        }
        frameLayoutWatcher = layoutWatcher
        islandView?.viewTreeObserver?.addOnGlobalLayoutListener(layoutWatcher)
        try { windowManager?.addView(visualRoot, visualParams) } catch (_: Exception) { hideIslandInternal() }
        prewarmIslandLayersOnce()
    }

    private fun updateOutsideWatcherForState() {
        // Disabled: this separate full-screen watcher window was stealing taps from the island
        // and collapsing it before Reply/action buttons could receive clicks.
        // Main visualRoot + reflection touch region already handle pass-through/off-switch behavior.
        removeOutsideWatcher()
    }
    private fun ensureOutsideWatcher() { if (outsideWatcherView != null) return; outsideWatcherView = FrameLayout(this).apply { setBackgroundColor(0); setOnTouchListener { _, event -> if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_OUTSIDE) postCollapseIsland() ; false } }; try { windowManager?.addView(outsideWatcherView, WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, 16777216 or 8 or 4096 or 512 or 256, -3).apply { gravity = Gravity.TOP or Gravity.START; title = "HyperIslandProOutside" }) } catch (_: Exception) {} }
    private fun removeOutsideWatcher() { try { windowManager?.removeViewImmediate(outsideWatcherView!!) } catch (_: Exception) {}; outsideWatcherView = null }
    private var regionUpdateDeferred = false

    private fun forceRegionUpdate() {
        // Never fight the morph: a requestLayout while the card is animating re-centres and re-clips
        // the content mid-flight. Park it, and flush when the morph lands.
        if (morphAnimator?.isRunning == true || ghostAnimator?.isRunning == true) {
            regionUpdateDeferred = true
            return
        }
        frameWatch.noteRegionPass() // counted, because every one of these is a full-screen layout request
        visualRoot?.post { visualRoot?.requestLayout(); visualRoot?.parent?.requestLayout() }
    }

    private fun flushDeferredRegionUpdate() {
        if (!regionUpdateDeferred) return
        regionUpdateDeferred = false
        forceRegionUpdate()
    }
    fun updateAllToCurrentState() { val w = dp(getTargetWidth(currentStage)); val h = dp(getTargetHeight(currentStage)); val r = dp(getTargetRadius(currentStage)).toFloat(); updateIslandLayout(w, h, r) }
    /**
     * "Sleep, don't discard" (Problem C of the combined audit): the island used to be DETACHED on every hide,
     * so every wake re-inflated the whole view tree on the very path the first expansion runs - the cold-start
     * that reads snappy. Now it sleeps: the view stays attached (invisible to everything by GONE, unreachable
     * to the user's finger by FLAG_NOT_TOUCHABLE - printed in the log as the audit's "region recompute"),
     * and its build cost is paid exactly once in the process's lifetime. The state resets below are unchanged
     * from the discard days, deliberately: sleeping shares the discard's own state contract, only the window
     * survives.
     */
    private fun hideIslandInternal() {
        detachFrameWatchers(); endRingSwap("hide"); morphAnimator?.cancel(); ghostAnimator?.cancel()
        autoCollapseRunnable?.let { mainHandler.removeCallbacks(it) }; removeOutsideWatcher()
        setIslandTouchable(false)
        visualRoot?.visibility = View.GONE
        currentStage = IslandStage.STAGE1_IDLE; isReplyMode = false; isGhostReplyMode = false; replyGhostView?.clearGhost()
        TraceLog.line("ISLAND", "sleep: view retained (window untouchable, see flags line above)")
    }

    /** The audit's "zero-area touch region while hidden": one flag on the whole window, and the log prints the
     *  bits so the claim is verified off the device (0x10 set = the window cannot take a touch at all). */
    private fun setIslandTouchable(on: Boolean) {
        val root = visualRoot ?: return
        val params = visualParams ?: return
        val mask = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val want = if (on) params.flags and mask.inv() else params.flags or mask
        if (want != params.flags) {
            params.flags = want
            try { windowManager?.updateViewLayout(root, params) } catch (_: Exception) {}
        }
        TraceLog.line("ISLAND", "touchable=$on flags=0x" + params.flags.toString(16))
    }

    /** Every site that used to say "no window? build" now says "wake the island" - with a retained window the
     *  null check alone can leave a sleeper GONE while its content updates into the dark. */
    private fun ensureIslandAwake() {
        if (visualRoot == null || islandView == null) { showIslandInternal(); return }
        setIslandTouchable(true)
        visualRoot?.visibility = View.VISIBLE
        TraceLog.line("ISLAND", "wake: warm path - retained view")
    }

    /** Problem C.3: once per PROCESS, prime the GPU layers at a calm moment (right after the island's first
     *  build, pill-state), so the first-ever expansion doesn't pay texture allocation mid-animation. */
    private var islandPrewarmed = false
    private fun prewarmIslandLayersOnce() {
        if (islandPrewarmed) return
        islandPrewarmed = true
        visualRoot?.post {
            gridRoot?.setLayerType(View.LAYER_TYPE_HARDWARE, null); gridRoot?.buildLayer()
            gridContentSec?.setLayerType(View.LAYER_TYPE_HARDWARE, null); gridContentSec?.buildLayer()
            TraceLog.line("MORPH", "prewarm: GPU layers primed once for this process")
        }
    }
    private fun loadAppIcon(pkg: String) = try { packageManager.getApplicationIcon(pkg) } catch (_: Exception) { null }

    private fun loadPillNotificationIcon(pkg: String, smallIcon: Icon?) = try {
        val cached = PillIconCache.get(pkg) // lets TestLab reuse the latest real smallIcon/legacy icon
        val iconToUse = smallIcon ?: cached?.smallIcon
        val legacyResId = cached?.legacyIconResId ?: 0
        val mode = AppSettings.getPillIconRenderMode(this)
        val tint = getPillIconTint(pkg)

        if (mode == AppSettings.PILL_ICON_AUTO && pkg.contains("instagram", true)) {
            // Instagram's brand identity is the gradient. Notification smallIcon is monochrome,
            // so Auto mode uses a clean gradient camera glyph instead of a flat red icon in the pill.
            InstagramGradientCameraDrawable()
        } else {
            val manual = { loadManualResourceDrawable(pkg, iconToUse) }
            val loaded = { iconToUse?.let { loadViaIconLoadDrawable(pkg, it) } }
            val legacy = { loadLegacyResourceDrawable(pkg, legacyResId) }
            val adaptive = { loadPillDisplayIcon(pkg) }
            val generic = { loadGenericPillGlyph(pkg) }

            val chosen = when (mode) {
                AppSettings.PILL_ICON_MANUAL_RESOURCE_NO_VALIDATION -> manual()
                AppSettings.PILL_ICON_MANUAL_RESOURCE_VALIDATED -> manual()?.takeIf { looksLikeGlyph(it) }
                AppSettings.PILL_ICON_LOAD_DRAWABLE_NO_VALIDATION -> loaded()
                AppSettings.PILL_ICON_LOAD_DRAWABLE_VALIDATED -> loaded()?.takeIf { looksLikeGlyph(it) }
                AppSettings.PILL_ICON_LEGACY_NO_VALIDATION -> legacy()
                AppSettings.PILL_ICON_ADAPTIVE_NO_VALIDATION -> adaptive()
                AppSettings.PILL_ICON_LAUNCHER -> loadLauncherPillIcon(pkg)
                AppSettings.PILL_ICON_GENERIC -> generic()
                else -> {
                    manual()?.takeIf { looksLikeGlyph(it) }
                        ?: loaded()?.takeIf { looksLikeGlyph(it) }
                        ?: adaptive()?.takeIf { looksLikeGlyph(it) }
                        ?: generic()
                }
            }

            Log.d(
                "HyperIslandPro",
                "PillIcon mode=${AppSettings.getPillIconRenderModeName(mode)} pkg=$pkg cached=${cached != null} legacy=$legacyResId chosen=${chosen?.javaClass?.simpleName}"
            )

            if (mode == AppSettings.PILL_ICON_LAUNCHER) chosen else chosen?.let { tintGlyph(it, tint) }
        }
    } catch (e: Exception) {
        Log.d("HyperIslandPro", "Pill icon resolver failed for $pkg: ${e.message}")
        loadGenericPillGlyph(pkg)
    }

    private fun loadManualResourceDrawable(pkg: String, icon: Icon?): Drawable? {
        return try {
            val type = icon?.let { getIconTypeCompat(it) }
            if (type != 2) return null // Icon.TYPE_RESOURCE = 2
            val resId = icon.let { getIconResIdCompat(it) } ?: 0
            if (resId == 0) return null
            val resPkg = icon.let { getIconResPackageCompat(it) }.ifBlank { pkg }
            loadDrawableFromPackageResource(resPkg, resId)
        } catch (e: Exception) {
            Log.d("HyperIslandPro", "manualResource failed for $pkg: ${e.message}")
            null
        }
    }

    private fun loadLegacyResourceDrawable(pkg: String, legacyResId: Int): Drawable? {
        if (legacyResId == 0) return null
        return try {
            loadDrawableFromPackageResource(pkg, legacyResId)
        } catch (e: Exception) {
            Log.d("HyperIslandPro", "legacyResource failed for $pkg/$legacyResId: ${e.message}")
            null
        }
    }

    private fun loadDrawableFromPackageResource(resPkg: String, resId: Int): Drawable? {
        return try {
            val pkgContext = createPackageContext(resPkg, Context.CONTEXT_IGNORE_SECURITY)
            pkgContext.getDrawable(resId)?.mutate()
        } catch (_: Exception) {
            try {
                val res = packageManager.getResourcesForApplication(resPkg)
                @Suppress("DEPRECATION")
                res.getDrawable(resId, null)?.mutate()
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun loadViaIconLoadDrawable(pkg: String, icon: Icon): Drawable? {
        return try {
            val iconContext = try { createPackageContext(pkg, Context.CONTEXT_IGNORE_SECURITY) } catch (_: Exception) { this }
            icon.loadDrawable(iconContext)?.mutate()
        } catch (_: Exception) {
            try { icon.loadDrawable(this)?.mutate() } catch (_: Exception) { null }
        }
    }

    private fun getIconTypeCompat(icon: Icon): Int? = try {
        icon.javaClass.getMethod("getType").invoke(icon) as? Int
    } catch (_: Exception) { null }

    private fun getIconResIdCompat(icon: Icon): Int? = try {
        icon.javaClass.getMethod("getResId").invoke(icon) as? Int
    } catch (_: Exception) { null }

    private fun getIconResPackageCompat(icon: Icon): String = try {
        icon.javaClass.getMethod("getResPackage").invoke(icon)?.toString().orEmpty()
    } catch (_: Exception) { "" }

    private fun looksLikeGlyph(drawable: Drawable): Boolean {
        return try {
            val size = 64
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val copy = drawable.constantState?.newDrawable()?.mutate() ?: drawable.mutate()
            copy.setBounds(0, 0, size, size)
            copy.draw(canvas)

            fun alphaAt(x: Int, y: Int) = Color.alpha(bmp.getPixel(x, y))
            val cornerAlphaMax = maxOf(alphaAt(2, 2), alphaAt(size - 3, 2), alphaAt(2, size - 3), alphaAt(size - 3, size - 3))
            var nonTransparent = 0
            var total = 0
            var x = 0
            while (x < size) {
                var y = 0
                while (y < size) {
                    total++
                    if (Color.alpha(bmp.getPixel(x, y)) > 18) nonTransparent++
                    y += 2
                }
                x += 2
            }
            val fraction = nonTransparent.toFloat() / total.toFloat().coerceAtLeast(1f)
            bmp.recycle()
            val pass = cornerAlphaMax < 80 && fraction in 0.005f..0.86f
            Log.d("HyperIslandPro", "PillGlyphCheck ${drawable.javaClass.simpleName}: corner=$cornerAlphaMax fraction=$fraction pass=$pass")
            pass
        } catch (_: Exception) {
            false
        }
    }

    private fun tintGlyph(drawable: Drawable, tint: Int): Drawable {
        val d = drawable.mutate()
        d.setTint(tint)
        d.setTintMode(PorterDuff.Mode.SRC_IN)
        d.colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
        return d
    }

    private fun loadLauncherPillIcon(pkg: String) = try {
        packageManager.getApplicationIcon(pkg)
    } catch (_: Exception) { null }

    private fun loadGenericPillGlyph(pkg: String) = object : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = getPillIconTint(pkg)
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = dp(2).toFloat()
            color = getPillIconTint(pkg)
        }
        private val bubble = RectF()
        private val tail = Path()

        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            if (w <= 0f || h <= 0f) return
            val l = b.left.toFloat()
            val t = b.top.toFloat()
            bubble.set(l + w * 0.16f, t + h * 0.18f, l + w * 0.84f, t + h * 0.66f)
            canvas.drawRoundRect(bubble, w * 0.16f, w * 0.16f, strokePaint)
            tail.reset()
            tail.moveTo(l + w * 0.34f, t + h * 0.64f)
            tail.lineTo(l + w * 0.23f, t + h * 0.84f)
            tail.lineTo(l + w * 0.50f, t + h * 0.66f)
            canvas.drawPath(tail, strokePaint)
        }

        override fun getIntrinsicWidth(): Int = dp(32)
        override fun getIntrinsicHeight(): Int = dp(32)
        override fun setAlpha(alpha: Int) { paint.alpha = alpha; strokePaint.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter; strokePaint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private fun getPillIconTint(pkg: String): Int = when {
        pkg.contains("telegram", true) -> Color.rgb(42, 171, 238)
        pkg.contains("whatsapp", true) -> Color.rgb(37, 211, 102)
        pkg.contains("instagram", true) -> Color.rgb(225, 48, 108)
        pkg.contains("google", true) -> Color.WHITE
        else -> Color.WHITE
    }

    private fun loadPillDisplayIcon(pkg: String) = try {
        val icon = packageManager.getApplicationIcon(pkg)
        if (Build.VERSION.SDK_INT >= 26 && icon is AdaptiveIconDrawable) {
            icon.foreground
        } else {
            icon
        }
    } catch (_: Exception) { null }

    /**
     * `getApplicationInfo` + `getApplicationLabel` are two binder round trips, and they used to run for every
     * notification of every app: his export put 10 stalls of >=24 ms inside them while the island was open.
     * A label does not change under a running process, so one lookup per package, and a failure is not cached
     * (a transient `NameNotFound` must not freeze the package name onto the card).
     */
    private val appNamesByPkg = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun getAppName(pkg: String): String = appNamesByPkg[pkg] ?: try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
            .also { appNamesByPkg[pkg] = it }
    } catch (_: Exception) {
        pkg
    }
    /**
     * Stamps a card with the *message's* instant when the app published one. The old one-liner used
     * postTime only, and Instagram pushes a thread's whole backlog in one go (capture seq 85: message
     * time 12.6 minutes before postTime), so a ten-minute-old chat arrived reading "now" - exactly the
     * complaint from the device.
     */
    private fun timeLabelFor(model: NotificationModel): String {
        val stamp = if (model.displayTimeMs > 0L) model.displayTimeMs else model.postTime
        val now = System.currentTimeMillis()
        val stampDay = Calendar.getInstance().apply { timeInMillis = stamp }
        val today = Calendar.getInstance().apply { timeInMillis = now }
        val sameLocalDay = stampDay.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
            stampDay.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
        return ChatDisplayPolicy.timeLabel(
            millis = stamp,
            nowMs = now,
            sameLocalDay = sameLocalDay,
            clockText = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(stamp)),
            dateText = SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(stamp))
        )
    }
    private fun buildDisplayText(appName: String, t: String, m: String): DisplayText { val a = appName.trim().ifBlank { "App" }; var ti = t.trim(); var me = m.trim(); if (ti.equals(a, true)) { ti = me; me = "" }; if (ti.isBlank() && me.isNotBlank()) { ti = me; me = "" }; return DisplayText(a, ti, me) }
    private fun updateOutlineForIsland(w: Int, h: Int, r: Float) { val left = ((resources.displayMetrics.widthPixels - w) / 2) + dp(AppSettings.getIslandXDp(this)); val top = dp(AppSettings.getIslandYDp(this)); outlineRect.set(left, top, left + w, top + h); outlineRadius = r; islandLayoutParams?.topMargin = top }
    private fun lerpEven(s: Int, e: Int, p: Float): Int { val v = (s + ((e - s) * p)).roundToInt(); return if (v % 2 != 0) v + 1 else v }
    private fun lerp(s: Float, e: Float, p: Float) = s + ((e - s) * p)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun createIslandBackground(r: Float): GradientDrawable = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; setColor(Color.BLACK); cornerRadius = r }
    override fun onDestroy() { invalidateContentCache(); stopStallWatch(); stopDisplayWatch(); hideIslandInternal(); if (instance === this) instance = null; super.onDestroy() }
}


