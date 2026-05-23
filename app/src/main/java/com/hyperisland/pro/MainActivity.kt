package com.hyperisland.pro

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.hyperisland.pro.core.AppSettings
import com.hyperisland.pro.services.HyperAccessibilityService
import com.hyperisland.pro.services.IslandOverlayService
import com.hyperisland.pro.ui.PermissionDoctorActivity
import com.hyperisland.pro.ui.SettingsActivity
import com.hyperisland.pro.ui.TestLabActivity

class MainActivity : Activity() {

    private lateinit var txtIslandStatus: TextView
    private lateinit var btnIslandToggle: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AppSettings.ensurePhaseDefaults(this)

        setContentView(R.layout.activity_main)

        txtIslandStatus = findViewById(R.id.txtIslandStatus)
        btnIslandToggle = findViewById(R.id.btnIslandToggle)

        findViewById<TextView>(R.id.txtBuildInfo).text = buildString {
            append("Version: ${BuildConfig.VERSION_NAME}\n")
            append("Package: ${BuildConfig.APPLICATION_ID}\n")
            append("Min SDK: 33 | Kotlin + XML | No fake cutout")
        }

        btnIslandToggle.setOnClickListener {
            if (AppSettings.isIslandEnabled(this)) {
                stopIsland()
            } else {
                startIsland()
            }
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
    }

    override fun onResume() {
        super.onResume()
        refreshIslandUi()
    }

    private fun startIsland() {
        requestPostNotificationIfNeeded()

        if (isAccessibilityServiceEnabled()) {
            val started = HyperAccessibilityService.showIslandFromApp(this)

            if (started) {
                stopFallbackService()
                refreshIslandUi()
                Toast.makeText(this, "Accessibility overlay started", Toast.LENGTH_SHORT).show()
                return
            }

            Toast.makeText(
                this,
                "Accessibility service enabled but not connected yet. Reopen app or wait a moment.",
                Toast.LENGTH_LONG
            ).show()
        }

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Grant overlay permission first", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, PermissionDoctorActivity::class.java))
            return
        }

        AppSettings.setIslandEnabled(this, true)
        AppSettings.setOverlayEngine(this, AppSettings.ENGINE_APPLICATION)
        startFallbackService(IslandOverlayService.ACTION_SHOW)
        refreshIslandUi()
        Toast.makeText(this, "Fallback application overlay started", Toast.LENGTH_SHORT).show()
    }

    private fun stopIsland() {
        HyperAccessibilityService.hideIslandFromApp(this)
        startFallbackService(IslandOverlayService.ACTION_HIDE)

        AppSettings.setIslandEnabled(this, false)
        AppSettings.setOverlayEngine(this, AppSettings.ENGINE_NONE)

        refreshIslandUi()

        Toast.makeText(this, "Island stop command sent", Toast.LENGTH_SHORT).show()
    }

    private fun stopFallbackService() {
        startFallbackService(IslandOverlayService.ACTION_HIDE)
    }

    private fun startFallbackService(action: String) {
        val intent = Intent(this, IslandOverlayService::class.java).apply {
            this.action = action
        }

        if (action == IslandOverlayService.ACTION_HIDE) {
            startService(intent)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun requestPostNotificationIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2001)
            }
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(this, HyperAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return enabled.split(":").any { it.equals(expected, ignoreCase = true) }
    }

    private fun refreshIslandUi() {
        val enabled = AppSettings.isIslandEnabled(this)
        val engine = AppSettings.getOverlayEngine(this)

        txtIslandStatus.text = if (enabled) {
            when (engine) {
                AppSettings.ENGINE_ACCESSIBILITY -> {
                    "Phase 1.1 status: ENABLED\nEngine: Accessibility Overlay\nStatus bar overdraw test: PASS on your device."
                }

                AppSettings.ENGINE_APPLICATION -> {
                    "Phase 1.1 status: ENABLED\nEngine: Application Overlay fallback\nThis may appear below status bar icons on HyperOS."
                }

                else -> {
                    "Phase 1.1 status: ENABLED\nEngine: Unknown"
                }
            }
        } else {
            "Phase 1.1 status: OFF\nEnable to show the real AMOLED black pill."
        }

        btnIslandToggle.text = if (enabled) {
            "TURN ISLAND OFF"
        } else {
            "TURN ISLAND ON"
        }
    }
}
