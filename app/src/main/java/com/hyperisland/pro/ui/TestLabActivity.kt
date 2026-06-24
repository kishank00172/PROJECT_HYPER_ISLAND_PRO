package com.hyperisland.pro.ui

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import com.hyperisland.pro.R
import com.hyperisland.pro.core.AppSettings
import com.hyperisland.pro.services.HyperAccessibilityService

class TestLabActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_test_lab)

        findViewById<Button>(R.id.btnExpandIsland).setOnClickListener { HyperAccessibilityService.expandIslandFromApp(this) }
        findViewById<Button>(R.id.btnCollapseIsland).setOnClickListener { HyperAccessibilityService.collapseIslandFromApp(this) }
        findViewById<Button>(R.id.btnToggleIsland).setOnClickListener { HyperAccessibilityService.toggleExpandFromApp(this) }

        findViewById<Button>(R.id.btnTestNotification).setOnClickListener {
            if (!AppSettings.isIslandEnabled(this)) { Toast.makeText(this, "Turn island ON first", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            HyperAccessibilityService.showNotificationFromApp(
                context = this,
                packageName = packageName,
                appName = "Test Lab",
                title = "Phase 3 Check",
                message = "Testing Grid Layout & FIFO Queue",
                postTime = System.currentTimeMillis(),
                contentIntent = null,
                actions = emptyList()
            )
        }
    }
}
