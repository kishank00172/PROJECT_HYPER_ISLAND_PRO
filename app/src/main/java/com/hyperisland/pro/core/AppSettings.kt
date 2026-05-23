package com.hyperisland.pro.core

import android.content.Context

object AppSettings {
    private const val PREF_NAME = "hyper_island_settings"
    private const val KEY_DEVELOPER_MODE = "developer_mode"

    fun isDeveloperMode(context: Context): Boolean {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DEVELOPER_MODE, false)
    }

    fun setDeveloperMode(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DEVELOPER_MODE, enabled)
            .apply()
    }
}
