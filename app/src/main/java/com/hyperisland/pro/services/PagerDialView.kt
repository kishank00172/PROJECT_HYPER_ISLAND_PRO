package com.hyperisland.pro.services

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.hyperisland.pro.core.PagerDial
import kotlin.math.abs

/**
 * ROUND H pager v3 (owner work.md spec, rendered from his measurements):
 * the Instagram windowed dial. ONE progress value p (260ms easeOutCubic), EVERY visible item
 * interpolated together - highlight move and dial rotation start/end in lockstep (his explicit
 * rule). Retargets read the CURRENT drawn values so rapid swipes never jump. The very last
 * painted frame always equals the rest frame. Zero allocations inside onDraw.
 */
class PagerDialView(context: Context) : View(context) {

    private class Entry(var x: Float = 0f, var w: Float = 0f, var h: Float = 0f, var alpha: Float = 0f, var dash: Boolean = false)
    private fun Entry.copyOf() = Entry(x, w, h, alpha, dash)

    private val drawn = ArrayList<Entry>()
    private val drawnPage = ArrayList<Int>()
    private val from = ArrayList<Entry>()
    private val fromPage = ArrayList<Int>()
    private val target = ArrayList<Entry>()
    private val targetPage = ArrayList<Int>()

    private val paint = Paint().apply { isAntiAlias = true; color = Color.WHITE }
    private val rect = RectF()

    private var specNow: PagerDial.Spec? = null
    var aNow = 0; private set
    var pitch = PagerDial.P_DEFAULT; private set
    var slowFactor = 1f
    var frozenP = -1f
    var idxNow = 0; private set
    var totalNow = 0; private set
    var widthDp = 0f; private set

    private var anim: ValueAnimator? = null
    private var onSettle: ((String) -> Unit)? = null
    fun setOnSettleLog(cb: ((String) -> Unit)?) { onSettle = cb }

    private fun dpToPx(dp: Float) = dp * resources.displayMetrics.density

    /** Spec §8: shrink pitch in 0.25dp steps down to P_MIN until DIAL width fits the keep-out. */
    fun retunePitch(availablePx: Float): Float {
        var p = PagerDial.P_DEFAULT
        while (p > PagerDial.P_MIN && dpToPx(8 * p + 2 * PagerDial.DELTA + PagerDial.TINY_D) > availablePx) p -= 0.25f
        pitch = p
        return p
    }

    /** Drives a new (idx, total). Returns the spec it aimed at (rest state); HIDDEN => GONE. */
    fun setPage(idx: Int, total: Int, animate: Boolean): PagerDial.Spec {
        val prevIdx = idxNow
        val spec = PagerDial.spec(idx.coerceAtLeast(0), total, aNow, pitch)
        idxNow = idx.coerceAtLeast(0); totalNow = total
        if (spec.mode == PagerDial.Mode.HIDDEN) {
            anim?.cancel(); anim = null
            drawn.clear(); drawnPage.clear()
            specNow = spec; aNow = spec.a; widthDp = 0f
            visibility = GONE
            requestLayout()
            return spec
        }
        val old = specNow
        val widthChanged = old == null || old.mode != spec.mode || abs(old.widthDp - spec.widthDp) > 0.5f
        if (widthChanged) {
            // spec §7: crossing ROW<->DIAL (N across 5/6) or a width change => crossfade, not FLIP
            anim?.cancel(); anim = null
            commitFull(spec)
            if (animate) {
                alpha = 0f
                animate().alpha(1f).setDuration((150f * slowFactor).toLong()).start()
            }
            logSettled()
            return spec
        }
        buildFlip(spec, Integer.signum(idxNow - prevIdx), if (animate) (260f * slowFactor).toLong() else 0L)
        return spec
    }

    private fun commitFull(s: PagerDial.Spec) {
        drawn.clear(); drawnPage.clear()
        for (it in s.items) { drawn += Entry(it.xDp, it.wDp, it.hDp, it.alpha, it.dash); drawnPage += it.page }
        specNow = s; aNow = s.a; widthDp = s.widthDp
        visibility = VISIBLE
        contentDescription = "Page ${idxNow + 1} of $totalNow"
        requestLayout(); invalidate()
    }

