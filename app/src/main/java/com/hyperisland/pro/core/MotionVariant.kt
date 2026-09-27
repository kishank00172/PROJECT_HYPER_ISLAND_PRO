package com.hyperisland.pro.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The motion laws the two outside designs asked for, in one Android-free object.
 *
 * Two of those designs reached for `androidx.dynamicanimation`'s `SpringAnimation` and for
 * `lp.width = value; view.requestLayout()` on every frame. Both are refused here on purpose, and for the same
 * reason: this app has one dependency (junit) and one rule its whole morph is built on - the card view is laid
 * out **once** at its final size and the growth is *drawn* (`IslandMorphFrame`). A per-frame layout of a
 * screen-sized overlay window is what used to produce "text upar-neeche hilta hai, fir set hota hai". So a
 * spring lives here as what it actually is - a function of normalized time - which also means CI can assert on
 * frame 3 of 20 instead of hoping a live animation settles the way the doc says it does.
 */
object MotionVariant {

    /**
     * The largest valid `KEY_MORPH_STYLE`.
     *
     * This number exists because of a real, user-visible bug: when the fourth style was added, `AppSettings`'
     * getter and setter were still clamping the value into the old 0..2 range, so choosing radio #4 stored
     * style 2 - and the tester's verdict "4th mei kuchh to alag hai he nahi, 3rd jaisa he to hai" was
     * describing the truth. A style list that cannot reach its last entry is not a list. Every style now goes
     * through [clampStyle], and `MotionVariantTest` fails the build if any constant is outside it.
     */
    const val MAX_STYLE = 6
    const val MIN_STYLE = 0

    /**
     * The shortest morph this app runs (`dur=340ms` in the stage animators, straight out of b1406's trace). It
     * constrains the response table below, and the reason it exists as a constant is his round-27 verdict
     * "dono new options ek he hai": with Claude's 0.42 s response inside a 0.38 s animation, the spring never
     * reaches its target while it is running, so the interpolator pins the last frame to 1.0 - and the bounce,
     * the settle, and the entire reason to use a spring are cut off at the door. What is left on screen is a
     * slightly different ease. Which is what he saw.
     */
    const val MIN_MORPH_WINDOW_MS = 340L
    /** How much of the window a spring needs to reach its target and come back: 1.35 responses. */
    const val SPRING_SETTLE_FACTOR = 1.35f

    fun clampStyle(v: Int): Int = v.coerceIn(MIN_STYLE, MAX_STYLE)

    /**
     * How far the drawn box has come between the two sizes, measured on the axis that moves more. Geometry
     * rather than the clock, on purpose: a spring overshoots, so the clock would already be at 1.0 while the
     * shape is still on its way back, and every phase keyed to it (the gate, the squeeze, the ripple) would
     * fire in the wrong place. Out-of-range input is clamped, because a clamped spring at t=1 is 1.0.
     */
    fun progressOf(fromW: Int, fromH: Int, toW: Int, toH: Int, w: Int, h: Int): Float {
        val dw = (toW - fromW).toFloat()
        val dh = (toH - fromH).toFloat()
        if (dw == 0f && dh == 0f) return 1f
        return if (abs(dw) >= abs(dh)) ((w - fromW) / dw) else ((h - fromH) / dh)
    }

    /**
     * What each style is called, in the words the Lab prints and the [MORPH] trace logs. It lives here rather
     * than in `AppSettings` so a JVM test can read it: the naming of a style is part of its definition, and the
     * bug this file exists to prevent was exactly a name pointing at the wrong behaviour.
     */
    fun styleName(style: Int): String = when (clampStyle(style)) {
        AppSettings.MORPH_STYLE_CARRY -> "ride only (b1378)"
        AppSettings.MORPH_STYLE_SHAPE_ONLY -> "scale + fade only"
        AppSettings.MORPH_STYLE_GLASS -> "glass settle"
        AppSettings.MORPH_STYLE_LIQUID -> "liquid capsule (Claude)"
        AppSettings.MORPH_STYLE_HYPERMORPH -> "hypermorph (ChatGPT)"
        AppSettings.MORPH_STYLE_BLUEPRINT -> "precise snap, organic breath (aapka v2)"
        else -> "balanced"
    }

