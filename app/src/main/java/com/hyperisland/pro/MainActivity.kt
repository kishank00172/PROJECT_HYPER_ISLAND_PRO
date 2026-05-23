package com.hyperisland.pro

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.hyperisland.pro.core.AppSettings
import com.hyperisland.pro.services.IslandOverlayService
import com.hyperisland.pro.ui.PermissionDoctorActivity
import com.hyperisland.pro.ui.SettingsActivity
import com.hyperisland.pro.ui.TestLabActivity

class MainActivity : Activity() {

    private lateinit var txtIslandStatus: TextView
    private lateinit var btnIslandToggle: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Grant overlay permission first", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, PermissionDoctorActivity::class.java))
            return
        }

        requestPostNotificationIfNeeded()

        AppSettings.setIslandEnabled(this, true)
        startIslandService(IslandOverlayService.ACTION_SHOW)
        refreshIslandUi()
    }

    private fun stopIsland() {
        AppSettings.setIslandEnabled(this, false)
        startIslandService(IslandOverlayService.ACTION_HIDE)
        refreshIslandUi()
    }

    private fun startIslandService(action: String) {
        val intent = Intent(this, IslandOverlayService::class.java).apply {
            this.action = action
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

    private fun refreshIslandUi() {
        val enabled = AppSettings.isIslandEnabled(this)

        txtIslandStatus.text = if (enabled) {
            "Phase 1 overlay status: ENABLED\nTap the black pill to verify touch detection."
        } else {
            "Phase 1 overlay status: OFF\nEnable to show the real AMOLED black pill."
        }

        btnIslandToggle.text = if (enabled) {
            "TURN ISLAND OFF"
        } else {
            "TURN ISLAND ON"
        }
    }
}
