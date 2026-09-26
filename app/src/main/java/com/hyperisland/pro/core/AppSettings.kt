package com.hyperisland.pro.core

import android.content.Context

object AppSettings {
    private const val PREF_NAME = "hyper_island_settings"
    private const val KEY_DEVELOPER_MODE = "developer_mode"
    private const val KEY_ISLAND_ENABLED = "island_enabled"
    private const val KEY_OVERLAY_ENGINE = "overlay_engine"
    private const val KEY_DEFAULTS_VERSION = "defaults_version"

    private const val KEY_ISLAND_WIDTH_DP = "island_width_dp"
    private const val KEY_ISLAND_HEIGHT_DP = "island_height_dp"
    private const val KEY_ISLAND_Y_DP = "island_y_dp"
    private const val KEY_ISLAND_X_DP = "island_x_dp"
    private const val KEY_MORPH_STYLE = "morph_style"
    private const val KEY_MOTION_PROFILE = "motion_profile"
    private const val KEY_MOTION_SQUEEZE = "motion_squeeze_pct"
    private const val KEY_MOTION_GATE = "motion_gate_pct"
    private const val KEY_MOTION_MAGNET = "motion_magnet_pct"
    private const val KEY_MORPH_CONTENT_SCALE = "morph_content_scale_pct"
    private const val KEY_MORPH_GLYPH_SWAP = "morph_glyph_swap_pct"
    private const val KEY_SHADE_POLICY = "shade_open_policy"
    private const val KEY_MORPH_CONTENT_DROP_DP = "morph_content_drop_dp"
    private const val KEY_MORPH_COUNT_ROLL = "morph_count_roll"
    private const val KEY_MORPH_ENTRY = "morph_content_entry"
    private const val KEY_MORPH_STAGGER_PCT = "morph_stagger_pct"
    private const val KEY_MORPH_GONE_BY_PCT = "morph_gone_by_pct"
    private const val KEY_ISLAND_CORNER_RADIUS_DP = "island_corner_radius_dp"

    private const val KEY_ISLAND_STAGE2_WIDTH_DP = "island_stage2_width_dp"
    private const val KEY_ISLAND_EXPANDED_WIDTH_DP = "island_expanded_width_dp"
    private const val KEY_ISLAND_EXPANDED_HEIGHT_DP = "island_expanded_height_dp"
    private const val KEY_ISLAND_EXPANDED_CORNER_RADIUS_DP = "island_expanded_corner_radius_dp"
    private const val KEY_REPLY_ANIMATION_MODE = "reply_animation_mode"
    private const val KEY_REPLY_LIQUID_HEIGHT_DP = "reply_liquid_height_dp"
    private const val KEY_REPLY_LIQUID_RADIUS_DP = "reply_liquid_radius_dp"
    private const val KEY_REPLY_LIQUID_LEFT_GAP_DP = "reply_liquid_left_gap_dp"
    private const val KEY_REPLY_LIQUID_EDGE_GAP_DP = "reply_liquid_edge_gap_dp"
    private const val KEY_PILL_ICON_RENDER_MODE = "pill_icon_render_mode"
    private const val KEY_SHADE_PULL_ANIMATION_MODE = "shade_pull_animation_mode"

    const val ENGINE_NONE = "none"
    const val ENGINE_ACCESSIBILITY = "accessibility"
    const val ENGINE_APPLICATION = "application"

    // Reply Morph Lab modes: 0/1 are legacy baselines, 2/3/4 use Ghost V2 renderer.
    const val REPLY_ANIM_CLASSIC_LAYOUT = 0
    const val REPLY_ANIM_GPU_SMOOTH = 1
    const val REPLY_ANIM_MAGNETIC_DOCK = 2
    const val REPLY_ANIM_LIQUID_FILL = 3
    const val REPLY_ANIM_ELASTIC_BUBBLE = 4
    const val REPLY_ANIM_MINIMAL_PRO = 5
    const val DEFAULT_REPLY_ANIMATION_MODE = REPLY_ANIM_LIQUID_FILL
    const val DEFAULT_REPLY_LIQUID_HEIGHT_DP = 44
    const val DEFAULT_REPLY_LIQUID_RADIUS_DP = 26
    const val DEFAULT_REPLY_LIQUID_LEFT_GAP_DP = 0
    const val DEFAULT_REPLY_LIQUID_EDGE_GAP_DP = 13

