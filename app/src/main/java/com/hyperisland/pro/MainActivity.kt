package com.hyperisland.pro

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
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

        findViewById<Button>(R.id.btnIslandToggle).setOnClickListener {
            val isServiceOn = isAccessibilityServiceEnabled(this)
            if (isServiceOn) {
                val isAppLogicEnabled = AppSettings.isIslandEnabled(this)
                if (isAppLogicEnabled) {
                    // Turn OFF
                    AppSettings.setIslandEnabled(this, false)
                    HyperAccessibilityService.hideIslandFromApp(this)
                } else {
                    // Turn ON
                    AppSettings.setIslandEnabled(this, true)
                    HyperAccessibilityService.showIslandFromApp(this)
                }
            } else {
                startActivity(Intent(this, PermissionDoctorActivity::class.java))
            }
            updateStatus()
        }

        findViewById<Button>(R.id.btnPermissionDoctor).setOnClickListener {
            startActivity(Intent(this, PermissionDoctorActivity::class.java))
        }

        findViewById<Button>(R.id.btnTestLab).setOnClickListener {
            startActivity(Intent(this, TestLabActivity::class.java))
        }

        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        
        findViewById<TextView>(R.id.txtBuildInfo)?.text = "Build: Phase 3.2 - Touch & Toggle Fix"
    }

    private fun startKeepAliveService() {
        val intent = Intent(this, IslandOverlayService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (_: Exception) {}
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
        val txtStatus = findViewById<TextView>(R.id.txtIslandStatus)
        val btnToggle = findViewById<Button>(R.id.btnIslandToggle)
        
        val isServiceOn = isAccessibilityServiceEnabled(this)
        val isAppLogicEnabled = AppSettings.isIslandEnabled(this)

        if (!isServiceOn) {
            txtStatus?.text = "Phase 3 status: SERVICE OFF"
            txtStatus?.setTextColor(0xFFFF3B30.toInt())
            btnToggle?.text = "TURN ISLAND ON"
        } else {
            if (isAppLogicEnabled) {
                txtStatus?.text = "Phase 3 status: RUNNING"
                txtStatus?.setTextColor(0xFF30D158.toInt())
                btnToggle?.text = "TURN ISLAND OFF"
            } else {
                txtStatus?.text = "Phase 3 status: STANDBY"
                txtStatus?.setTextColor(0xFFFFA726.toInt())
                btnToggle?.text = "TURN ISLAND ON"
            }
        }
    }
}
