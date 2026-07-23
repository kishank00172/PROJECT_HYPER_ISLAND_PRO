package com.hyperisland.pro.ui

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.hyperisland.pro.R
import com.hyperisland.pro.core.AppSettings
import com.hyperisland.pro.services.HyperAccessibilityService

class TestLabActivity : Activity() {
    private var isBindingRadio = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_test_lab)

        bindReplyAnimationLab()
        bindLiquidCalibration()
        bindPillIconLab()

        findViewById<Button>(R.id.btnExpandIsland).setOnClickListener { HyperAccessibilityService.expandIslandFromApp(this) }
        findViewById<Button>(R.id.btnCollapseIsland).setOnClickListener { HyperAccessibilityService.collapseIslandFromApp(this) }
        findViewById<Button>(R.id.btnToggleIsland).setOnClickListener { HyperAccessibilityService.toggleExpandFromApp(this) }
        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }

        findViewById<Button>(R.id.btnTestNotification).setOnClickListener {
            if (!AppSettings.isIslandEnabled(this)) {
                Toast.makeText(this, "Turn island ON first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
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

    private fun bindReplyAnimationLab() {
        // Reply Morph Lab: switch modes and launch real overlay previews.
        val radioGroup = findViewById<RadioGroup>(R.id.radioReplyAnimationMode)
        isBindingRadio = true
        radioGroup.check(idForReplyMode(AppSettings.getReplyAnimationMode(this)))
        isBindingRadio = false
        updateTestInfo()

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            if (isBindingRadio) return@setOnCheckedChangeListener
            val mode = modeForRadioId(checkedId)
            AppSettings.setReplyAnimationMode(this, mode)
            updateTestInfo()
            Toast.makeText(this, "Reply animation: ${AppSettings.getReplyAnimationModeName(mode)}", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnPreviewReplyFirst).setOnClickListener {
            previewReplyAnimation(replySecond = false)
        }

        findViewById<Button>(R.id.btnPreviewReplySecond).setOnClickListener {
            previewReplyAnimation(replySecond = true)
        }
    }

    private fun bindLiquidCalibration() {
        // Temporary Liquid textbox calibration; final values will be hardcoded after device testing.
        bindSeekBar(
            seekId = R.id.seekLiquidHeight,
            labelId = R.id.txtLiquidHeightValue,
            label = "Height / Motai",
            min = 32,
            max = 64,
            get = { AppSettings.getReplyLiquidHeightDp(this) },
            set = { AppSettings.setReplyLiquidHeightDp(this, it) }
        )
        bindSeekBar(
            seekId = R.id.seekLiquidRadius,
            labelId = R.id.txtLiquidRadiusValue,
            label = "Corner Radius",
            min = 8,
            max = 34,
            get = { AppSettings.getReplyLiquidRadiusDp(this) },
            set = { AppSettings.setReplyLiquidRadiusDp(this, it) }
        )
        bindSeekBar(
            seekId = R.id.seekLiquidLeftGap,
            labelId = R.id.txtLiquidLeftGapValue,
            label = "Left Gap / Length Start",
            min = 0,
            max = 48,
            get = { AppSettings.getReplyLiquidLeftGapDp(this) },
            set = { AppSettings.setReplyLiquidLeftGapDp(this, it) }
        )
        bindSeekBar(
            seekId = R.id.seekLiquidEdgeGap,
            labelId = R.id.txtLiquidEdgeGapValue,
            label = "Right + Bottom Gap / Placing",
            min = 0,
            max = 40,
            get = { AppSettings.getReplyLiquidEdgeGapDp(this) },
            set = { AppSettings.setReplyLiquidEdgeGapDp(this, it) }
        )

        findViewById<Button>(R.id.btnPreviewLiquidCalibration).setOnClickListener {
            AppSettings.setReplyAnimationMode(this, AppSettings.REPLY_ANIM_LIQUID_FILL)
            updateTestInfo()
            previewReplyAnimation(replySecond = false)
        }
    }

    private fun bindSeekBar(
        seekId: Int,
        labelId: Int,
        label: String,
        min: Int,
        max: Int,
        get: () -> Int,
        set: (Int) -> Unit
    ) {
        val seekBar = findViewById<SeekBar>(seekId)
        val labelView = findViewById<TextView>(labelId)
        seekBar.max = max - min
        fun update(value: Int) {
            labelView.text = "$label: ${value}dp"
        }
        val initial = get().coerceIn(min, max)
        seekBar.progress = initial - min
        update(initial)
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = min + progress
                set(value)
                update(value)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun bindPillIconLab() {
        // Temporary lab for comparing automatic pill icon render paths.
        val modeGroup = findViewById<RadioGroup>(R.id.radioPillIconMode)
        modeGroup.check(idForPillIconMode(AppSettings.getPillIconRenderMode(this)))
        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = pillIconModeForId(checkedId)
            AppSettings.setPillIconRenderMode(this, mode)
            Toast.makeText(this, "Pill icon: ${AppSettings.getPillIconRenderModeName(mode)}", Toast.LENGTH_SHORT).show()
        }

        findViewById<RadioGroup>(R.id.radioPillIconApp).check(R.id.radioPillAppTelegram)
        findViewById<Button>(R.id.btnPreviewPillIcon).setOnClickListener {
            if (!AppSettings.isIslandEnabled(this)) {
                Toast.makeText(this, "Turn island ON first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val pkg = selectedPillPreviewPackage()
            val ok = HyperAccessibilityService.previewPillIconFromApp(this, pkg, 7)
            if (!ok) Toast.makeText(this, "Accessibility service not connected", Toast.LENGTH_SHORT).show()
        }
    }

    private fun selectedPillPreviewPackage(): String {
        return when (findViewById<RadioGroup>(R.id.radioPillIconApp).checkedRadioButtonId) {
            R.id.radioPillAppWhatsApp -> "com.whatsapp"
            R.id.radioPillAppInstagram -> "com.instagram.android"
            R.id.radioPillAppMessages -> "com.google.android.apps.messaging"
            else -> "org.telegram.messenger"
        }
    }

    private fun idForPillIconMode(mode: Int): Int = when (mode) {
        AppSettings.PILL_ICON_SMALL_ONLY -> R.id.radioPillIconSmallOnly
        AppSettings.PILL_ICON_ADAPTIVE_FOREGROUND -> R.id.radioPillIconAdaptive
        AppSettings.PILL_ICON_LAUNCHER -> R.id.radioPillIconLauncher
        AppSettings.PILL_ICON_GENERIC -> R.id.radioPillIconGeneric
        else -> R.id.radioPillIconAuto
    }

    private fun pillIconModeForId(id: Int): Int = when (id) {
        R.id.radioPillIconSmallOnly -> AppSettings.PILL_ICON_SMALL_ONLY
        R.id.radioPillIconAdaptive -> AppSettings.PILL_ICON_ADAPTIVE_FOREGROUND
        R.id.radioPillIconLauncher -> AppSettings.PILL_ICON_LAUNCHER
        R.id.radioPillIconGeneric -> AppSettings.PILL_ICON_GENERIC
        else -> AppSettings.PILL_ICON_AUTO
    }

    private fun previewReplyAnimation(replySecond: Boolean) {
        if (!AppSettings.isIslandEnabled(this)) {
            Toast.makeText(this, "Turn island ON first", Toast.LENGTH_SHORT).show()
            return
        }
        val ok = HyperAccessibilityService.previewReplyAnimationFromApp(this, replySecond)
        if (!ok) {
            Toast.makeText(this, "Accessibility service not connected", Toast.LENGTH_SHORT).show()
            return
        }
        val side = if (replySecond) "Reply second/right" else "Reply first"
        Toast.makeText(this, "Preview: $side • ${AppSettings.getReplyAnimationModeName(AppSettings.getReplyAnimationMode(this))}", Toast.LENGTH_SHORT).show()
    }

    private fun updateTestInfo() {
        val mode = AppSettings.getReplyAnimationMode(this)
        val modeName = AppSettings.getReplyAnimationModeName(mode)
        val description = when (mode) {
            AppSettings.REPLY_ANIM_CLASSIC_LAYOUT -> "Old layout-width morph. Good for true resize feel, but can be choppy."
            AppSettings.REPLY_ANIM_GPU_SMOOTH -> "Smooth GPU scale. Fast and stable, but right-side Reply may expand from its own lane."
            AppSettings.REPLY_ANIM_MAGNETIC_DOCK -> "Capsule Chrome baseline: anchored edge, geometry-derived sibling wipe, no decoration."
            AppSettings.REPLY_ANIM_LIQUID_FILL -> "Liquid Parallax: slower edge travel, soft under-layer, subtle straight sheen."
            AppSettings.REPLY_ANIM_ELASTIC_BUBBLE -> "HyperOS Snap: fast anchored edge, lower capsule line, visible settle pulse."
            AppSettings.REPLY_ANIM_MINIMAL_PRO -> "Fast clean transition. Less flashy, more utility-focused."
            else -> "Clean direct ghost morph. No sheen, no bounce — baseline for origin continuity."
        }
        findViewById<TextView>(R.id.txtTests).text =
            "Selected Reply Morph:\n$modeName\n\n$description\n\nUse preview buttons below:\n• Reply first = WhatsApp style\n• Reply second/right = Google Messages style"
    }

    private fun idForReplyMode(mode: Int): Int = when (mode) {
        AppSettings.REPLY_ANIM_CLASSIC_LAYOUT -> R.id.radioReplyClassicLayout
        AppSettings.REPLY_ANIM_GPU_SMOOTH -> R.id.radioReplyGpuSmooth
        AppSettings.REPLY_ANIM_MAGNETIC_DOCK -> R.id.radioReplyMagneticDock
        AppSettings.REPLY_ANIM_LIQUID_FILL -> R.id.radioReplyLiquidFill
        AppSettings.REPLY_ANIM_ELASTIC_BUBBLE -> R.id.radioReplyElasticBubble
        AppSettings.REPLY_ANIM_MINIMAL_PRO -> R.id.radioReplyMinimalPro
        else -> R.id.radioReplyMagneticDock
    }

    private fun modeForRadioId(id: Int): Int = when (id) {
        R.id.radioReplyClassicLayout -> AppSettings.REPLY_ANIM_CLASSIC_LAYOUT
        R.id.radioReplyGpuSmooth -> AppSettings.REPLY_ANIM_GPU_SMOOTH
        R.id.radioReplyMagneticDock -> AppSettings.REPLY_ANIM_MAGNETIC_DOCK
        R.id.radioReplyLiquidFill -> AppSettings.REPLY_ANIM_LIQUID_FILL
        R.id.radioReplyElasticBubble -> AppSettings.REPLY_ANIM_ELASTIC_BUBBLE
        R.id.radioReplyMinimalPro -> AppSettings.REPLY_ANIM_MINIMAL_PRO
        else -> AppSettings.DEFAULT_REPLY_ANIMATION_MODE
    }
}
