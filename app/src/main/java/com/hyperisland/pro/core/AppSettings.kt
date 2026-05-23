package com.hyperisland.pro.core

import android.content.Context

object AppSettings {
    private const val PREF_NAME = "hyper_island_settings"

    private const val KEY_DEVELOPER_MODE = "developer_mode"
    private const val KEY_ISLAND_ENABLED = "island_enabled"

    private const val KEY_ISLAND_WIDTH_DP = "island_width_dp"
    private const val KEY_ISLAND_HEIGHT_DP = "island_height_dp"
    private const val KEY_ISLAND_Y_DP = "island_y_dp"
    private const val KEY_ISLAND_X_DP = "island_x_dp"

    const val DEFAULT_ISLAND_WIDTH_DP = 126
    const val DEFAULT_ISLAND_HEIGHT_DP = 34
    const val DEFAULT_ISLAND_Y_DP = 12
    const val DEFAULT_ISLAND_X_DP = 0

    fun isDeveloperMode(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_DEVELOPER_MODE, false)
    }

    fun setDeveloperMode(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_DEVELOPER_MODE, enabled)
            .apply()
    }

    fun isIslandEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_ISLAND_ENABLED, false)
    }

    fun setIslandEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_ISLAND_ENABLED, enabled)
            .apply()
    }

    fun getIslandWidthDp(context: Context): Int {
        return prefs(context).getInt(KEY_ISLAND_WIDTH_DP, DEFAULT_ISLAND_WIDTH_DP)
    }

    fun setIslandWidthDp(context: Context, value: Int) {
        prefs(context).edit()
            .putInt(KEY_ISLAND_WIDTH_DP, value.coerceIn(70, 260))
            .apply()
    }

    fun getIslandHeightDp(context: Context): Int {
        return prefs(context).getInt(KEY_ISLAND_HEIGHT_DP, DEFAULT_ISLAND_HEIGHT_DP)
    }

    fun setIslandHeightDp(context: Context, value: Int) {
        prefs(context).edit()
            .putInt(KEY_ISLAND_HEIGHT_DP, value.coerceIn(24, 60))
            .apply()
    }

    fun getIslandYDp(context: Context): Int {
        return prefs(context).getInt(KEY_ISLAND_Y_DP, DEFAULT_ISLAND_Y_DP)
    }

    fun setIslandYDp(context: Context, value: Int) {
        prefs(context).edit()
            .putInt(KEY_ISLAND_Y_DP, value.coerceIn(0, 120))
            .apply()
    }

    fun getIslandXDp(context: Context): Int {
        return prefs(context).getInt(KEY_ISLAND_X_DP, DEFAULT_ISLAND_X_DP)
    }

    fun setIslandXDp(context: Context, value: Int) {
        prefs(context).edit()
            .putInt(KEY_ISLAND_X_DP, value.coerceIn(-180, 180))
            .apply()
    }

    fun resetIslandDefaults(context: Context) {
        prefs(context).edit()
            .putInt(KEY_ISLAND_WIDTH_DP, DEFAULT_ISLAND_WIDTH_DP)
            .putInt(KEY_ISLAND_HEIGHT_DP, DEFAULT_ISLAND_HEIGHT_DP)
            .putInt(KEY_ISLAND_Y_DP, DEFAULT_ISLAND_Y_DP)
            .putInt(KEY_ISLAND_X_DP, DEFAULT_ISLAND_X_DP)
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
}