    /** A percent slider that cannot smuggle in a nonsense value; shared by every new knob in the Lab. */
    fun clampPct(v: Int, lo: Int, hi: Int): Int = v.coerceIn(lo, hi)

    fun isSpring(style: Int): Boolean =
        style == AppSettings.MORPH_STYLE_LIQUID || style == AppSettings.MORPH_STYLE_HYPERMORPH ||
            style == AppSettings.MORPH_STYLE_BLUEPRINT

    // ---------------------------------------------------------------- the spring, as a curve

    /**
     * The fraction of the distance covered at normalized time [t] for an animation [durationMs] long, with
     * Apple's two designer-facing parameters instead of mass/stiffness/damping: [response] (how quickly the
     * target is reached, in seconds) and [damping] (1.0 = no bounce; below it, the element overshoots and
     * comes back). Closed form for the underdamped case, critical for [damping] at 1, and pinned to exactly 1
     * at the end - the settle must land on the target size or the card is left visibly wrong.
     */
    fun spring(t: Float, durationMs: Long, response: Float, damping: Float): Float {
        if (t <= 0f) return 0f
        if (t >= 1f) return 1f
        val seconds = durationMs.coerceAtLeast(1L) / 1000f
        val time = t * seconds
        val w0 = (2f * PI.toFloat()) / response.coerceAtLeast(0.05f)
        val zeta = damping.coerceIn(0.05f, 1f)
        if (zeta >= 0.999f) {
            val e = exp(-w0 * time)
            return 1f - e * (1f + w0 * time)
        }
        val wd = w0 * sqrt(1f - zeta * zeta)
        val e = exp(-zeta * w0 * time)
        return 1f - e * (cos(wd * time) + (zeta * w0 / wd) * sin(wd * time))
    }

    /**
     * How far past the target an underdamped spring of this damping goes, as a fraction. The pin bound has to
     * be widened by exactly this much or the overshoot is clipped away - which is the specific way an elastic
     * design dies inside this app (a previous round measured 29 of 46 frames moving the box by 0.0 % because
     * the curve's work was being clamped).
     */
    /**
     * The axis rule, as a function so it can be tested instead of trusted: the box's width only ever *arrives*.
     * On the way out it stops at the card's width, on the way back at the pill's - so no frame of a morph can put
     * the island's edge where the eye cannot follow it, and the content row anchored to that edge cannot be made
     * to slide sideways. The curve still overshoots; the width simply does not spend it. [SPRING_AXIS].
     */
    fun axisWidth(w: Int, targetW: Int, growing: Boolean): Int =
        if (growing) minOf(w, targetW) else maxOf(w, targetW)

    fun peakOvershoot(damping: Float): Float {
        if (damping >= 0.999f) return 0f
        val z = damping.coerceIn(0.05f, 0.999f)
        return exp(-z * PI.toFloat() / sqrt(1f - z * z))
    }

    // ---------------------------------------------------------------- Claude: the gate, the radius, the edge

    /**
     * Claude's rule, in words: "jab tak capsule ~60% target width pe nahi pahunchta, text/icon crossfade shuru
     * hi na ho". So the content does not compete with the shape for attention, and text is never shown at a
     * width that clips it. Returns a 0..1 progress that starts at [gate].
     */
    fun gated(t: Float, gate: Float): Float {
        val g = gate.coerceIn(0f, 0.95f)
        return ((t - g) / (1f - g)).coerceIn(0f, 1f)
    }

    const val DEFAULT_GATE = 0.6f

    /**
     * ChatGPT's shape rule - "radius independently animate mat karo, `cornerRadius = height / 2`" - which keeps
     * a growing capsule a capsule instead of a rectangle with a separately-timed corner. Clamped to whatever
     * the designer asked for at the end, so a non-capsule target (a card with 22 dp corners) still lands there.
     */
    fun tensionRadius(heightPx: Float, wantedPx: Float): Float = minOf(heightPx / 2f, wantedPx)


