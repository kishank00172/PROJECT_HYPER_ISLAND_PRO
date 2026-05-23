package com.hyperisland.pro.ui

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import com.hyperisland.pro.R

class TestLabActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_test_lab)
        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<TextView>(R.id.txtTests).text = buildString {
            append("Phase 1: Test real AMOLED pill overlay\n")
            append("Phase 2: Test expand/collapse animation\n")
            append("Phase 3: Test real notification island\n")
            append("Phase 5: Test media/music island\n")
            append("Phase 6: Test timer island\n")
            append("Phase 7: Test download/install island\n")
            append("Phase 9: Test bubble physics\n\n")
            append("Rule: no fake working buttons before the engine exists.")
        }
    }
}