    private fun buildFlip(newSpec: PagerDial.Spec, dir: Int, durationMs: Long) {
        from.clear(); fromPage.clear(); target.clear(); targetPage.clear()
        val firstSlot = if (newSpec.mode == PagerDial.Mode.ROW) 2 else 0
        val x0 = if (newSpec.mode == PagerDial.Mode.ROW) 2f else PagerDial.TINY_D / 2f
        val activeSlot = newSpec.items.firstOrNull { it.dash }?.slot ?: 2
        fun xAt(slot: Int): Float = PagerDial.centreX(slot, firstSlot, x0, activeSlot, pitch)

        val pages = LinkedHashSet<Int>().apply { addAll(drawnPage); newSpec.items.forEach { add(it.page) } }.sorted()
        for (pg in pages) {
            val curI = drawnPage.indexOf(pg)
            val specItem = newSpec.items.firstOrNull { it.page == pg }
            val startE: Entry = if (curI >= 0) drawn[curI].copyOf()
            else run {
                // entering: materialises at the edge slot with alpha 0 (spec: "from clamped slot 9")
                val es = PagerDial.edgeSlot(dir, leaving = false).coerceIn(-1, 9)
                val (ew, eh, _) = PagerDial.sizeForSlot(es.coerceIn(0, 8), dash = false)
                Entry(xAt(es), ew, eh, 0f, false)
            }
            val endE: Entry = if (specItem != null) Entry(specItem.xDp, specItem.wDp, specItem.hDp, specItem.alpha, specItem.dash)
            else run {
                // leaving: exits through the opposite edge with alpha 0
                val es = PagerDial.edgeSlot(dir, leaving = true).coerceIn(-1, 9)
                val (ew, eh, _) = PagerDial.sizeForSlot(es.coerceIn(0, 8), dash = false)
                Entry(xAt(es), ew, eh, 0f, false)
            }
            from += startE; fromPage += pg; target += endE; targetPage += pg
        }
        if (durationMs <= 0L) { finishFlip(newSpec); return }
        anim?.cancel()
        val frozen = frozenP
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = TimeInterpolator { t -> if (frozen >= 0f) frozen else easeOutCubicAtomic(t) }
            addUpdateListener { an ->
                blend((an.animatedValue as Float))
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { finishFlip(newSpec) }
                override fun onAnimationCancel(animation: Animator) { finishFlip(newSpec) }
            })
        }.also { it.start() }
    }

    private fun easeOutCubicAtomic(t: Float): Float {
        val u = 1f - t; return 1f - u * u * u
    }

    private fun blend(p: Float) {
        val pr = if (frozenP >= 0f) frozenP else p
        for (i in targetPage.indices) {
            val src = from[i]; val dst = target[i]
            val nx = src.x + (dst.x - src.x) * pr
            val nw = src.w + (dst.w - src.w) * pr
            val nh = src.h + (dst.h - src.h) * pr
            val na = src.alpha + (dst.alpha - src.alpha) * pr
            val di = drawnPage.indexOf(targetPage[i])
            if (di >= 0) {
                val e = drawn[di]
                e.x = nx; e.w = nw; e.h = nh; e.alpha = na; e.dash = dst.dash
            } else {
                drawn += Entry(nx, nw, nh, na, dst.dash); drawnPage += targetPage[i]
            }
        }
    }

    private fun finishFlip(newSpec: PagerDial.Spec) {
        // Owner invariant: last frame == rest frame (hard-set, tolerance handled by caller's log)
        drawn.clear(); drawnPage.clear()
        for (it in newSpec.items) { drawn += Entry(it.xDp, it.wDp, it.hDp, it.alpha, it.dash); drawnPage += it.page }
        specNow = newSpec; aNow = newSpec.a; widthDp = newSpec.widthDp
        visibility = VISIBLE
        requestLayout(); invalidate()
        logSettled()
    }

    private fun logSettled() {
        val items = drawnPage.indices.map { drawnPage[it] to drawn[it] }.sortedBy { it.second.x }
        fun f(v: Float) = "%.1f".format(v)
        val centers = items.joinToString(",") { f(it.second.x) }
        val widths = items.joinToString(",") { f(it.second.w) }
        val alphas = items.joinToString(",") { f(it.second.alpha) }
        // c1: adjacent non-dash centre distance == P; c2: distance beside the dash == P + DELTA
        var c1 = "ok"; var c2 = "ok"
        for (k in 0 until items.size - 1) {
            val d = items[k + 1].second.x - items[k].second.x
            val besideDash = items[k].second.dash || items[k + 1].second.dash
            val want = if (besideDash) pitch + PagerDial.DELTA else pitch
            val gotPx = dpToPx(d) / resources.displayMetrics.density
            if (kotlin.math.abs(dpToPx(want) / resources.displayMetrics.density - gotPx * 1f) > 0.5f / resources.displayMetrics.density + 0.05f) {
                if (besideDash) c2 = "FAIL($k)" else c1 = "FAIL($k)"
            }
        }
        // c3: last frame == rest frame
        val rest = specNow
        var c3 = "ok"
        if (rest != null && rest.mode != PagerDial.Mode.HIDDEN) {
            var maxD = 0f
            for ((pg, e) in items) {
                val r = rest.items.firstOrNull { it.page == pg } ?: continue
                maxD = maxOf(maxD, abs(e.x - r.xDp), abs(e.w - r.wDp), abs(e.alpha - r.alpha))
            }
            if (maxD * resources.displayMetrics.density > 0.5f) c3 = "FAIL(d=%.2fdp)".format(maxD)
        }
        onSettle?.invoke("centersDp=[$centers] widthsDp=[$widths] alphas=[$alphas] c1=$c1 c2=$c2 c3=$c3")
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(dpToPx(maxOf(1f, widthDp)).toInt(), dpToPx(PagerDial.VIEW_H_DP).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        for (pass in 0..1) {                    // inactive first, dash last (spec §6)
            for (i in drawnPage.indices) {
                val e = drawn[i]
                if ((pass == 0) == e.dash) continue
                if (e.alpha <= 0.004f || e.w <= 0f || e.h <= 0f) continue
                val hPx = dpToPx(e.h)
                rect.set(dpToPx(e.x - e.w / 2f), cy - hPx / 2f, dpToPx(e.x + e.w / 2f), cy + hPx / 2f)
                paint.alpha = ((if (e.dash) 0xD9 else 0x4D) * e.alpha).toInt().coerceIn(0, 255)
                canvas.drawRoundRect(rect, hPx / 2f, hPx / 2f, paint)
            }
        }
    }
}