    // ---------------------------------------------------------------- ChatGPT: the phases

    /**
     * Phase A, "compression": 30-45 ms of squeeze before the bloom, so the container looks like it is loading
     * tension rather than being resized. A triangle in time, peaking at [window] and gone by 2x[window], so
     * there is no step when it ends. [amount] is the fraction (0.03 = 97 % width).
     */
    fun compression(t: Float, window: Float, amount: Float): Float {
        val w = window.coerceIn(0.02f, 0.9f)
        if (t <= 0f || t >= 2f * w || amount <= 0f) return 0f
        val tri = if (t < w) t / w else (2f * w - t) / w
        return tri * amount
    }

    /** His numbers exactly: width 97 %, height 106 % at the peak of a 3 % compression. */
    fun compressedWidth(t: Float, window: Float, amount: Float): Float = 1f - compression(t, window, amount)

    /** ...and the height is the mirror image at twice the magnitude, which is what keeps area roughly held. */
    fun compressedHeight(t: Float, window: Float, amount: Float): Float = 1f + 2f * compression(t, window, amount)

    /**
     * Phase D, "micro-settle" (100 -> 102 -> 100, "elastic, not trampoline"): deliberately not a second
     * animation. It is what an underdamped [spring] does on its own, and [peakOvershoot] says by how much, so
     * the two cannot disagree the way a manual bump plus a settle always do.
     */
    /**
     * Phase A's window as a fraction of the morph, and why it is 0.22 rather than the 30-45 ms he wrote: his
     * panel is running at 60 Hz (`hz=60` in his own trace), so 40 ms is two frames - at that length the squeeze
     * is not a feel, it is a dropped frame with a scale on it. 0.22 of 380 ms is 84 ms, five frames, and the
     * compression can actually be seen to happen.
     */
    /**
     * The three envelope windows are fractions of the CLOCK. This is a correction, not a preference: in b1411
     * they were read off the shape's progress, and a spring's progress is front-loaded - 0 -> 0.11 -> 0.32 in
     * the first two frames - so a "30-45 ms" compression got sampled once, past its peak, and the ripple became a
     * single blurred frame. A duration is a duration; measure it on the clock. (The gate stays on the shape,
     * because it is Claude's "~60% of target width" - a position, not a time.)
     */
    const val COMPRESSION_WINDOW = 0.20f
    const val DEFAULT_SQUEEZE = 0.05f

    /**
     * Phase D, "micro-settle" - 100 -> 102 -> 100 - as a small explicit pulse over the last [window] of the
     * travel, sized by [amount]. It is not left to the spring's tail on purpose: his own two numbers disagree,
     * because a spring at damping 0.86 overshoots by about half a percent, and half a percent of a 900 px box
     * is four pixels nobody can feel. A sine, so the pulse starts at zero and *ends* at zero - the size the
     * morph lands on has to be the size the card is.
     */
    fun microSettle(p: Float, window: Float, amount: Float): Float {
        val w = window.coerceIn(0.02f, 0.9f)
        if (amount <= 0f || p < 1f - w) return 0f
        return amount * sin(PI.toFloat() * ((p - (1f - w)) / w))
    }

    const val SETTLE_WINDOW = 0.30f
    const val DEFAULT_SETTLE = 0.025f

    /**
     * The signature idea from the second design: on the way back the content is not interpolated to the pill,
     * it is **pulled** into it - attraction rising as the anchors get closer, so the last frames snap. A power
     * curve is that shape with one parameter: [pull] 0 is linear (the current behaviour), 1 is p^2.
     */
    fun magnetic(t: Float, pull: Float): Float = t.coerceIn(0f, 1f).pow(1f + pull.coerceIn(0f, 2f))

    const val DEFAULT_MAGNET = 0.6f

