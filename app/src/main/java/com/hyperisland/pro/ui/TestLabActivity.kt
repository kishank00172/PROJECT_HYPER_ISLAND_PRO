package com.hyperisland.pro.ui

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.hyperisland.pro.R
import com.hyperisland.pro.services.HyperAccessibilityService
import com.hyperisland.pro.services.HyperNotificationListenerService

class TestLabActivity : Activity() {

    private lateinit var txtStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_test_lab)

        // Using exact IDs from activity_test_lab.xml
        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        txtStatus = findViewById(R.id.txtTests) // ID was txtTests, not txtLabStatus

        findViewById<Button>(R.id.btnExpandIsland).setOnClickListener {
            HyperAccessibilityService.expandIslandFromApp(this)
        }

        findViewById<Button>(R.id.btnCollapseIsland).setOnClickListener {
            HyperAccessibilityService.collapseIslandFromApp(this)
        }

        findViewById<Button>(R.id.btnToggleIsland).setOnClickListener {
            HyperAccessibilityService.toggleExpandFromApp(this)
        }

        findViewById<Button>(R.id.btnTestNotification).setOnClickListener {
            HyperAccessibilityService.showNotificationFromApp(
                context = this,
                packageName = packageName,
                appName = "Test Notification",
                title = "HYPER ISLAND PRO",
                message = "This is a real Phase 3 island notification simulation.",
                postTime = System.currentTimeMillis(),
                contentIntent = null,
                actions = emptyList() 
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val sb = StringBuilder()
        sb.append("Notification Listener:\n")
        sb.append(if (HyperNotificationListenerService.isConnected) "Connected" else "Disconnected")
        sb.append("\n\n")
        sb.append("Last Event Trace:\n")
        sb.append(HyperNotificationListenerService.lastDebugMessage)
        sb.append("\n\n")
        sb.append("Accessibility Engine:\n")
        sb.append(if (HyperAccessibilityService.isConnected()) "Connected" else "Disconnected")
        
        txtStatus.text = sb.toString()
    }
}
