package com.hyperisland.pro.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Disabled in Phase 0. Will be enabled after the overlay service is real and tested.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) = Unit
}