    /**
     * "Energy ripple": a surface wave over the first [window] of the morph, `sin()` so it starts at zero and
     * returns to zero - no seam at either end, which is the only way a 40-70 ms effect stays "felt, not seen".
     */
    fun ripple(t: Float, window: Float): Float {
        val w = window.coerceIn(0.02f, 0.9f)
        if (t <= 0f || t >= w) return 0f
        return sin(PI.toFloat() * t / w)
    }

    const val RIPPLE_WINDOW = 0.20f
    /** The ripple's blur as a fraction of the glass style's, because "feel it, not see it" is a fraction and not
     * a switch: at full glass strength the text visibly fogs, which is a second effect on the same element - and
     * running it on a collapse too, as b1406 did, is a blur flash at the start of every return. */
    const val RIPPLE_BLUR_FRACTION = 0.45f

    // ---------------------------------------------------------------- the buoyancy the tester asked for by name

    /**
     * How much slower the CONTENT floats than the box, and with how little damping. His round-33 words, after
     * a snappy-profile run where the box had no bounce left to share: "pill se nikla hua content aise feel
     * nahi aata, content sidha aa jata hai - buoyancy wala effect do, jaise content liquid mein hai aur expand
     * ne uspe asar daala." The spring styles' ride used to track the shape 1:1, so a critically-damped box
     * (damping 1.00 at his saved profile) made the text walk a stiff diagonal: perfectly on time, and dead.
     * The content now travels on its OWN spring: a third slower, and underdamped, so the box arrives first,
     * the content trails in like something suspended in it, overshoots its rest by a few pixels and floats
     * back. It is a property of the spring STYLES, not of his motion profile - at snappy the box stays crisp
     * and the content still floats, which is the whole point of calling the style "liquid".
     */
    const val BUOY_RESPONSE_SCALE = 1.30f
    const val BUOY_DAMPING = 0.55f

    /**
     * The content's buoyant progress. Landed on [spring] so the guarantee that matters is inherited, not
     * re-proven: exactly 0 at the start and exactly 1 at [durationMs], so the ride cannot snap at the
     * hand-off - the lag and the float all live strictly inside the window. Mid-flight it is behind the box
     * and occasionally past 1 (the dip), by [peakOvershoot] on a 0.55 damping.
     */
    fun buoy(t: Float, durationMs: Long, baseResponseSec: Float): Float =
        spring(t, durationMs, baseResponseSec * BUOY_RESPONSE_SCALE, BUOY_DAMPING)

    // -------------------------------------- blueprint v2: "Precise Snap, Organic Breath" (his sent spec)

