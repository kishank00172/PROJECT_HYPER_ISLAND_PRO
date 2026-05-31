package com.hyperisland.pro

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import com.hyperisland.pro.core.AppSettings
import com.hyperisland.pro.services.HyperAccessibilityService
import com.hyperisland.pro.services.IslandOverlayService
import com.hyperisland.pro.ui.PermissionDoctorActivity
import com.hyperisland.pro.ui.SettingsActivity
import com.hyperisland.pro.ui.TestLabActivity

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppSettings.ensurePhaseDefaults(this)
        setContentView(R.layout.activity_main)

        updateStatus()
        startKeepAliveService()

        findViewById<View>(R.id.cardToggle).setOnClickListener {
            val isServiceOn = isAccessibilityServiceEnabled(this)
            if (isServiceOn) {
                // If already on, we just toggle the app's internal logic
                val currentEnabled = AppSettings.isIslandEnabled(this)
                AppSettings.setIslandEnabled(this, !currentEnabled)
                HyperAccessibilityService.refreshIslandFromApp(this)
            } else {
                // Open Permission Doctor if service is off
                startActivity(Intent(this, PermissionDoctorActivity::class.java))
            }
            updateStatus()
        }

        findViewById<View>(R.id.cardDoctor).setOnClickListener {
            startActivity(Intent(this, PermissionDoctorActivity::class.java))
        }
        findViewById<View>(R.id.cardLab).setOnClickListener {
            startActivity(Intent(this, TestLabActivity::class.java))
        }
        findViewById<View>(R.id.cardSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun startKeepAliveService() {
        val intent = Intent(this, IslandOverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expected = ComponentName(context, HyperAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(":").any { it.equals(expected, ignoreCase = true) }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val txtStatus = findViewById<TextView>(R.id.txtServiceStatus)
        val isServiceOn = isAccessibilityServiceEnabled(this)
        val isAppLogicEnabled = AppSettings.isIslandEnabled(this)

        if (!isServiceOn) {
            txtStatus.text = "Accessibility: OFF"
            txtStatus.setTextColor(0xFFFF3B30.toInt())
        } else {
            txtStatus.text = if (isAppLogicEnabled) "Island: ON" else "Island: standby"
            txtStatus.setTextColor(if (isAppLogicEnabled) 0xFF30D158.toInt() else 0xFFFFA726.toInt())
        }
    }
}