    const val PILL_ICON_AUTO = 0
    const val PILL_ICON_MANUAL_RESOURCE_NO_VALIDATION = 1
    const val PILL_ICON_MANUAL_RESOURCE_VALIDATED = 2
    const val PILL_ICON_LOAD_DRAWABLE_NO_VALIDATION = 3
    const val PILL_ICON_LOAD_DRAWABLE_VALIDATED = 4
    const val PILL_ICON_LEGACY_NO_VALIDATION = 5
    const val PILL_ICON_ADAPTIVE_NO_VALIDATION = 6
    const val PILL_ICON_LAUNCHER = 7
    const val PILL_ICON_GENERIC = 8
    const val DEFAULT_PILL_ICON_RENDER_MODE = PILL_ICON_AUTO // TestLab-selectable pill icon resolver for compact island

    const val SHADE_PULL_SIMPLE = 0
    const val SHADE_PULL_MY_ABSORB = 1
    const val SHADE_PULL_KIMI_VACUUM = 2
    const val SHADE_PULL_DEEPSEEK_MAGNETIC = 3
    const val SHADE_PULL_GPT_HIGH = 4
    const val DEFAULT_SHADE_PULL_ANIMATION_MODE = SHADE_PULL_GPT_HIGH // default shade-pull preset

    private const val CURRENT_DEFAULTS_VERSION = 5

    const val DEFAULT_ISLAND_WIDTH_DP = 134
    const val DEFAULT_ISLAND_HEIGHT_DP = 38
    const val DEFAULT_ISLAND_Y_DP = 1
    const val DEFAULT_ISLAND_X_DP = 0

    /**
     * Which morph the island plays. It is a setting and not a build because he asked to choose the feel
     * himself: "kya koi aur animation idea hai? TestLab mei he daalna options choose karne ke liye". A
     * taste cannot be settled by me shipping one opinion per APK, and one rebuild per option would eat an
     * evening of his patience; TestLab flips it live and TOGGLE replays the morph on the spot.
     */
    const val MORPH_STYLE_BALANCED = 0     // rides the shape, scales, opacity follows the shape
    const val MORPH_STYLE_CARRY = 1        // rides the shape only (what b1378 shipped)
    const val MORPH_STYLE_SHAPE_ONLY = 2   // scales and fades with the shape, no travel
    /**
     * The fourth form: the content arrives out of focus and sharpens as the box lands. Apple's glass material is
     * defined by exactly this - its lensing is what iOS's own "Reduce Motion" switch exists to remove - and
     * Android has had a GPU-side effect for it since 12 (`RenderEffect`). The icon rides through it sharp,
     * because the icon is the element that carries the app's identity.
     */
    const val MORPH_STYLE_GLASS = 3
    /**
     * Fifth style: the outside design Claude produced - two springs (the shape runs one, the content runs
     * another that is gated until the capsule is ~60 % of the way there), a radius tied to the height,
     * edge-aware asymmetric growth around an off-centre punch hole, and three named feel presets.
     */
    const val MORPH_STYLE_LIQUID = 4
    /**
     * Sixth style: the outside design ChatGPT produced - "HyperMorph": compression, bloom, content migration,
     * micro-settle, and an asymmetric collapse, plus the two ideas nobody else has (magnetic anchors pulling
     * the content in, and a short energy ripple on the surface at open).
     */
    const val MORPH_STYLE_HYPERMORPH = 5
    /**
     * Seventh style: HIS second spec replaces his first under the same number ("replace the 7th one", his
     * binding word). Blueprint v2, "Precise Snap, Organic Breath" - the container spring hard-locks at
     * t = 0.337 with its own 2-3 px overshoot and one haptic; the text column rides an anchor pendulum
     * (-40 arrival / +10 dp sink at the lock / the -5 and +2 bounce pair / rigid at hard lock), stretched
     * 1.15 x 0.95 through the first 247 ms only; the icon rides the same pendulum at half buoyancy, 40 ms
     * late, never stretched. Collapse is asymmetric on purpose: 380 ms, damping 0.90, no overshoot - "a put
     * away feel", not a rewind. Constants live in MotionVariant where tests pin every one of them.
     */
    const val MORPH_STYLE_BLUEPRINT = 6
    // His design is the front door: every existing build keeps its chosen style (the pref wins), and every
    // fresh install starts on HIS, because the style he invented should not hide behind a default he never
    // asked for - the round-35 lesson being that an idea he cannot find reads as an idea that was ignored.
    const val DEFAULT_MORPH_STYLE = MORPH_STYLE_BLUEPRINT
    const val DEFAULT_MORPH_CONTENT_SCALE_PCT = 12
    const val DEFAULT_MORPH_GLYPH_SWAP_PCT = 50
    /** Extra travel on top of the geometry, in dp. 0 means: let the row arrive on the box's own math alone. */
    const val DEFAULT_MORPH_CONTENT_DROP_DP = 20
    const val DEFAULT_MORPH_STAGGER_PCT = 35
    const val DEFAULT_MORPH_GONE_BY_PCT = 45

