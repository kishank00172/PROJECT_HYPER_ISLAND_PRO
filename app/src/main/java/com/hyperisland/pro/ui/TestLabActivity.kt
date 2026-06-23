package com.hyperisland.pro.ui

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import com.hyperisland.pro.R
import com.hyperisland.pro.services.HyperAccessibilityService

class TestLabActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_test_lab)

        findViewById<Button>(R.id.btnTestNotification).setOnClickListener {
            HyperAccessibilityService.showNotificationFromApp(
                context = this,
                packageName = packageName,
                appName = "Test Lab",
                title = "Phase 3 Check",
                message = "Fluid Expansion & FIFO Queue Test",
                postTime = System.currentTimeMillis(),
                contentIntent = null,
                actions = emptyList()
            )
        }
    }
}
