package com.hyperisland.pro.ui

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
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
        bindShadePolicyLab()
        bindMorphLab()
        bindPillIconLab()
        bindShadePullLab()

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

    private fun bindShadePolicyLab() {
        // The count vanishing during a notification rain is a policy question, so it is his to set and not mine
        // to keep guessing at, and it is one radio away from the trace log that will now show what happened.
        val group = findViewById<RadioGroup>(R.id.radioShadePolicy)
        group.check(
            if (AppSettings.getShadeOpenPolicy(this) == AppSettings.SHADE_POLICY_WIPES)
                R.id.radioShadeWipes else R.id.radioShadeKeepCounting
        )
        group.setOnCheckedChangeListener { _, checkedId ->
            AppSettings.setShadeOpenPolicy(
                this,
                if (checkedId == R.id.radioShadeWipes) AppSettings.SHADE_POLICY_WIPES
                else AppSettings.SHADE_POLICY_COUNT_QUIETLY
            )
            Toast.makeText(this, "Shade: " + AppSettings.getShadeOpenPolicyName(this), Toast.LENGTH_SHORT).show()
        }
    }

    private fun bindMorphLab() {
        // He asked for options to choose from rather than another single opinion from me, and for them
        // to live where he can feel them without waiting on a build.
        val group = findViewById<RadioGroup>(R.id.radioMorphStyle)
        group.check(idForMorphStyle(AppSettings.getMorphStyle(this)))
        group.setOnCheckedChangeListener { _, checkedId ->
            AppSettings.setMorphStyle(this, morphStyleForId(checkedId))
            Toast.makeText(this, "Morph: " + AppSettings.getMorphStyleName(this), Toast.LENGTH_SHORT).show()
        }
        bindMorphSeek(
            R.id.seekMorphScale, R.id.tvMorphScaleLabel, "content starts", 0, 45,
            { AppSettings.getMorphContentScalePct(it) }, { c, v -> AppSettings.setMorphContentScalePct(c, v) }
        )
        bindMorphSeek(
            R.id.seekMorphSwap, R.id.tvMorphSwapLabel, "icon becomes the app badge at", 10, 90,
            { AppSettings.getMorphGlyphSwapPct(it) }, { c, v -> AppSettings.setMorphGlyphSwapPct(c, v) }
        )
        // The ride is separate from the style because he asked to feel "scale + fade" WITH the travelling icon,
        // and because a bundled switch is a switch he cannot actually test.
        findViewById<CheckBox>(R.id.chkMorphRide).apply {
            isChecked = AppSettings.getMorphIconRide(this@TestLabActivity)
            setOnCheckedChangeListener { _, checked ->
                AppSettings.setMorphIconRide(this@TestLabActivity, checked)
                Toast.makeText(
                    this@TestLabActivity,
                    if (checked) "Icon ride on" else "Icon ride off",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        // Which axis the content enters on. The box grows evenly left-right and downward from the pill, so the
        // two choices are "follow that axis" and "the old way, where the row was re-centred in the box every
        // frame" - and his own description of the old way is why this is a choice instead of my opinion:
        // "upper right side se niche center ki aur aa rha hai", "3rd option upper left side".
        val entryGroup = findViewById<RadioGroup>(R.id.radioMorphEntry)
        entryGroup.check(
            if (AppSettings.getMorphEntry(this) == AppSettings.MORPH_ENTRY_CENTRED) R.id.radioMorphEntryCentred
            else R.id.radioMorphEntryDrop
        )
        entryGroup.setOnCheckedChangeListener { _, checkedId ->
            AppSettings.setMorphEntry(
                this,
                if (checkedId == R.id.radioMorphEntryCentred) AppSettings.MORPH_ENTRY_CENTRED else AppSettings.MORPH_ENTRY_DROP
            )
            Toast.makeText(this, "Content entry: " + AppSettings.getMorphEntryName(this), Toast.LENGTH_SHORT).show()
        }
        findViewById<CheckBox>(R.id.chkMorphRoll).apply {
            isChecked = AppSettings.getMorphCountRoll(this@TestLabActivity)
            setOnCheckedChangeListener { _, checked ->
                AppSettings.setMorphCountRoll(this@TestLabActivity, checked)
                Toast.makeText(
                    this@TestLabActivity,
                    if (checked) "Count roll on - the digits slide the way the number moved" else "Count roll off",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        bindMorphSeek(
            R.id.seekMorphDrop, R.id.tvMorphDropLabel, "extra drop from the pill", 0, 40,
            { AppSettings.getMorphContentDropDp(it) }, { c, v -> AppSettings.setMorphContentDropDp(c, v) },
            unit = "dp"
        )
        bindMorphSeek(
            R.id.seekMorphStagger, R.id.tvMorphStaggerLabel, "entry staggered by", 0, 60,
            { AppSettings.getMorphStaggerPct(it) }, { c, v -> AppSettings.setMorphStaggerPct(c, v) }
        )
        // The collapse's deadline. Above this share of the shape's travel nothing of the row may still be
        // drawn over the pill; the old 40/45 constants that only I could tune are this slider now.
        bindMorphSeek(
            R.id.seekMorphGone, R.id.tvMorphGoneLabel, "content gone by", 25, 90,
            { AppSettings.getMorphGoneByPct(it) }, { c, v -> AppSettings.setMorphGoneByPct(c, v) }
        )
        findViewById<Button>(R.id.btnReplayMorph).setOnClickListener {
            // Both directions on one press: "opens nicely, closes wrong" is a real answer he could not
            // otherwise give me without timing two taps against a 320 ms morph.
            HyperAccessibilityService.toggleExpandFromApp(this)
            findViewById<Button>(R.id.btnReplayMorph).postDelayed({
                if (!isFinishing) HyperAccessibilityService.toggleExpandFromApp(this)
            }, 950L)
        }
    }

    private fun bindMorphSeek(
        seekId: Int,
        labelId: Int,
        label: String,
        min: Int,
        max: Int,
        get: (android.content.Context) -> Int,
        set: (android.content.Context, Int) -> Unit,
        unit: String = "%",
    ) {
        val seekBar = findViewById<SeekBar>(seekId)
        val labelView = findViewById<TextView>(labelId)
        fun show(v: Int) { labelView.text = "$label: $v$unit" }
        seekBar.max = max - min
        val initial = get(this).coerceIn(min, max)
        seekBar.progress = initial - min
        show(initial)
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = min + progress
                show(value)
                // Writing on every tick is what the other labs do, and the morph reads the setting at its
                // own start, so a drag costs nothing and applies to the very next replay.
                if (fromUser) set(this@TestLabActivity, value)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })
    }

    private fun idForMorphStyle(style: Int): Int = when (style) {
        AppSettings.MORPH_STYLE_CARRY -> R.id.radioMorphCarry
        AppSettings.MORPH_STYLE_SHAPE_ONLY -> R.id.radioMorphScaleOnly
        else -> R.id.radioMorphBalanced
    }

    private fun morphStyleForId(id: Int): Int = when (id) {
        R.id.radioMorphCarry -> AppSettings.MORPH_STYLE_CARRY
        R.id.radioMorphScaleOnly -> AppSettings.MORPH_STYLE_SHAPE_ONLY
        else -> AppSettings.MORPH_STYLE_BALANCED
    }

    private fun bindPillIconLab() {
        // Temporary lab for comparing automatic compact pill icon render paths.
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
        AppSettings.PILL_ICON_MANUAL_RESOURCE_NO_VALIDATION -> R.id.radioPillIconSmallOnly
        AppSettings.PILL_ICON_LOAD_DRAWABLE_NO_VALIDATION -> R.id.radioPillIconAdaptive
        AppSettings.PILL_ICON_LEGACY_NO_VALIDATION -> R.id.radioPillIconLauncher
        AppSettings.PILL_ICON_GENERIC -> R.id.radioPillIconGeneric
        else -> R.id.radioPillIconAuto
    }

    private fun pillIconModeForId(id: Int): Int = when (id) {
        R.id.radioPillIconSmallOnly -> AppSettings.PILL_ICON_MANUAL_RESOURCE_NO_VALIDATION
        R.id.radioPillIconAdaptive -> AppSettings.PILL_ICON_LOAD_DRAWABLE_NO_VALIDATION
        R.id.radioPillIconLauncher -> AppSettings.PILL_ICON_LEGACY_NO_VALIDATION
        R.id.radioPillIconGeneric -> AppSettings.PILL_ICON_GENERIC
        else -> AppSettings.PILL_ICON_AUTO
    }

    private fun bindShadePullLab() {
        // Compare shade-open pill clear animations on device.
        val group = findViewById<RadioGroup>(R.id.radioShadePullMode)
        group.check(idForShadePullMode(AppSettings.getShadePullAnimationMode(this)))
        group.setOnCheckedChangeListener { _, checkedId ->
            val mode = shadePullModeForId(checkedId)
            AppSettings.setShadePullAnimationMode(this, mode)
            Toast.makeText(this, "Shade pull: ${AppSettings.getShadePullAnimationModeName(mode)}", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.btnPreviewShadePull).setOnClickListener {
            if (!AppSettings.isIslandEnabled(this)) {
                Toast.makeText(this, "Turn island ON first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val ok = HyperAccessibilityService.previewShadePullFromApp(this)
            if (!ok) Toast.makeText(this, "Accessibility service not connected", Toast.LENGTH_SHORT).show()
        }
    }

    private fun idForShadePullMode(mode: Int): Int = when (mode) {
        AppSettings.SHADE_PULL_SIMPLE -> R.id.radioShadePullSimple
        AppSettings.SHADE_PULL_MY_ABSORB -> R.id.radioShadePullMyAbsorb
        AppSettings.SHADE_PULL_KIMI_VACUUM -> R.id.radioShadePullKimi
        AppSettings.SHADE_PULL_DEEPSEEK_MAGNETIC -> R.id.radioShadePullDeepSeek
        AppSettings.SHADE_PULL_GPT_HIGH -> R.id.radioShadePullGptHigh
        else -> R.id.radioShadePullGptHigh
    }

    private fun shadePullModeForId(id: Int): Int = when (id) {
        R.id.radioShadePullSimple -> AppSettings.SHADE_PULL_SIMPLE
        R.id.radioShadePullMyAbsorb -> AppSettings.SHADE_PULL_MY_ABSORB
        R.id.radioShadePullKimi -> AppSettings.SHADE_PULL_KIMI_VACUUM
        R.id.radioShadePullDeepSeek -> AppSettings.SHADE_PULL_DEEPSEEK_MAGNETIC
        R.id.radioShadePullGptHigh -> AppSettings.SHADE_PULL_GPT_HIGH
        else -> AppSettings.DEFAULT_SHADE_PULL_ANIMATION_MODE
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