    /**
     * Where the content enters from. The box grows evenly on both sides and hangs from its own top edge, so
     * "drop from the pill" moves the content on that same axis; "centred in the box" is what the earlier builds
     * did - the host re-centred the row in the drawn box every frame, and the carry slid it in from the right,
     * which he described as arriving "upper right side se niche center ki aur" (and "upper left" without the
     * carry). Both stay available because the feeling is his call, and the arithmetic behind each is in
     * IslandMorphFrame.
     */
    const val MORPH_ENTRY_DROP = 0
    const val MORPH_ENTRY_CENTRED = 1
    const val DEFAULT_MORPH_ENTRY = MORPH_ENTRY_DROP

    /**
     * The icon ride is NOT part of a style any more. He liked "scale + fade" for the content and the travelling
     * icon separately - "scale plus fade bhi mast hai lekin ... upar se icon ride hoti to aur mast lagti" - and
     * bundling the two meant he could not have both. One switch, combinable with any style.
     */

    fun getMorphContentDropDp(context: Context) = prefs(context).getInt(KEY_MORPH_CONTENT_DROP_DP, DEFAULT_MORPH_CONTENT_DROP_DP)
    fun setMorphContentDropDp(context: Context, v: Int) =
        prefs(context).edit().putInt(KEY_MORPH_CONTENT_DROP_DP, v.coerceIn(0, 40)).apply()

    /** The badge's digits slide the way the number moved (Apple's `.numericText()`); off means it just changes. */
    fun getMorphCountRoll(context: Context) = prefs(context).getBoolean(KEY_MORPH_COUNT_ROLL, true)
    fun setMorphCountRoll(context: Context, v: Boolean) = prefs(context).edit().putBoolean(KEY_MORPH_COUNT_ROLL, v).apply()

    fun getMorphEntryName(context: Context): String =
        if (getMorphEntry(context) == MORPH_ENTRY_DROP) "drops out of the pill" else "centred in the box"

    fun getMorphEntry(context: Context) =
        prefs(context).getInt(KEY_MORPH_ENTRY, DEFAULT_MORPH_ENTRY).coerceIn(MORPH_ENTRY_DROP, MORPH_ENTRY_CENTRED)
    fun setMorphEntry(context: Context, v: Int) =
        prefs(context).edit().putInt(KEY_MORPH_ENTRY, v.coerceIn(MORPH_ENTRY_DROP, MORPH_ENTRY_CENTRED)).apply()