    /**
     * Blueprint v2, verbatim constants from the final spec he sent ("numbers AI-processed, test ke baad
     * tune karunga" - constants stay one [const val] each, for that exact day). The pull/float family of
     * rounds 34-35 is REPLACED by this: the pendulum, the piecewise anchor path, the icon at half buoyancy
     * and 40 ms late, the hard lock, and the one haptic are all read from here.
     */
    const val V2_EXPAND_TOTAL_MS = 950L
    const val V2_COLLAPSE_TOTAL_MS = 380L
    // Round 43-1: the container "neck" (his formula inset(y)=maxInset*sin(PI*y^1.4), active 40-300 ms,
    // envelope sin peak at the window's centre = 170 ms; maxInset = 5% of the current drawn width,
    // "previously tried 9% - too strong"). Top edge full width always; it REPLACES the uniform squeeze
    // for the v2 expand (same slot, better silhouette - no double-narrowing).
    // Round 45 (his screenshot evidence - torn-paper silhouette): DISABLED at runtime. Autopsy: the
    // round-43 path builder distributed its 20 profile steps across the straight band (h - 2*radius);
    // round 44 then made v2's radius the capsule rule (h/2), shrinking that band to ~0 px - 21 profile
    // points collapsed into a 1-px strip and the corner quads swept from mid-height, which rasterises
    // as exactly the jagged, asymmetric edge he photographed. Two rounds, never geometrically co-tested -
    // triage entry recorded. The math stays pinned + unit-tested; the silhouette path gains a degenerate-
    // band guard; re-enabling gets its own round with device screenshot proof, not a drive-by.
    const val V2_NECK_ENABLED = false
    const val V2_NECK_MAX_INSET = 0.05f
    const val V2_NECK_START_MS = 40L
    const val V2_NECK_END_MS = 300L
    // Round 43-3: the post-lock "confirm gulp" on the pill itself - a SEPARATE 220 ms pulse after the
    // collapse's hard lock (the collapse curve itself stays crisp/zero-overshoot, untouched). Volume-constant
    // keyframes for sy/sx live in the service next to the animator that runs them.
    const val V2_GULP_MS = 220L   // "380 LOCK62- ??FIGMMo??" -> ~380 ms total, spring r=0.30 s
    const val V2_CONTAINER_SQUEEZE = 0.04f   // round 42 arm, retired at runtime in round 43: the same
    // window + purpose is owned by V2_NECK_* below with a per-y inset profile (uniform squeeze cannot
    // express "zero at the icon edge"); kept pinned for lore and for non-v2 experiments.   // round 42 (his order: enable+tune the sleeping squeeze, 3-5%
    // sane band read at 4%): the WIDTH-side voice of the vertical bounce - the same volume-constant symmetry the
    // text overlay already performs (sy up <-> sx down). Windowed on rawT, peak 0.15 ~= real 142 ms so the eye
    // reads ONE settle beside the height apex at ~140 ms, fully released by rawT 0.30 (pre-lock).
    const val V2_COLLAPSE_CONTENT_SINK = 0.08f   // round 43 (his order): synced with the container's OWN
    // 380 ms shrink curve, 1.00 -> 0.92, monotone; the dedicated alpha fade is GONE for the v2 collapse -
    // clip-only ("content retreating into the shrinking pill", the existing dispatchDraw containment clip
    // doing the cutting), so the scale speaks alone instead of racing a fade.   // round 42-2 (his: collapse has ZERO content motion - log truth:
    // ty=0/sy=1.000 all the way): a monotone crisp settle-in, scale 1.00 -> 0.96, no overshoot, keyed to the
    // collapse spring's own monotone output coerced - a shrink that LOOKS intentional instead of flat.
    const val V2_CONTAINER_RESPONSE = 0.22f  // ~4 px peak overshoot at 421 dp, fully locked by 0.337 t (320 ms)
    const val V2_CONTAINER_DAMPING = 0.62f   // b1440's logged evidence: 0.78's +6.3 px height (~2.5 dp) and +14 px
    // width were masked by the content's 35 px move at the same instant; his band (0.60-0.65) read at 0.62 =>
    // 8.4% of travel: +26.6 px on height at real ~140 ms, pre-320-lock caught, independent of the content's arc.   // his binding order over the constant-vs-table clash: 0.78 gives
    const val V2_COLLAPSE_RESPONSE = 0.30f
    const val V2_COLLAPSE_DAMPING = 0.90f    // "crisp, no bounce"; settles and pins inside the 380 ms clock
    const val V2_STRETCH_END = 0.26f         // stretch owns ONLY the pull window (0 - 247 ms), fading out after

    /**
     * The pendulum, read as anchors - (fraction of clock, px offset, gravity of zero velocity at extremes).
     * His text row, verbatim: -40 start, +10 dp sink (26 px) at lock, -5 / +2 bounce pair, 0 by the rigid zone.
     * Between anchors, half-cosine arcs: an oscillating system's extremes are where velocity is zero, so
     * piecewise-halved cosines BETWEEN extremes are exactly the smooth motion he drew, not an approximation.
     */
    private val V2_TEXT_ANCHORS = floatArrayOf(0f, -40f, 0.40f, 26f, 0.589f, -5f, 0.758f, 2f, 0.90f, 0f)

