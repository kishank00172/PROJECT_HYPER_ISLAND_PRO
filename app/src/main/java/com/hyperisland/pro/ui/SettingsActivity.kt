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
import com.hyperisland.pro.services.IslandOverlayService

class SettingsActivity : Activity() {

    private lateinit var txtWidth: TextView
    private lateinit var txtHeight: TextView
    private lateinit var txtY: TextView
    private lateinit var txtX: TextView

    private lateinit var seekWidth: SeekBar
    private lateinit var seekHeight: SeekBar
    private lateinit var seekY: SeekBar
    private lateinit var seekX: SeekBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }

        val developer = findViewById<Switch>(R.id.switchDeveloper)
        developer.isChecked = AppSettings.isDeveloperMode(this)
        developer.setOnCheckedChangeListener { _, isChecked ->
            AppSettings.setDeveloperMode(this, isChecked)
        }

        txtWidth = findViewById(R.id.txtWidthValue)
        txtHeight = findViewById(R.id.txtHeightValue)
        txtY = findViewById(R.id.txtYValue)
        txtX = findViewById(R.id.txtXValue)

        seekWidth = findViewById(R.id.seekWidth)
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
        seekHeight.max = 36      // 24..60
        seekY.max = 120          // 0..120
        seekX.max = 360          // -180..180

        loadSeekValues()

        seekWidth.setOnSeekBarChangeListener(simpleListener {
            val value = 70 + seekWidth.progress
            AppSettings.setIslandWidthDp(this, value)
            updateLabels()
            refreshOverlayIfRunning()
        })

        seekHeight.setOnSeekBarChangeListener(simpleListener {
            val value = 24 + seekHeight.progress
            AppSettings.setIslandHeightDp(this, value)
            updateLabels()
            refreshOverlayIfRunning()
        })

        seekY.setOnSeekBarChangeListener(simpleListener {
            val value = seekY.progress
            AppSettings.setIslandYDp(this, value)
            updateLabels()
            refreshOverlayIfRunning()
        })

        seekX.setOnSeekBarChangeListener(simpleListener {
            val value = seekX.progress - 180
            AppSettings.setIslandXDp(this, value)
            updateLabels()
            refreshOverlayIfRunning()
        })
    }

    private fun loadSeekValues() {
        seekWidth.progress = AppSettings.getIslandWidthDp(this) - 70
        seekHeight.progress = AppSettings.getIslandHeightDp(this) - 24
        seekY.progress = AppSettings.getIslandYDp(this)
        seekX.progress = AppSettings.getIslandXDp(this) + 180
        updateLabels()
    }

    private fun updateLabels() {
        txtWidth.text = "${AppSettings.getIslandWidthDp(this)} dp"
        txtHeight.text = "${AppSettings.getIslandHeightDp(this)} dp"
        txtY.text = "${AppSettings.getIslandYDp(this)} dp"
        txtX.text = "${AppSettings.getIslandXDp(this)} dp"
    }

    private fun refreshOverlayIfRunning() {
        if (!AppSettings.isIslandEnabled(this)) return

        val intent = Intent(this, IslandOverlayService::class.java).apply {
            action = IslandOverlayService.ACTION_REFRESH
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun simpleListener(onChanged: () -> Unit): SeekBar.OnSeekBarChangeListener {
        return object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) onChanged()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }
    }
}
