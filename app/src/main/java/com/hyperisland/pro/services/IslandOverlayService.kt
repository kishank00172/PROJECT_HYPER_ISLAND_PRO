package com.hyperisland.pro.services

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Phase 0 placeholder only.
 * Real WindowManager overlay starts in Phase 1.
 * Important rule: we do NOT draw fake punch-hole/cutout masks.
 */
class IslandOverlayService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
