package com.hyperisland.pro.ui

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.hardware.display.DisplayManager
import android.view.Display
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
    private lateinit var txtCornerRadius: TextView
    private lateinit var txtExpandedCornerRadius: TextView
    private lateinit var txtY: TextView
    private lateinit var txtX: TextView

    private lateinit var seekWidth: SeekBar
    private lateinit var seekStage2Width: SeekBar
    private lateinit var seekExpandedWidth: SeekBar
    private lateinit var seekExpandedHeight: SeekBar
    private lateinit var seekHeight: SeekBar
    private lateinit var seekCornerRadius: SeekBar
    private lateinit var seekExpandedCornerRadius: SeekBar
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

        // b1506 / Island Boost: rate vote by Display.modeId (b1505); here is his user-visible switch.
        val note = findViewById<TextView>(R.id.txtHighRefreshNote)
        val peakHz = runCatching {
            getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
                ?.supportedModes?.maxByOrNull { it.refreshRate }?.refreshRate ?: 0f
        }.getOrDefault(0f)
        note?.text = if (peakHz > 60.5f) "Experimental · buttery morphs; thodi zyada battery (panel: ${peakHz.toInt()}Hz)"
                     else "Experimental · is phone me sirf ${peakHz.toInt()}Hz panel mila"
        val boost = findViewById<Switch>(R.id.switchHighRefresh)
        boost.isChecked = AppSettings.getIslandHighRefreshEnabled(this)
        val pin = findViewById<Switch>(R.id.switchIslandPin)
        pin.isChecked = AppSettings.getIslandPinEnabled(this)
        pin.setOnCheckedChangeListener { _, isChecked -> AppSettings.setIslandPinEnabled(this, isChecked) } // b1527
        boost.setOnCheckedChangeListener { _, isChecked ->
            AppSettings.setIslandHighRefreshEnabled(this, isChecked)
            note?.text = when {
                peakHz <= 60.5f -> "Experimental · is phone me sirf ${peakHz.toInt()}Hz panel mila"
                isChecked && peakHz >= 119f -> "Experimental · 120Hz island vote ON (restart mat maangta; agla island wake se asar)"
                isChecked -> "Experimental · island vote ON (peak panel: ${peakHz.toInt()}Hz; thodi zyada battery)"
                else -> "Experimental · buttery morphs; thodi zyada battery (panel: ${peakHz.toInt()}Hz)"
            }
        }

        // b1516 (his slam: cycler = feel nahi aata): 4 visible pills, selected one lights up blue.
        val dpBtns = listOf(
            findViewById<android.widget.Button>(R.id.btnDpOff),
            findViewById<android.widget.Button>(R.id.btnDpAura),
            findViewById<android.widget.Button>(R.id.btnDpShell),
            findViewById<android.widget.Button>(R.id.btnDpBadge),
        )
        fun paintDpChoice(v: Int) {
            dpBtns.forEachIndexed { i, b -> b?.setTextColor(if (i == v) 0xFF4DA3FF.toInt() else 0xFFAAAAAA.toInt()) }
        }
        paintDpChoice(AppSettings.getAvatarDpStyle(this))
        var refreshDpLabUI: (() -> Unit)? = null
        dpBtns.forEachIndexed { i, b ->
            b?.setOnClickListener { AppSettings.setAvatarDpStyle(this, i); paintDpChoice(i); refreshDpLabUI?.invoke() } // b1533: repaint lab for the newly-picked style
        }

        // b1526 DP Style Lab (his tuning board): live values; rebinding applies on next expand
        fun labSeek(seekId: Int, txtId: Int, unit: String, off: Int, span: Int, get: () -> Int, set: (Int) -> Unit) {
            val sk = findViewById<SeekBar>(seekId) ?: return
            val tv = findViewById<android.widget.TextView>(txtId)
            fun paint(v: Int) { tv?.text = "$unit".let { u -> if (u == "%") "$v%" else "$v $u" } }
            sk.max = span // b1530: was hardcoded 100 - past-range drags coerced silently and labels lied ("128 dp")
            sk.progress = get() - off
            paint(get())
            sk.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) { set(off + p); paint(off + p); refreshOverlayIfRunning() } } // b1531: live-apply (lab sliders never refreshed before)
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        labSeek(R.id.seekDpLabPhoto, R.id.txtDpLabPhotoValue, "dp", 28, 10, { AppSettings.getDpLabPhotoDp(this, AppSettings.getAvatarDpStyle(this)) }, { AppSettings.setDpLabPhotoDp(this, AppSettings.getAvatarDpStyle(this), it) })
        labSeek(R.id.seekDpLabRingView, R.id.txtDpLabRingViewValue, "dp", 34, 8, { AppSettings.getDpLabRingViewDp(this, AppSettings.getAvatarDpStyle(this)) }, { AppSettings.setDpLabRingViewDp(this, AppSettings.getAvatarDpStyle(this), it) })
        labSeek(R.id.seekDpLabRingStroke, R.id.txtDpLabRingStrokeValue, "dp", 1, 5, { AppSettings.getDpLabRingStrokeDp(this, AppSettings.getAvatarDpStyle(this)) }, { AppSettings.setDpLabRingStrokeDp(this, AppSettings.getAvatarDpStyle(this), it) })
        labSeek(R.id.seekDpLabRingBright, R.id.txtDpLabRingBrightValue, "%", 0, 100, { AppSettings.getDpLabRingBrightPct(this, AppSettings.getAvatarDpStyle(this)) }, { AppSettings.setDpLabRingBrightPct(this, AppSettings.getAvatarDpStyle(this), it) })
        labSeek(R.id.seekDpLabGlowStroke, R.id.txtDpLabGlowStrokeValue, "dp", 0, 80, { AppSettings.getDpLabGlowRadiusDp(this, AppSettings.getAvatarDpStyle(this)) }, { AppSettings.setDpLabGlowRadiusDp(this, AppSettings.getAvatarDpStyle(this), it) }) // b1532: glow ka apna radius
        labSeek(R.id.seekDpLabGlowAlpha, R.id.txtDpLabGlowAlphaValue, "%", 0, 100, { AppSettings.getDpLabGlowAlphaPct(this, AppSettings.getAvatarDpStyle(this)) }, { AppSettings.setDpLabGlowAlphaPct(this, AppSettings.getAvatarDpStyle(this), it) })
        labSeek(R.id.seekDpLabBadge, R.id.txtDpLabBadgeValue, "dp", 10, 12, { AppSettings.getDpLabBadgeDp(this, AppSettings.getAvatarDpStyle(this)) }, { AppSettings.setDpLabBadgeDp(this, AppSettings.getAvatarDpStyle(this), it) })

        // b1533 (his: "ye setting har mode ke liye alag alag hoga na"): style badalte hi sliders usi
        // style ki values dikhayein; glow/ring rows sirf AURA ke hain (baaki styles par photo+badge).
        fun syncDpLabVisibility() {
            val st = AppSettings.getAvatarDpStyle(this)
            val auraOnly = listOf(R.id.rowDpLabRingView, R.id.rowDpLabRingStroke, R.id.rowDpLabRingBright, R.id.rowDpLabGlowStroke, R.id.rowDpLabGlowAlpha)
            val photoBadge = listOf(R.id.rowDpLabPhoto, R.id.rowDpLabBadge)
            auraOnly.forEach { findViewById<android.view.View>(it)?.visibility = if (st == 1) android.view.View.VISIBLE else android.view.View.GONE }
            photoBadge.forEach { findViewById<android.view.View>(it)?.visibility = if (st == 0) android.view.View.GONE else android.view.View.VISIBLE }
        }
        refreshDpLabUI = {
            syncDpLabVisibility()
            // sliders ko nayi style ki values pe re-sync karo (progress reset -> label paint live hota hai)
            val pairs = listOf(
                R.id.seekDpLabPhoto to { AppSettings.getDpLabPhotoDp(this, AppSettings.getAvatarDpStyle(this)) },
                R.id.seekDpLabRingView to { AppSettings.getDpLabRingViewDp(this, AppSettings.getAvatarDpStyle(this)) },
                R.id.seekDpLabRingStroke to { AppSettings.getDpLabRingStrokeDp(this, AppSettings.getAvatarDpStyle(this)) },
                R.id.seekDpLabRingBright to { AppSettings.getDpLabRingBrightPct(this, AppSettings.getAvatarDpStyle(this)) },
                R.id.seekDpLabGlowStroke to { AppSettings.getDpLabGlowRadiusDp(this, AppSettings.getAvatarDpStyle(this)) },
                R.id.seekDpLabGlowAlpha to { AppSettings.getDpLabGlowAlphaPct(this, AppSettings.getAvatarDpStyle(this)) },
                R.id.seekDpLabBadge to { AppSettings.getDpLabBadgeDp(this, AppSettings.getAvatarDpStyle(this)) }
            )
            val mins = intArrayOf(28, 34, 1, 0, 0, 0, 10)
            pairs.forEachIndexed { idx, (id, getv) ->
                findViewById<android.widget.SeekBar>(id)?.let { sk -> if (sk.progress != getv() - mins[idx]) sk.progress = getv() - mins[idx] }
            }
        }
        syncDpLabVisibility()

        // b1533 (his point 1: export karne wala button): DP Lab section me button -> Downloads me file.
        findViewById<android.widget.Button>(R.id.btnExportDpLab)?.setOnClickListener {
            if (android.os.Build.VERSION.SDK_INT < 29) {
                android.widget.Toast.makeText(this, "Export ke liye Android 10+ chahiye", android.widget.Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            try {
                val names = arrayOf("OFF", "AURA", "SHELL", "BADGE")
                val sb2 = StringBuilder("HIP DP Style Lab export\n")
                for (sty in 0..3) {
                    sb2.append("[" + names[sty] + "] photo=" + AppSettings.getDpLabPhotoDp(this, sty) +
                        " ringView=" + AppSettings.getDpLabRingViewDp(this, sty) +
                        " ringStroke=" + AppSettings.getDpLabRingStrokeDp(this, sty) +
                        " ringBright=" + AppSettings.getDpLabRingBrightPct(this, sty) +
                        " glowRadius=" + AppSettings.getDpLabGlowRadiusDp(this, sty) +
                        " glowAlpha=" + AppSettings.getDpLabGlowAlphaPct(this, sty) +
                        " badge=" + AppSettings.getDpLabBadgeDp(this, sty) + "\n")
                }
                sb2.append("currentStyle=" + names[AppSettings.getAvatarDpStyle(this)] + "\n")
                val fn = "HIP-DPLab-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date()) + ".txt"
                val cv = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fn)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                if (uri != null) contentResolver.openOutputStream(uri)?.use { it.write(sb2.toString().toByteArray()) }
                android.widget.Toast.makeText(this, "Saved: Downloads/" + fn, android.widget.Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                android.widget.Toast.makeText(this, "Export fail: " + e.message, android.widget.Toast.LENGTH_LONG).show()
            }
        }

        txtWidth = findViewById(R.id.txtWidthValue)
        txtStage2Width = findViewById(R.id.txtStage2WidthValue)
        txtExpandedWidth = findViewById(R.id.txtExpandedWidthValue)
        txtExpandedHeight = findViewById(R.id.txtExpandedHeightValue)
        txtHeight = findViewById(R.id.txtHeightValue)
        txtCornerRadius = findViewById(R.id.txtCornerRadiusValue)
        txtExpandedCornerRadius = findViewById(R.id.txtExpandedCornerRadiusValue)
        txtY = findViewById(R.id.txtYValue)
        txtX = findViewById(R.id.txtXValue)

        seekWidth = findViewById(R.id.seekWidth)
        seekStage2Width = findViewById(R.id.seekStage2Width)
        seekExpandedWidth = findViewById(R.id.seekExpandedWidth)
        seekExpandedHeight = findViewById(R.id.seekExpandedHeight)
        seekHeight = findViewById(R.id.seekHeight)
        seekCornerRadius = findViewById(R.id.seekCornerRadius)
        seekExpandedCornerRadius = findViewById(R.id.seekExpandedCornerRadius)
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
        seekWidth.max = 190             // 70..260
        seekStage2Width.max = 200        // 100..300
        seekExpandedWidth.max = 240      // 180..420
        seekExpandedHeight.max = 150     // 70..220
        seekHeight.max = 36             // 24..60
        seekCornerRadius.max = 30       // 0..30
        seekExpandedCornerRadius.max = 60 // 0..60
        seekY.max = 120                 // 0..120
        seekX.max = 360                 // -180..180

        loadSeekValues()

        seekWidth.setOnSeekBarChangeListener(simpleListener { AppSettings.setIslandWidthDp(this, 70 + seekWidth.progress); updateLabels(); refreshOverlayIfRunning() })
        seekStage2Width.setOnSeekBarChangeListener(simpleListener { AppSettings.setIslandStage2WidthDp(this, 100 + seekStage2Width.progress); updateLabels(); refreshOverlayIfRunning() })
        seekExpandedWidth.setOnSeekBarChangeListener(simpleListener { AppSettings.setIslandExpandedWidthDp(this, 180 + seekExpandedWidth.progress); updateLabels(); refreshOverlayIfRunning() })
        seekExpandedHeight.setOnSeekBarChangeListener(simpleListener { AppSettings.setIslandExpandedHeightDp(this, 70 + seekExpandedHeight.progress); updateLabels(); refreshOverlayIfRunning() })
        seekHeight.setOnSeekBarChangeListener(simpleListener { AppSettings.setIslandHeightDp(this, 24 + seekHeight.progress); updateLabels(); refreshOverlayIfRunning() })
        seekCornerRadius.setOnSeekBarChangeListener(simpleListener { AppSettings.setIslandCornerRadiusDp(this, seekCornerRadius.progress); updateLabels(); refreshOverlayIfRunning() })
        seekExpandedCornerRadius.setOnSeekBarChangeListener(simpleListener { AppSettings.setIslandExpandedCornerRadiusDp(this, seekExpandedCornerRadius.progress); updateLabels(); refreshOverlayIfRunning() })
        seekY.setOnSeekBarChangeListener(simpleListener { AppSettings.setIslandYDp(this, seekY.progress); updateLabels(); refreshOverlayIfRunning() })
        seekX.setOnSeekBarChangeListener(simpleListener { AppSettings.setIslandXDp(this, seekX.progress - 180); updateLabels(); refreshOverlayIfRunning() })
    }

    private fun loadSeekValues() {
        seekWidth.progress = AppSettings.getIslandWidthDp(this) - 70
        seekStage2Width.progress = AppSettings.getIslandStage2WidthDp(this) - 100
        seekExpandedWidth.progress = AppSettings.getIslandExpandedWidthDp(this) - 180
        seekExpandedHeight.progress = AppSettings.getIslandExpandedHeightDp(this) - 70
        seekHeight.progress = AppSettings.getIslandHeightDp(this) - 24
        seekCornerRadius.progress = AppSettings.getIslandCornerRadiusDp(this)
        seekExpandedCornerRadius.progress = AppSettings.getIslandExpandedCornerRadiusDp(this)
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
        txtCornerRadius.text = "${AppSettings.getIslandCornerRadiusDp(this)} dp"
        txtExpandedCornerRadius.text = "${AppSettings.getIslandExpandedCornerRadiusDp(this)} dp"
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
