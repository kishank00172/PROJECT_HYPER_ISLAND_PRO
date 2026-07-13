package com.hyperisland.pro.ui

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.RadioGroup
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
            AppSettings.REPLY_ANIM_MAGNETIC_DOCK -> "Current candidate. Reply tile expands and docks to safe left lane."
            AppSettings.REPLY_ANIM_LIQUID_FILL -> "Slower premium reveal with cyan edge. Good for cinematic feel."
            AppSettings.REPLY_ANIM_ELASTIC_BUBBLE -> "Magnetic dock with tiny soft finish bounce."
            AppSettings.REPLY_ANIM_MINIMAL_PRO -> "Fast clean transition. Less flashy, more utility-focused."
            else -> "Current candidate. Reply tile expands and docks to safe left lane."
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
