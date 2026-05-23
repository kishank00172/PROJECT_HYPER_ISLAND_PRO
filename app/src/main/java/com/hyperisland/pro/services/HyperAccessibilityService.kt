package com.hyperisland.pro.services

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Phase 0 placeholder.
 * Later phases will use this for notification panel awareness and lockscreen/window state detection.
 */
class HyperAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
}
