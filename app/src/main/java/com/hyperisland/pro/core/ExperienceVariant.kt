package com.hyperisland.pro.core

/** b1455 Phase-1: the variant experiment as pure data. NO view code lives here, so nothing in this file
  * can touch the hierarchy - the service only READS these numbers. Sources of each knob:
  *  - CLAUDE = claude-sonnet-5-high audit (vibrant extraction, layered blur bloom, 110ms/6% gulp + haptic)
  *  - SOL    = gpt-6-sol audit (2-layer glow stops, 145ms 3-keyframe gulp, pager slot-rebind + drag progress,
  *             end-frame apply-not-restore handshake, gone-if-absent sparse card)
  *  - MIX    = agreed overall-best: Sol's structure + Claude's colour/haptic touches.
  * Anything the audits got WRONG against HEAD (headH pin already gated, shader-key theory, "nothing GONE")
  * is deliberately NOT represented here. */
enum class ExperienceVariant { CLAUDE, SOL, MIX;

    companion object {
        fun parse(s: String?): ExperienceVariant = when (s?.lowercase()) {
            "claude" -> CLAUDE
            "sol" -> SOL
            else -> MIX   // default is the mix - the agreed overall best
        }
    }
}

/** Glow rendering knobs. Colours themselves come from updateAmbientGlow at runtime - these are shapes. */
data class GlowSpec(
    val twoLayer: Boolean,          // SOL: core + aura; CLAUDE: one layered bloom
    val coreRadiusDp: Int,
    val auraRadiusDp: Int,
    val auraOffsetYDp: Int,         // SOL: light pools _beneath_ the icon
    val coreAlpha: Float,           // 0..1 paint alpha for the core stop
    val auraAlpha: Float,
    val stopFractions: FloatArray,  // monotonic 0..1
    val stopAlphaMul: FloatArray,   // SOL: [1, .65, .16, 0]; CLAUDE: [1, .55, .22, 0] at [0,.45,1] (+blur)
    val blurPx: Int,                // CLAUDE: 18 PX (not dp - his literal "blur 18px"); SOL: 0
)

/** Post-collapse absorb pulse. One pulse, one settle: no glow/text changes accompany it. */
data class GulpSpec(
    val durationMs: Int,
    val times: FloatArray,          // milliseconds, ascending, first 0 last = durationMs
    val scaleX: FloatArray,
    val scaleY: FloatArray,         // contract first (absorb), then one rebound, then 1.0
    val hapticAtMs: Int,            // CLAUDE/MIX: tick during the absorb; SOL: no haptic
)

/** Pager indicator knobs: drag-progress interpolation, full slot rebind vs translated capsule, a11y label. */
data class PagerSpec(
    val rebindAllSlots: Boolean,    // SOL: rebuild slot states on index change instead of sliding one view
    val dragProgressLive: Boolean,  // SOL: indicator follows finger mid-drag, not only at settle
    val talkBackLabel: Boolean,     // announce "page N of M" absolutely
)

/** Morph end handshake: apply the evaluated end-frame instead of resetting to a different base, and log
  * the invariant |last animated transform - first idle transform| for device proof. */
data class EndHandshakeSpec(val applyEndFrame: Boolean, val logInvariant: Boolean)

/** Sparse card rule: invisible rows own no space, margins exist only between two visible rows. */
data class SparseSpec(val goneIfAbsent: Boolean, val marginsVisibleOnly: Boolean)

data class ExperienceProfile(
    val glow: GlowSpec,
    val gulp: GulpSpec,
    val pager: PagerSpec,
    val handshake: EndHandshakeSpec,
    val sparse: SparseSpec,
)

object ExperienceProfiles {

    val CLAUDE = ExperienceProfile(
        glow = GlowSpec(
            twoLayer = false, coreRadiusDp = 38, auraRadiusDp = 0, auraOffsetYDp = 0,   // his literal: radius = 1.6 x max(iconW, iconH) = 1.6 x 24dp
            coreAlpha = 1.00f, auraAlpha = 0f,   // his stops [.55,.22,0] are the FINAL alphas, not multipliers of a base
            stopFractions = floatArrayOf(0f, 0.45f, 1f),
            stopAlphaMul = floatArrayOf(0.55f, 0.22f, 0f),   // claude's own numbers: 0f->55%a, .45f->22%a, 1f->0 (must reach zero at the edge; a hard disc rim is exactly the "cheap" look)
            blurPx = 18,
        ),
        gulp = GulpSpec(
            durationMs = 110,
            // his literal MotionVariant.spring(t, 110ms, response=0.09, damping=0.55) sampled every few ms,
            // mapped as 0.94 + 0.06*spring: 6% absorb, spring's 12.6% overshoot -> +0.76% rebound, one settle.
            // Peak of the spring is at t=0.49 (54ms) = 1.1263 -> scale 1.0076.
            times = floatArrayOf(0f, 13f, 28f, 42f, 54f, 73f, 88f, 110f),
            scaleX = floatArrayOf(0.940f, 0.958f, 0.987f, 1.004f, 1.0074f, 1.0039f, 1.0004f, 1.00f),
            scaleY = floatArrayOf(0.940f, 0.958f, 0.987f, 1.004f, 1.0074f, 1.0039f, 1.0004f, 1.00f),
            hapticAtMs = 35,
        ),
        pager = PagerSpec(rebindAllSlots = false, dragProgressLive = false, talkBackLabel = false),
        handshake = EndHandshakeSpec(applyEndFrame = false, logInvariant = true),
        sparse = SparseSpec(goneIfAbsent = false, marginsVisibleOnly = false),
    )

    val SOL = ExperienceProfile(
        glow = GlowSpec(
            twoLayer = true, coreRadiusDp = 38, auraRadiusDp = 78, auraOffsetYDp = 13,
            coreAlpha = 0.10f, auraAlpha = 0.045f,
            stopFractions = floatArrayOf(0f, 0.32f, 0.72f, 1f),
            stopAlphaMul = floatArrayOf(1f, 0.65f, 0.16f, 0f),
            blurPx = 0,
        ),
        gulp = GulpSpec(
            durationMs = 145,
            times = floatArrayOf(0f, 48f, 105f, 145f),
            scaleX = floatArrayOf(1.00f, 0.955f, 1.025f, 1.00f),
            scaleY = floatArrayOf(1.00f, 0.890f, 1.040f, 1.00f),
            hapticAtMs = 0,
        ),
        pager = PagerSpec(rebindAllSlots = true, dragProgressLive = true, talkBackLabel = true),
        handshake = EndHandshakeSpec(applyEndFrame = true, logInvariant = true),
        sparse = SparseSpec(goneIfAbsent = true, marginsVisibleOnly = true),
    )

    /** agreed overall best: Sol's structure; Claude's colour + haptic touches. */
    val MIX = SOL.copy(
        glow = SOL.glow.copy(blurPx = 18),       // Claude's bloom over Sol's two-layer pool
        gulp = SOL.gulp.copy(hapticAtMs = 35),   // one tick during the absorb, Claude's idea
    )

    fun of(v: ExperienceVariant): ExperienceProfile = when (v) {
        ExperienceVariant.CLAUDE -> CLAUDE
        ExperienceVariant.SOL -> SOL
        ExperienceVariant.MIX -> MIX
    }
}
