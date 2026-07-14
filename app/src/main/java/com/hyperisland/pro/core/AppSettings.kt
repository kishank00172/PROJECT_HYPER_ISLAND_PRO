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
    private const val KEY_ISLAND_CORNER_RADIUS_DP = "island_corner_radius_dp"

    private const val KEY_ISLAND_STAGE2_WIDTH_DP = "island_stage2_width_dp"
    private const val KEY_ISLAND_EXPANDED_WIDTH_DP = "island_expanded_width_dp"
    private const val KEY_ISLAND_EXPANDED_HEIGHT_DP = "island_expanded_height_dp"
    private const val KEY_ISLAND_EXPANDED_CORNER_RADIUS_DP = "island_expanded_corner_radius_dp"
    private const val KEY_REPLY_ANIMATION_MODE = "reply_animation_mode"

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

    private const val CURRENT_DEFAULTS_VERSION = 5

    const val DEFAULT_ISLAND_WIDTH_DP = 134
    const val DEFAULT_ISLAND_HEIGHT_DP = 38
    const val DEFAULT_ISLAND_Y_DP = 1
    const val DEFAULT_ISLAND_X_DP = 0
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

    fun resetIslandDefaults(context: Context) {
        prefs(context).edit().clear().putInt(KEY_DEFAULTS_VERSION, CURRENT_DEFAULTS_VERSION).apply()
        ensurePhaseDefaults(context)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
}