    /** Offset piecewise: cos half-arcs between (t_i, y_i) anchors. Between 0 and the first anchor: the pull
     *  window also glides with the expansion; after the last anchor: rigid zero, by definition. */
    private fun anchoredSegments(t: Float, anchors: FloatArray): Float {
        if (t <= 0f) return anchors[1]
        val n = anchors.size / 2
        for (i in 0 until n - 1) {
            val t0 = anchors[2 * i]; val y0 = anchors[2 * i + 1]
            val t1 = anchors[2 * i + 2]; val y1 = anchors[2 * i + 3]
            if (t <= t1) {
                val u = if (t1 > t0) ((t - t0) / (t1 - t0)).coerceIn(0f, 1f) else 1f
                val w = (1f - cos(PI.toFloat() * u)) / 2f
                return y0 + (y1 - y0) * w
            }
        }
        return anchors[anchors.size - 1]
    }

    /** Text column offset in px at clock t. */
    fun v2TextOffsetPx(t: Float): Float = anchoredSegments(t, V2_TEXT_ANCHORS)

    /**
     * The icon rides the same pendulum at exactly half amplitude, delayed by 40 ms (his ICON_DELAY_MS) -
     * and the delayed piece is where a naive ratio would eat his constants, so it lives in the GATE, not
     * in the offset: u = t - delay; before the delay the icon does exactly what he wrote: rigid at its seat.
     */
    fun v2IconOffsetPx(t: Float): Float {
        val delay = 40f / V2_EXPAND_TOTAL_MS
        val u = (t - delay).coerceIn(0f, 1f)
        return 0.5f * v2TextOffsetPx(u)
    }

    /** Stretch ring on the pull: identical bell shape to before, mapped to his 0-247 ms window. */
    fun v2StretchScaleY(t: Float): Float = 1f + 0.15f * sin(PI.toFloat() * (t / V2_STRETCH_END).coerceIn(0f, 1f))
    fun v2StretchScaleX(t: Float): Float = 1f - 0.05f * sin(PI.toFloat() * (t / V2_STRETCH_END).coerceIn(0f, 1f))

    /** Haptic gate, exact single tick: his "Container Lock 0.337" boundary, fired once per expand. */
    fun v2HapticAt(t: Float): Boolean = t in 0.335f..0.345f

    // ---------------------------------------------------------------- the profiles Claude asked for

    /**
     * "Firebase-driven motion profiles": presets are the testable half of that idea and they are here; the
     * remote delivery of them is not, because this app has no backend yet and a build that phones home for its
     * feel is a build he cannot test offline. [MotionProfile] is one data class, so wiring a config service
     * into it later is plumbing and not a redesign.
     */
    const val PROFILE_SNAPPY = 0
    const val PROFILE_SILKY = 1
    const val PROFILE_BOUNCY = 2
    const val PROFILE_COUNT = 3

    fun clampProfile(v: Int): Int = v.coerceIn(0, PROFILE_COUNT - 1)

    fun profileName(v: Int): String = when (clampProfile(v)) {
        PROFILE_SNAPPY -> "snappy"
        PROFILE_SILKY -> "silky"
        else -> "bouncy"
    }

    /**
     * Response per trigger, from the first design's spec sheet (arrival ~0.35 s, tap-to-expand ~0.45 s,
     * auto-collapse ~0.30 s) read against the second design's numbers (240-280 ms out, 170-210 ms back). A
     * collapse is deliberately quicker than an expansion in both: opening says "I am opening", closing says
     * "back to business".
     */
    fun responseScaleFor(style: Int, profile: Int, towardCard: Boolean): Float {
        val p = clampProfile(profile)
        if (style == AppSettings.MORPH_STYLE_HYPERMORPH) {
            // GPT's own ratio: 0.20 s of response inside his 320 ms morph, 0.145 s inside the collapse.
            return if (towardCard) 0.62f else 0.45f
        }
        return when (p) {
            // Claude's three profiles, as his table gives them: reach and stop, one small settle, a real bounce.
            // Not ordered the way Claude's response column is, and that is deliberate: his table pairs a longer
            // response with a bouncier ratio, and the two together push the settle past the last frame (0.807 x
            // response / damping is the settle, and 0.38 s at zeta 0.5 needs 0.62 s of animation). The invariant
            // is "the spring comes home inside its window"; the response that satisfies it shrinks as the bounce
            // grows, so the profiles are ordered by amplitude, which is what he can see, not by response.
            PROFILE_SNAPPY -> if (towardCard) 0.85f else 0.45f
            PROFILE_BOUNCY -> if (towardCard) 0.70f else 0.55f
            else -> if (towardCard) 0.80f else 0.48f
        }
    }