    /** How much of the shape's travel the children share out between themselves instead of arriving as one block. */
    fun getMorphStaggerPct(context: Context) = prefs(context).getInt(KEY_MORPH_STAGGER_PCT, DEFAULT_MORPH_STAGGER_PCT)
    fun setMorphStaggerPct(context: Context, v: Int) =
        prefs(context).edit().putInt(KEY_MORPH_STAGGER_PCT, v.coerceIn(0, 60)).apply()

    /** A collapse's own deadline: past this fraction of the shape's travel nothing of the row may still be drawn. */
    fun getMorphGoneByPct(context: Context) = prefs(context).getInt(KEY_MORPH_GONE_BY_PCT, DEFAULT_MORPH_GONE_BY_PCT)
    fun setMorphGoneByPct(context: Context, v: Int) =
        prefs(context).edit().putInt(KEY_MORPH_GONE_BY_PCT, v.coerceIn(25, 90)).apply()

    /**
     * What opening the notification shade does to the island. There was one behaviour and it was wrong: the
     * shade being open (or MIUI reporting any tall enough systemui window as the shade - a heads-up can do that)
     * emptied the ring AND refused every notification that arrived meanwhile. So the count fell during a
     * notification rain with nothing touched, and some messages never showed at all. The quiet policy keeps
     * counting and only hides the pill; WIPES is the old behaviour, left in TestLab so it can be compared
     * instead of argued about.
     */
    const val SHADE_POLICY_COUNT_QUIETLY = 0  // keep counting, never pop, never wipe
    const val SHADE_POLICY_WIPES = 1          // old: opening the shelf marks everything read
    const val DEFAULT_SHADE_POLICY = SHADE_POLICY_COUNT_QUIETLY
    const val DEFAULT_ISLAND_CORNER_RADIUS_DP = 19
    const val DEFAULT_ISLAND_STAGE2_WIDTH_DP = 180
    const val DEFAULT_ISLAND_EXPANDED_WIDTH_DP = 390
    const val DEFAULT_ISLAND_EXPANDED_HEIGHT_DP = 154
    const val DEFAULT_ISLAND_EXPANDED_CORNER_RADIUS_DP = 42

    fun ensurePhaseDefaults(context: Context) {
        val prefs = prefs(context)
        val version = prefs.getInt(KEY_DEFAULTS_VERSION, 0)
        if (version < CURRENT_DEFAULTS_VERSION) {
            val editor = prefs.edit()
            editor.putInt(KEY_ISLAND_WIDTH_DP, DEFAULT_ISLAND_WIDTH_DP)
                .putInt(KEY_ISLAND_HEIGHT_DP, DEFAULT_ISLAND_HEIGHT_DP)
                .putInt(KEY_ISLAND_Y_DP, DEFAULT_ISLAND_Y_DP)
                .putInt(KEY_ISLAND_X_DP, DEFAULT_ISLAND_X_DP)
                .putInt(KEY_ISLAND_CORNER_RADIUS_DP, DEFAULT_ISLAND_CORNER_RADIUS_DP)
                .putInt(KEY_ISLAND_STAGE2_WIDTH_DP, DEFAULT_ISLAND_STAGE2_WIDTH_DP)
                .putInt(KEY_ISLAND_EXPANDED_WIDTH_DP, DEFAULT_ISLAND_EXPANDED_WIDTH_DP)
                .putInt(KEY_ISLAND_EXPANDED_HEIGHT_DP, DEFAULT_ISLAND_EXPANDED_HEIGHT_DP)
                .putInt(KEY_ISLAND_EXPANDED_CORNER_RADIUS_DP, DEFAULT_ISLAND_EXPANDED_CORNER_RADIUS_DP)
                .putInt(KEY_REPLY_ANIMATION_MODE, DEFAULT_REPLY_ANIMATION_MODE)
                .putInt(KEY_REPLY_LIQUID_HEIGHT_DP, DEFAULT_REPLY_LIQUID_HEIGHT_DP)
                .putInt(KEY_REPLY_LIQUID_RADIUS_DP, DEFAULT_REPLY_LIQUID_RADIUS_DP)
                .putInt(KEY_REPLY_LIQUID_LEFT_GAP_DP, DEFAULT_REPLY_LIQUID_LEFT_GAP_DP)
                .putInt(KEY_REPLY_LIQUID_EDGE_GAP_DP, DEFAULT_REPLY_LIQUID_EDGE_GAP_DP)
            editor.putInt(KEY_DEFAULTS_VERSION, CURRENT_DEFAULTS_VERSION).apply()
        }
    }

