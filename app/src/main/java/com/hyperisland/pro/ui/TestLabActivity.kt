package com.hyperisland.pro.ui

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.hyperisland.pro.R
import com.hyperisland.pro.core.AppSettings
import com.hyperisland.pro.services.HyperAccessibilityService

class TestLabActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_test_lab)

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }

        findViewById<TextView>(R.id.txtTests).text = buildString {
            append("Phase 2 active tests:\n")
            append("• Expand island\n")
            append("• Collapse island\n")
            append("• Toggle expand/collapse\n\n")
            append("These controls send real commands to the Accessibility Overlay engine. No fake UI state.")
        }

        findViewById<Button>(R.id.btnExpandIsland).setOnClickListener {
            if (!ensureIslandEnabled()) return@setOnClickListener
            val ok = HyperAccessibilityService.expandIslandFromApp(this)
            showCommandResult(ok, "Expand command sent")
        }

        findViewById<Button>(R.id.btnCollapseIsland).setOnClickListener {
            if (!ensureIslandEnabled()) return@setOnClickListener
            val ok = HyperAccessibilityService.collapseIslandFromApp(this)
            showCommandResult(ok, "Collapse command sent")
        }

        findViewById<Button>(R.id.btnToggleIsland).setOnClickListener {
            if (!ensureIslandEnabled()) return@setOnClickListener
            val ok = HyperAccessibilityService.toggleExpandFromApp(this)
            showCommandResult(ok, "Toggle command sent")
        }
    }

    private fun ensureIslandEnabled(): Boolean {
        if (AppSettings.isIslandEnabled(this)) return true

        Toast.makeText(this, "Turn island ON first", Toast.LENGTH_SHORT).show()
        return false
    }

    private fun showCommandResult(success: Boolean, successText: String) {
        if (success) {
            Toast.makeText(this, successText, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(
                this,
                "Accessibility engine not connected. Reopen app or toggle Accessibility service.",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
