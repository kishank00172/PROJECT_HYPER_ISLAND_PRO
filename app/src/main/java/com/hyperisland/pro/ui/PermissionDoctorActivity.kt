package com.hyperisland.pro.ui

import android.app.Activity
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.hyperisland.pro.R
import com.hyperisland.pro.services.HyperAccessibilityService

class PermissionDoctorActivity : Activity() {

    private data class Row(
        val root: View,
        val title: TextView,
        val desc: TextView,
        val status: TextView,
        val fix: Button
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permission_doctor)
        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        bindFixButtons()
    }

    override fun onResume() {
        super.onResume()
        refreshRows()
    }

    private fun bindRow(id: Int): Row {
        val root = findViewById<View>(id)
        return Row(
            root = root,
            title = root.findViewById(R.id.txtTitle),
            desc = root.findViewById(R.id.txtDesc),
            status = root.findViewById(R.id.txtStatus),
            fix = root.findViewById(R.id.btnFix)
        )
    }

    private fun bindFixButtons() {
        bindRow(R.id.rowOverlay).fix.setOnClickListener {
            openIntent(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        bindRow(R.id.rowNotification).fix.setOnClickListener {
            openIntent(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        bindRow(R.id.rowAccessibility).fix.setOnClickListener {
            openIntent(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        bindRow(R.id.rowBattery).fix.setOnClickListener {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:$packageName")
            openIntent(intent, fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        bindRow(R.id.rowUsage).fix.setOnClickListener {
            openIntent(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        bindRow(R.id.rowAutostart).fix.setOnClickListener {
            openXiaomiAutostartSettings()
        }
        bindRow(R.id.rowShizuku).fix.setOnClickListener {
            openIntent(packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                ?: Intent(Settings.ACTION_APPLICATION_SETTINGS))
        }
    }

    private fun refreshRows() {
        setRow(
            bindRow(R.id.rowOverlay),
            "Overlay permission",
            "Required in Phase 1 for the real floating island window.",
            Settings.canDrawOverlays(this),
            missingText = "Missing"
        )

        setRow(
            bindRow(R.id.rowNotification),
            "Notification access",
            "Required in Phase 3 to read notifications and build real island events.",
            isNotificationListenerEnabled(),
            missingText = "Missing"
        )

        setRow(
            bindRow(R.id.rowAccessibility),
            "Accessibility service",
            "Used later for notification panel awareness and lockscreen/window state detection.",
            isAccessibilityServiceEnabled(),
            missingText = "Missing"
        )

        setRow(
            bindRow(R.id.rowBattery),
            "Battery optimization",
            "Recommended for HyperOS/Xiaomi survival so the overlay service is not killed.",
            isIgnoringBatteryOptimizations(),
            missingText = "Restricted",
            optional = true
        )

        setRow(
            bindRow(R.id.rowUsage),
            "Usage access",
            "Optional now. Later used for foreground app detection and per-app rules.",
            hasUsageAccess(),
            missingText = "Optional",
            optional = true
        )

        val autostart = bindRow(R.id.rowAutostart)
        autostart.title.text = "Xiaomi/HyperOS autostart"
        autostart.desc.text = "No official Android API can verify this. Open Xiaomi security settings and allow autostart manually."
        setStatus(autostart.status, "Manual check", Status.ORANGE)
        autostart.fix.text = "Open"

        val shizukuInstalled = isPackageInstalled("moe.shizuku.privileged.api")
        val shizuku = bindRow(R.id.rowShizuku)
        shizuku.title.text = "Shizuku"
        shizuku.desc.text = "Optional advanced-feature dependency. Real Shizuku integration starts in a later phase."
        setStatus(shizuku.status, if (shizukuInstalled) "Installed" else "Not installed", if (shizukuInstalled) Status.GREEN else Status.ORANGE)
        shizuku.fix.text = if (shizukuInstalled) "Open" else "Settings"
    }

    private enum class Status { GREEN, RED, ORANGE }

    private fun setRow(row: Row, title: String, desc: String, granted: Boolean, missingText: String, optional: Boolean = false) {
        row.title.text = title
        row.desc.text = desc
        if (granted) {
            setStatus(row.status, "Ready", Status.GREEN)
            row.fix.text = "Open"
        } else {
            setStatus(row.status, missingText, if (optional) Status.ORANGE else Status.RED)
            row.fix.text = "Fix"
        }
    }

    private fun setStatus(view: TextView, text: String, status: Status) {
        view.text = text
        val bg = when (status) {
            Status.GREEN -> R.drawable.bg_status_green
            Status.RED -> R.drawable.bg_status_red
            Status.ORANGE -> R.drawable.bg_status_orange
        }
        view.setBackgroundResource(bg)
        view.setTextColor(Color.WHITE)
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return flat.split(":").any { it.contains(packageName, ignoreCase = true) }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(this, HyperAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(":").any { it.equals(expected, ignoreCase = true) }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val pm = getSystemService(PowerManager::class.java)
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(AppOpsManager::class.java)
        val mode = appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        if (mode == AppOpsManager.MODE_ALLOWED) return true

        val usage = getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val stats = usage.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 60_000, now)
        return !stats.isNullOrEmpty()
    }

    private fun isPackageInstalled(pkg: String): Boolean {
        return try {
            packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun openXiaomiAutostartSettings() {
        val intent = Intent().apply {
            component = ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
        }
        openIntent(intent, fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun openIntent(intent: Intent, fallback: Intent? = null) {
        try {
            startActivity(intent)
        } catch (_: Exception) {
            fallback?.let {
                try { startActivity(it) } catch (_: Exception) { }
            }
        }
    }
}