    fun isDeveloperMode(context: Context) = prefs(context).getBoolean(KEY_DEVELOPER_MODE, false)
    fun setDeveloperMode(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean(KEY_DEVELOPER_MODE, enabled).apply()
    fun isIslandEnabled(context: Context) = prefs(context).getBoolean(KEY_ISLAND_ENABLED, false)
    fun setIslandEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean(KEY_ISLAND_ENABLED, enabled).apply()
    fun getOverlayEngine(context: Context) = prefs(context).getString(KEY_OVERLAY_ENGINE, ENGINE_NONE) ?: ENGINE_NONE
    fun setOverlayEngine(context: Context, engine: String) = prefs(context).edit().putString(KEY_OVERLAY_ENGINE, engine).apply()

    fun getIslandWidthDp(context: Context) = prefs(context).getInt(KEY_ISLAND_WIDTH_DP, DEFAULT_ISLAND_WIDTH_DP)
    fun setIslandWidthDp(context: Context, v: Int) = prefs(context).edit().putInt(KEY_ISLAND_WIDTH_DP, v).apply()
    fun getIslandHeightDp(context: Context) = prefs(context).getInt(KEY_ISLAND_HEIGHT_DP, DEFAULT_ISLAND_HEIGHT_DP)
    fun setIslandHeightDp(context: Context, v: Int) = prefs(context).edit().putInt(KEY_ISLAND_HEIGHT_DP, v).apply()
    fun getIslandCornerRadiusDp(context: Context) = prefs(context).getInt(KEY_ISLAND_CORNER_RADIUS_DP, DEFAULT_ISLAND_CORNER_RADIUS_DP)
    fun setIslandCornerRadiusDp(context: Context, v: Int) = prefs(context).edit().putInt(KEY_ISLAND_CORNER_RADIUS_DP, v).apply()
    fun getIslandYDp(context: Context) = prefs(context).getInt(KEY_ISLAND_Y_DP, DEFAULT_ISLAND_Y_DP)
    fun setIslandYDp(context: Context, v: Int) = prefs(context).edit().putInt(KEY_ISLAND_Y_DP, v).apply()
    fun getIslandXDp(context: Context) = prefs(context).getInt(KEY_ISLAND_X_DP, DEFAULT_ISLAND_X_DP)
    fun setIslandXDp(context: Context, v: Int) = prefs(context).edit().putInt(KEY_ISLAND_X_DP, v).apply()

    // Both directions go through MotionVariant.clampStyle, and the range is one number there. It used to be a
    // literal 0..MORPH_STYLE_SHAPE_ONLY written twice here, which is how the fourth style became unselectable:
    // the radio stored 2, the getter read 2, and the tester correctly reported that the new option looked
    // exactly like the old one. A list whose last entry cannot be picked is not a list.
    fun getMorphStyle(context: Context) =
        MotionVariant.clampStyle(prefs(context).getInt(KEY_MORPH_STYLE, DEFAULT_MORPH_STYLE))

    fun setMorphStyle(context: Context, v: Int) = prefs(context).edit()
        .putInt(KEY_MORPH_STYLE, MotionVariant.clampStyle(v)).apply()

    fun getMorphStyleName(context: Context): String = getMorphStyleName(getMorphStyle(context))

    /**
     * A style number on its own, no Context. It exists so a JVM test can assert that every entry in the list is
     * called something different: the round where the fourth option turned out to be unreachable was also the
     * round where two radios described one behaviour, and a name table is the cheapest place in the world to
     * catch that before it ships.
     */
    fun getMorphStyleName(style: Int): String = MotionVariant.styleName(style)

    // --- the two outside designs' knobs. Each is a percent the Lab slides, and each is clamped by the same
    // pure function the tests use, so a stored value can never be outside the range the rules assume.
    fun getMotionProfile(context: Context) =
        MotionVariant.clampProfile(prefs(context).getInt(KEY_MOTION_PROFILE, MotionVariant.PROFILE_SILKY))
    fun setMotionProfile(context: Context, v: Int) = prefs(context).edit()
        .putInt(KEY_MOTION_PROFILE, MotionVariant.clampProfile(v)).apply()

    /** Phase A's squeeze, as a percent of width (3 = "97 % width, 106 % height"). */
    fun getMotionSqueezePct(context: Context) = MotionVariant.clampPct(prefs(context).getInt(KEY_MOTION_SQUEEZE, 5), 0, 8)
    fun setMotionSqueezePct(context: Context, v: Int) = prefs(context).edit()
        .putInt(KEY_MOTION_SQUEEZE, MotionVariant.clampPct(v, 0, 8)).apply()

    /** How far the box has to open before the content is allowed to crossfade (Claude's 60 %). */
    fun getMotionGatePct(context: Context) = MotionVariant.clampPct(prefs(context).getInt(KEY_MOTION_GATE, 60), 0, 90)
    fun setMotionGatePct(context: Context, v: Int) = prefs(context).edit()
        .putInt(KEY_MOTION_GATE, MotionVariant.clampPct(v, 0, 90)).apply()

    /** How hard the pill's anchors pull the content home on a collapse (0 = the linear move he has now). */
    fun getMotionMagnetPct(context: Context) = MotionVariant.clampPct(prefs(context).getInt(KEY_MOTION_MAGNET, 60), 0, 150)
    fun setMotionMagnetPct(context: Context, v: Int) = prefs(context).edit()
        .putInt(KEY_MOTION_MAGNET, MotionVariant.clampPct(v, 0, 150)).apply()

    /** How much smaller the card content starts, as a percent: 12 means it opens at 88% and grows in. */
    fun getMorphContentScalePct(context: Context) = prefs(context).getInt(KEY_MORPH_CONTENT_SCALE, DEFAULT_MORPH_CONTENT_SCALE_PCT)
    fun setMorphContentScalePct(context: Context, v: Int) =
        prefs(context).edit().putInt(KEY_MORPH_CONTENT_SCALE, v.coerceIn(0, 45)).apply()

    /** Where along the morph the travelling icon puts on the launcher badge instead of the pill glyph. */
    fun getMorphGlyphSwapPct(context: Context) = prefs(context).getInt(KEY_MORPH_GLYPH_SWAP, DEFAULT_MORPH_GLYPH_SWAP_PCT)
    fun setMorphGlyphSwapPct(context: Context, v: Int) =
        prefs(context).edit().putInt(KEY_MORPH_GLYPH_SWAP, v.coerceIn(10, 90)).apply()

    fun getShadeOpenPolicy(context: Context) = prefs(context).getInt(KEY_SHADE_POLICY, DEFAULT_SHADE_POLICY)
        .coerceIn(SHADE_POLICY_COUNT_QUIETLY, SHADE_POLICY_WIPES)

    fun setShadeOpenPolicy(context: Context, v: Int) = prefs(context).edit()
        .putInt(KEY_SHADE_POLICY, v.coerceIn(SHADE_POLICY_COUNT_QUIETLY, SHADE_POLICY_WIPES)).apply()

    fun getShadeOpenPolicyName(context: Context): String =
        if (getShadeOpenPolicy(context) == SHADE_POLICY_WIPES) "shade wipes (old)" else "keep counting"

    fun getIslandStage2WidthDp(context: Context) = prefs(context).getInt(KEY_ISLAND_STAGE2_WIDTH_DP, DEFAULT_ISLAND_STAGE2_WIDTH_DP)
    fun setIslandStage2WidthDp(context: Context, v: Int) = prefs(context).edit().putInt(KEY_ISLAND_STAGE2_WIDTH_DP, v).apply()
    fun getIslandExpandedWidthDp(context: Context) = prefs(context).getInt(KEY_ISLAND_EXPANDED_WIDTH_DP, DEFAULT_ISLAND_EXPANDED_WIDTH_DP)
    fun setIslandExpandedWidthDp(context: Context, v: Int) = prefs(context).edit().putInt(KEY_ISLAND_EXPANDED_WIDTH_DP, v).apply()
    fun getIslandExpandedHeightDp(context: Context) = prefs(context).getInt(KEY_ISLAND_EXPANDED_HEIGHT_DP, DEFAULT_ISLAND_EXPANDED_HEIGHT_DP)
    fun setIslandExpandedHeightDp(context: Context, v: Int) = prefs(context).edit().putInt(KEY_ISLAND_EXPANDED_HEIGHT_DP, v).apply()
    fun getIslandExpandedCornerRadiusDp(context: Context) = prefs(context).getInt(KEY_ISLAND_EXPANDED_CORNER_RADIUS_DP, DEFAULT_ISLAND_EXPANDED_CORNER_RADIUS_DP)
    fun setIslandExpandedCornerRadiusDp(context: Context, v: Int) = prefs(context).edit().putInt(KEY_ISLAND_EXPANDED_CORNER_RADIUS_DP, v).apply()

    fun getReplyAnimationMode(context: Context): Int {
        val mode = prefs(context).getInt(KEY_REPLY_ANIMATION_MODE, DEFAULT_REPLY_ANIMATION_MODE)
        return mode.coerceIn(REPLY_ANIM_CLASSIC_LAYOUT, REPLY_ANIM_MINIMAL_PRO)
    }

    fun setReplyAnimationMode(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_REPLY_ANIMATION_MODE, mode.coerceIn(REPLY_ANIM_CLASSIC_LAYOUT, REPLY_ANIM_MINIMAL_PRO)).apply()
    }