    /**
     * [responseScaleFor] turned into seconds for the window this morph actually got, which is what [spring] wants.
     *
     * The scale, not a constant, because his duration slider runs 120-650 ms and the two designs' response numbers
     * were written next to their own durations (Claude: 0.30-0.34 s in a 380-450 ms morph; GPT: 0.20 s in 320 ms).
     * b1411 hard-coded the seconds and the window decided the rest; the ratio is the part that transfers, and a
     * constant would break as soon as he moves the slider.
     */
    fun responseFor(style: Int, profile: Int, towardCard: Boolean, windowMs: Long): Float =
        if (style == AppSettings.MORPH_STYLE_BLUEPRINT) {
            // Blueprint v2 bypasses the presets AND the slider: its constants are his.
            if (towardCard) V2_CONTAINER_RESPONSE else V2_COLLAPSE_RESPONSE
        } else windowMs * responseScaleFor(style, profile, towardCard) / 1000f

    /**
     * Damping per trigger. Claude's table says a light bounce on arrival (0.7), a confident settle on a tap
     * (0.85) and **no** bounce on an auto-collapse (1.0) - a lid going down should not wobble. ChatGPT's bloom
     * wants 0.82-0.9 and its collapse 0.9-ish. The classic styles stay 1.0, which is another way of saying
     * "nothing about them changes".
     */
    /**
     * The only axis an island can spring on: **down**. The surface is pinned to the top edge of the screen and
     * grows into the space below it, so it has thousands of pixels of room one way and, on a phone whose card is
     * 1067 px wide on a 1080 px panel, thirteen px the other way. A horizontal overshoot is not a style choice
     * here - it is a rectangle drawn off-screen. Both outside designs asked for it anyway (Claude's 1.02-1.08 on
     * the capsule, ChatGPT's 100 -> 102 -> 100 on the container), because both were written for a Dynamic Island
     * that floats with air on all four sides. Their timing, gate, squash and staged return all transfer; the
     * horizontal bounce does not, and this constant is where that decision lives.
     */
    const val SPRING_AXIS = "down"

    fun dampingFor(style: Int, profile: Int, towardCard: Boolean): Float = when {
        AppSettings.MORPH_STYLE_BLUEPRINT == style && towardCard -> V2_CONTAINER_DAMPING
        AppSettings.MORPH_STYLE_BLUEPRINT == style -> V2_COLLAPSE_DAMPING
        style == AppSettings.MORPH_STYLE_LIQUID && !towardCard -> 1f
        style == AppSettings.MORPH_STYLE_LIQUID -> when (clampProfile(profile)) {
            // Claude wrote 0.85 for a tap-to-expand, which overshoots by 0.7 % - and the axis decides whether
            // that is a feel or a rounding error. The box's width may not overshoot at all now (see
            // [SPRING_AXIS]), so the whole amplitude lives on the height, where his card is 421 px instead of
            // 1067: the same ratios would have shrunk the motion threefold. These are the ratios that put the
            // three presets back at 1 px, 8 px and 40 px of vertical travel, which at 120 Hz are ~0, ~2 and
            // ~14 frames of visible movement. The presets are the amplitude, and the amplitude is what he has
            // to be able to name.
            // The axis moved (see `SPRING_AXIS` in the service), so the yardstick moved: the overshoot is now
            // measured against a 421 px height instead of a 1067 px width, which means the same damping ratios
            // would produce a third of the pixels. These are the ratios that put the three presets back at
            // 0 / 8 / 40 px of *vertical* travel on his card, and they still settle inside his window.
            PROFILE_SNAPPY -> 0.94f
            PROFILE_BOUNCY -> 0.60f
            else -> 0.78f
        }
        style == AppSettings.MORPH_STYLE_HYPERMORPH -> if (towardCard) 0.84f else 0.95f
        else -> 1f
    }
}
