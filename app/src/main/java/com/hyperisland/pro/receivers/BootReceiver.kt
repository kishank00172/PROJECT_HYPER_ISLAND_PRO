package com.hyperisland.pro.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hyperisland.pro.core.AppSettings
import com.hyperisland.pro.services.HyperNotificationListenerService

/**
 * b1500: binding self-heal. MIUI drops/re-wires service bindings on both of these moments and
 * the island silently dies (his repeated off->on ritual). One free requestRebind per event.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!AppSettings.getAutoRebindListenerEnabled(context)) return
        HyperNotificationListenerService.requestRebindNow(context)
    }
}
