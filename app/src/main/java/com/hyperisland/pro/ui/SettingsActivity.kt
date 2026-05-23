package com.hyperisland.pro.ui

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.Switch
import com.hyperisland.pro.R
import com.hyperisland.pro.core.AppSettings

class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }

        val developer = findViewById<Switch>(R.id.switchDeveloper)
        developer.isChecked = AppSettings.isDeveloperMode(this)
        developer.setOnCheckedChangeListener { _, isChecked ->
            AppSettings.setDeveloperMode(this, isChecked)
        }
    }
}
