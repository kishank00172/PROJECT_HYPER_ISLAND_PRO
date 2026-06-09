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

    const val ENGINE_NONE = "none"
    const val ENGINE_ACCESSIBILITY = "accessibility"
    const val ENGINE_APPLICATION = "application"

    private const val CURRENT_DEFAULTS_VERSION = 5

    const val DEFAULT_ISLAND_WIDTH_DP = 134
    const val DEFAULT_ISLAND_HEIGHT_DP = 38
    const val DEFAULT_ISLAND_Y_DP = 1
    const val DEFAULT_ISLAND_X_DP = 0
    const val DEFAULT_ISLAND_CORNER_RADIUS_DP = 19 // Half of 38

    const val DEFAULT_ISLAND_STAGE2_WIDTH_DP = 180
    const val DEFAULT_ISLAND_EXPANDED_WIDTH_DP = 390
    const val DEFAULT_ISLAND_EXPANDED_HEIGHT_DP = 154
    const val DEFAULT_ISLAND_EXPANDED_CORNER_RADIUS_DP = 42

    fun ensurePhaseDefaults(context: Context) {
        val prefs = prefs(context)
        val version = prefs.getInt(KEY_DEFAULTS_VERSION, 0)

        if (version < CURRENT_DEFAULTS_VERSION) {
            val editor = prefs.edit()
            if (version < 5) {
                editor.putInt(KEY_ISLAND_WIDTH_DP, DEFAULT_ISLAND_WIDTH_DP)
                    .putInt(KEY_ISLAND_HEIGHT_DP, DEFAULT_ISLAND_HEIGHT_DP)
                    .putInt(KEY_ISLAND_Y_DP, DEFAULT_ISLAND_Y_DP)
                    .putInt(KEY_ISLAND_X_DP, DEFAULT_ISLAND_X_DP)
                    .putInt(KEY_ISLAND_CORNER_RADIUS_DP, DEFAULT_ISLAND_CORNER_RADIUS_DP)
                    .putInt(KEY_ISLAND_STAGE2_WIDTH_DP, DEFAULT_ISLAND_STAGE2_WIDTH_DP)
                    .putInt(KEY_ISLAND_EXPANDED_WIDTH_DP, DEFAULT_ISLAND_EXPANDED_WIDTH_DP)
                    .putInt(KEY_ISLAND_EXPANDED_HEIGHT_DP, DEFAULT_ISLAND_EXPANDED_HEIGHT_DP)
                    .putInt(KEY_ISLAND_EXPANDED_CORNER_RADIUS_DP, DEFAULT_ISLAND_EXPANDED_CORNER_RADIUS_DP)
            }
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
    fun setIslandWidthDp(context: Context, value: Int) = prefs(context).edit().putInt(KEY_ISLAND_WIDTH_DP, value.coerceIn(70, 260)).apply()

    fun getIslandHeightDp(context: Context) = prefs(context).getInt(KEY_ISLAND_HEIGHT_DP, DEFAULT_ISLAND_HEIGHT_DP)
    fun setIslandHeightDp(context: Context, value: Int) = prefs(context).edit().putInt(KEY_ISLAND_HEIGHT_DP, value.coerceIn(24, 60)).apply()

    fun getIslandCornerRadiusDp(context: Context) = prefs(context).getInt(KEY_ISLAND_CORNER_RADIUS_DP, DEFAULT_ISLAND_CORNER_RADIUS_DP)
    fun setIslandCornerRadiusDp(context: Context, value: Int) = prefs(context).edit().putInt(KEY_ISLAND_CORNER_RADIUS_DP, value.coerceIn(0, 30)).apply()

    fun getIslandYDp(context: Context) = prefs(context).getInt(KEY_ISLAND_Y_DP, DEFAULT_ISLAND_Y_DP)
    fun setIslandYDp(context: Context, value: Int) = prefs(context).edit().putInt(KEY_ISLAND_Y_DP, value.coerceIn(0, 120)).apply()

    fun getIslandXDp(context: Context) = prefs(context).getInt(KEY_ISLAND_X_DP, DEFAULT_ISLAND_X_DP)
    fun setIslandXDp(context: Context, value: Int) = prefs(context).edit().putInt(KEY_ISLAND_X_DP, value.coerceIn(-180, 180)).apply()

    fun getIslandStage2WidthDp(context: Context) = prefs(context).getInt(KEY_ISLAND_STAGE2_WIDTH_DP, DEFAULT_ISLAND_STAGE2_WIDTH_DP)
    fun setIslandStage2WidthDp(context: Context, value: Int) = prefs(context).edit().putInt(KEY_ISLAND_STAGE2_WIDTH_DP, value.coerceIn(100, 300)).apply()

    fun getIslandExpandedWidthDp(context: Context) = prefs(context).getInt(KEY_ISLAND_EXPANDED_WIDTH_DP, DEFAULT_ISLAND_EXPANDED_WIDTH_DP)
    fun setIslandExpandedWidthDp(context: Context, value: Int) = prefs(context).edit().putInt(KEY_ISLAND_EXPANDED_WIDTH_DP, value.coerceIn(180, 420)).apply()

    fun getIslandExpandedHeightDp(context: Context) = prefs(context).getInt(KEY_ISLAND_EXPANDED_HEIGHT_DP, DEFAULT_ISLAND_EXPANDED_HEIGHT_DP)
    fun setIslandExpandedHeightDp(context: Context, value: Int) = prefs(context).edit().putInt(KEY_ISLAND_EXPANDED_HEIGHT_DP, value.coerceIn(70, 220)).apply()

    fun getIslandExpandedCornerRadiusDp(context: Context) = prefs(context).getInt(KEY_ISLAND_EXPANDED_CORNER_RADIUS_DP, DEFAULT_ISLAND_EXPANDED_CORNER_RADIUS_DP)
    fun setIslandExpandedCornerRadiusDp(context: Context, value: Int) = prefs(context).edit().putInt(KEY_ISLAND_EXPANDED_CORNER_RADIUS_DP, value.coerceIn(0, 60)).apply()

    fun resetIslandDefaults(context: Context) {
        prefs(context).edit()
            .putInt(KEY_ISLAND_WIDTH_DP, DEFAULT_ISLAND_WIDTH_DP)
            .putInt(KEY_ISLAND_HEIGHT_DP, DEFAULT_ISLAND_HEIGHT_DP)
            .putInt(KEY_ISLAND_Y_DP, DEFAULT_ISLAND_Y_DP)
            .putInt(KEY_ISLAND_X_DP, DEFAULT_ISLAND_X_DP)
            .putInt(KEY_ISLAND_CORNER_RADIUS_DP, DEFAULT_ISLAND_CORNER_RADIUS_DP)
            .putInt(KEY_ISLAND_STAGE2_WIDTH_DP, DEFAULT_ISLAND_STAGE2_WIDTH_DP)
            .putInt(KEY_ISLAND_EXPANDED_WIDTH_DP, DEFAULT_ISLAND_EXPANDED_WIDTH_DP)
            .putInt(KEY_ISLAND_EXPANDED_HEIGHT_DP, DEFAULT_ISLAND_EXPANDED_HEIGHT_DP)
            .putInt(KEY_ISLAND_EXPANDED_CORNER_RADIUS_DP, DEFAULT_ISLAND_EXPANDED_CORNER_RADIUS_DP)
            .putInt(KEY_DEFAULTS_VERSION, CURRENT_DEFAULTS_VERSION)
            .apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
}
