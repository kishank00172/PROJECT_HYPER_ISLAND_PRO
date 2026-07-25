package com.hyperisland.pro.services

import android.graphics.drawable.Icon

/**
 * Process-local cache of the latest real notification icon data per package.
 * TestLab previews can use this after a real notification arrives, so SmallIcon/resource tests are valid.
 */
object PillIconCache {
    data class Entry(
        val packageName: String,
        val smallIcon: Icon?,
        val legacyIconResId: Int,
        val updatedAt: Long
    )

    private val lock = Any()
    private val entries = HashMap<String, Entry>()

    fun record(packageName: String, smallIcon: Icon?, legacyIconResId: Int) {
        if (packageName.isBlank()) return
        synchronized(lock) {
            entries[packageName] = Entry(
                packageName = packageName,
                smallIcon = smallIcon,
                legacyIconResId = legacyIconResId,
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    fun get(packageName: String): Entry? {
        if (packageName.isBlank()) return null
        return synchronized(lock) { entries[packageName] }
    }
}