    fun getReplyAnimationModeName(mode: Int): String = when (mode.coerceIn(REPLY_ANIM_CLASSIC_LAYOUT, REPLY_ANIM_MINIMAL_PRO)) {
        REPLY_ANIM_CLASSIC_LAYOUT -> "Classic Layout Morph"
        REPLY_ANIM_GPU_SMOOTH -> "GPU Smooth Scale"
        REPLY_ANIM_MAGNETIC_DOCK -> "V3: Capsule Chrome"
        REPLY_ANIM_LIQUID_FILL -> "V3: Liquid Parallax"
        REPLY_ANIM_ELASTIC_BUBBLE -> "V3: HyperOS Snap"
        REPLY_ANIM_MINIMAL_PRO -> "Minimal Pro Fade"
        else -> "Magnetic Dock Morph"
    }

    fun getReplyLiquidHeightDp(context: Context) = prefs(context).getInt(KEY_REPLY_LIQUID_HEIGHT_DP, DEFAULT_REPLY_LIQUID_HEIGHT_DP)
    fun setReplyLiquidHeightDp(context: Context, v: Int) = prefs(context).edit().putInt(KEY_REPLY_LIQUID_HEIGHT_DP, v.coerceIn(32, 64)).apply()
    fun getReplyLiquidRadiusDp(context: Context) = prefs(context).getInt(KEY_REPLY_LIQUID_RADIUS_DP, DEFAULT_REPLY_LIQUID_RADIUS_DP)
    fun setReplyLiquidRadiusDp(context: Context, v: Int) = prefs(context).edit().putInt(KEY_REPLY_LIQUID_RADIUS_DP, v.coerceIn(8, 34)).apply()
    fun getReplyLiquidLeftGapDp(context: Context) = prefs(context).getInt(KEY_REPLY_LIQUID_LEFT_GAP_DP, DEFAULT_REPLY_LIQUID_LEFT_GAP_DP)
    fun setReplyLiquidLeftGapDp(context: Context, v: Int) = prefs(context).edit().putInt(KEY_REPLY_LIQUID_LEFT_GAP_DP, v.coerceIn(0, 48)).apply()
    fun getReplyLiquidEdgeGapDp(context: Context) = prefs(context).getInt(KEY_REPLY_LIQUID_EDGE_GAP_DP, DEFAULT_REPLY_LIQUID_EDGE_GAP_DP)
    fun setReplyLiquidEdgeGapDp(context: Context, v: Int) = prefs(context).edit().putInt(KEY_REPLY_LIQUID_EDGE_GAP_DP, v.coerceIn(0, 40)).apply()

