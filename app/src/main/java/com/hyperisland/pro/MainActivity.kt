package com.hyperisland.pro

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import com.hyperisland.pro.ui.PermissionDoctorActivity
import com.hyperisland.pro.ui.SettingsActivity
import com.hyperisland.pro.ui.TestLabActivity

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<TextView>(R.id.txtBuildInfo).text = buildString {
            append("Version: ${BuildConfig.VERSION_NAME}\n")
            append("Package: ${BuildConfig.APPLICATION_ID}\n")
            append("Min SDK: 33 | Kotlin + XML | No fake cutout")
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
}
