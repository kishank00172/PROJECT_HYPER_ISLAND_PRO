package com.hyperisland.pro.ui

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.hyperisland.pro.R
import com.hyperisland.pro.core.AppSettings
import com.hyperisland.pro.services.HyperAccessibilityService
import com.hyperisland.pro.services.IslandOverlayService

class SettingsActivity : Activity() {

    private lateinit var txtWidth: TextView
    private lateinit var txtStage2Width: TextView
    private lateinit var txtExpandedWidth: TextView
    private lateinit var txtExpandedHeight: TextView
    private lateinit var txtHeight: TextView
    private lateinit var txtY: TextView
    private lateinit var txtX: TextView

    private lateinit var seekWidth: SeekBar
    private lateinit var seekStage2Width: SeekBar
    private lateinit var seekExpandedWidth: SeekBar
    private lateinit var seekExpandedHeight: SeekBar
    private lateinit var seekHeight: SeekBar
    private lateinit var seekY: SeekBar
    private lateinit var seekX: SeekBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppSettings.ensurePhaseDefaults(this)
        setContentView(R.layout.activity_settings)

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }

        val developer = findViewById<Switch>(R.id.switchDeveloper)
        developer.isChecked = AppSettings.isDeveloperMode(this)
        developer.setOnCheckedChangeListener { _, isChecked -> AppSettings.setDeveloperMode(this, isChecked) }

        txtWidth = findViewById(R.id.txtWidthValue)
        txtStage2Width = findViewById(R.id.txtStage2WidthValue)
        txtExpandedWidth = findViewById(R.id.txtExpandedWidthValue)
        txtExpandedHeight = findViewById(R.id.txtExpandedHeightValue)
        txtHeight = findViewById(R.id.txtHeightValue)
        txtY = findViewById(R.id.txtYValue)
        txtX = findViewById(R.id.txtXValue)

        seekWidth = findViewById(R.id.seekWidth)
        seekStage2Width = findViewById(R.id.seekStage2Width)
        seekExpandedWidth = findViewById(R.id.seekExpandedWidth)
        seekExpandedHeight = findViewById(R.id.seekExpandedHeight)
        seekHeight = findViewById(R.id.seekHeight)
        seekY = findViewById(R.id.seekY)
        seekX = findViewById(R.id.seekX)

        setupSeekBars()

        findViewById<Button>(R.id.btnResetOverlay).setOnClickListener {
            AppSettings.resetIslandDefaults(this)
            loadSeekValues()
            refreshOverlayIfRunning()
        }
    }

    private fun setupSeekBars() {
        seekWidth.max = 190      // 70..260
        seekStage2Width.max = 200 // 100..300
        seekExpandedWidth.max = 240 // 180..420
        seekExpandedHeight.max = 150 // 70..220
        seekHeight.max = 36      // 24..60
        seekY.max = 120          // 0..120
        seekX.max = 360          // -180..180

        loadSeekValues()

        seekWidth.setOnSeekBarChangeListener(simpleListener {
            AppSettings.setIslandWidthDp(this, 70 + seekWidth.progress)
            updateLabels(); refreshOverlayIfRunning()
        })

        seekStage2Width.setOnSeekBarChangeListener(simpleListener {
            AppSettings.setIslandStage2WidthDp(this, 100 + seekStage2Width.progress)
            updateLabels(); refreshOverlayIfRunning()
        })

        seekExpandedWidth.setOnSeekBarChangeListener(simpleListener {
            AppSettings.setIslandExpandedWidthDp(this, 180 + seekExpandedWidth.progress)
            updateLabels(); refreshOverlayIfRunning()
        })

        seekExpandedHeight.setOnSeekBarChangeListener(simpleListener {
            AppSettings.setIslandExpandedHeightDp(this, 70 + seekExpandedHeight.progress)
            updateLabels(); refreshOverlayIfRunning()
        })

        seekHeight.setOnSeekBarChangeListener(simpleListener {
            AppSettings.setIslandHeightDp(this, 24 + seekHeight.progress)
            updateLabels(); refreshOverlayIfRunning()
        })

        seekY.setOnSeekBarChangeListener(simpleListener {
            AppSettings.setIslandYDp(this, seekY.progress)
            updateLabels(); refreshOverlayIfRunning()
        })

        seekX.setOnSeekBarChangeListener(simpleListener {
            AppSettings.setIslandXDp(this, seekX.progress - 180)
            updateLabels(); refreshOverlayIfRunning()
        })
    }

    private fun loadSeekValues() {
        seekWidth.progress = AppSettings.getIslandWidthDp(this) - 70
        seekStage2Width.progress = AppSettings.getIslandStage2WidthDp(this) - 100
        seekExpandedWidth.progress = AppSettings.getIslandExpandedWidthDp(this) - 180
        seekExpandedHeight.progress = AppSettings.getIslandExpandedHeightDp(this) - 70
        seekHeight.progress = AppSettings.getIslandHeightDp(this) - 24
        seekY.progress = AppSettings.getIslandYDp(this)
        seekX.progress = AppSettings.getIslandXDp(this) + 180
        updateLabels()
    }

    private fun updateLabels() {
        txtWidth.text = "${AppSettings.getIslandWidthDp(this)} dp"
        txtStage2Width.text = "${AppSettings.getIslandStage2WidthDp(this)} dp"
        txtExpandedWidth.text = "${AppSettings.getIslandExpandedWidthDp(this)} dp"
        txtExpandedHeight.text = "${AppSettings.getIslandExpandedHeightDp(this)} dp"
        txtHeight.text = "${AppSettings.getIslandHeightDp(this)} dp"
        txtY.text = "${AppSettings.getIslandYDp(this)} dp"
        txtX.text = "${AppSettings.getIslandXDp(this)} dp"
    }

    private fun refreshOverlayIfRunning() {
        if (!AppSettings.isIslandEnabled(this)) return
        if (!HyperAccessibilityService.refreshIslandFromApp(this)) {
            val intent = Intent(this, IslandOverlayService::class.java).apply { action = IslandOverlayService.ACTION_REFRESH }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        }
    }

    private fun simpleListener(onChanged: () -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { if (f) onChanged() }
        override fun onStartTrackingTouch(s: SeekBar?) {}
        override fun onStopTrackingTouch(s: SeekBar?) {}
    }
}