    fun getPillIconRenderMode(context: Context): Int {
        return prefs(context).getInt(KEY_PILL_ICON_RENDER_MODE, DEFAULT_PILL_ICON_RENDER_MODE)
            .coerceIn(PILL_ICON_AUTO, PILL_ICON_GENERIC)
    }

    fun setPillIconRenderMode(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_PILL_ICON_RENDER_MODE, mode.coerceIn(PILL_ICON_AUTO, PILL_ICON_GENERIC)).apply()
    }

    fun getPillIconRenderModeName(mode: Int): String = when (mode.coerceIn(PILL_ICON_AUTO, PILL_ICON_GENERIC)) {
        PILL_ICON_AUTO -> "Auto validated"
        PILL_ICON_MANUAL_RESOURCE_NO_VALIDATION -> "Manual resource no validation"
        PILL_ICON_MANUAL_RESOURCE_VALIDATED -> "Manual resource validated"
        PILL_ICON_LOAD_DRAWABLE_NO_VALIDATION -> "loadDrawable no validation"
        PILL_ICON_LOAD_DRAWABLE_VALIDATED -> "loadDrawable validated"
        PILL_ICON_LEGACY_NO_VALIDATION -> "Legacy res no validation"
        PILL_ICON_ADAPTIVE_NO_VALIDATION -> "Adaptive foreground no validation"
        PILL_ICON_LAUNCHER -> "Launcher icon"
        PILL_ICON_GENERIC -> "Generic glyph"
        else -> "Auto validated"
    }

    fun getShadePullAnimationMode(context: Context): Int {
        return prefs(context).getInt(KEY_SHADE_PULL_ANIMATION_MODE, DEFAULT_SHADE_PULL_ANIMATION_MODE)
            .coerceIn(SHADE_PULL_SIMPLE, SHADE_PULL_GPT_HIGH)
    }

    fun setShadePullAnimationMode(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_SHADE_PULL_ANIMATION_MODE, mode.coerceIn(SHADE_PULL_SIMPLE, SHADE_PULL_GPT_HIGH)).apply()
    }

    fun getShadePullAnimationModeName(mode: Int): String = when (mode.coerceIn(SHADE_PULL_SIMPLE, SHADE_PULL_GPT_HIGH)) {
        SHADE_PULL_SIMPLE -> "Simple fade/scale"
        SHADE_PULL_MY_ABSORB -> "Shade Pull Absorb"
        SHADE_PULL_KIMI_VACUUM -> "Kimi Vacuum Extraction"
        SHADE_PULL_DEEPSEEK_MAGNETIC -> "DeepSeek Magnetic Pull"
        SHADE_PULL_GPT_HIGH -> "GPT High Magnetic"
        else -> "GPT High Magnetic"
    }

    fun resetIslandDefaults(context: Context) {
        prefs(context).edit().clear().putInt(KEY_DEFAULTS_VERSION, CURRENT_DEFAULTS_VERSION).apply()
        ensurePhaseDefaults(context)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
}
