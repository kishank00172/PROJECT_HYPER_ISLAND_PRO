package com.hyperisland.pro.core

import android.graphics.Bitmap

/** b1513: sender DPs for the expanded card (his pick from the notification-lab export).
 *  The listener extracts the latest MessagingStyle message's Person.icon and parks a circular
 *  96 px bitmap here; the island looks it up by any key in the conversation's alias set.
 *  Process-lifetime only (fine: after a process death the avatars simply re-feed from the next
 *  notification post, same as every other hot cache). */
object AvatarStore {
    private val cache = object : LinkedHashMap<String, Bitmap>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 32
    }
    private fun norm(s: String?) = s?.trim()?.lowercase().orEmpty()
    fun put(vararg keys: String?, bmp: Bitmap) {
        keys.map(::norm).filter { it.isNotEmpty() }.distinct().forEach { cache[it] = bmp }
    }
    fun get(vararg keys: String?): Bitmap? {
        for (k in keys.map(::norm)) if (k.isNotEmpty()) cache[k]?.let { return it }
        return null
    }
    fun report() = "AvatarStore size=${'$'}{cache.size}"
}
