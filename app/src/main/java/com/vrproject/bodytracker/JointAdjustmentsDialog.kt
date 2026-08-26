package com.vrproject.bodytracker

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.Locale
import kotlin.math.roundToInt

class JointAdjustmentsDialog(
    private val context: Context,
    private val currentConfigProvider: () -> AppConfig,
    private val onConfigUpdated: (AppConfig) -> Unit
) {

    fun show() {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_joint_adjustments, null)
        var savedConfig = currentConfigProvider()

        val globalZLabel = dialogView.findViewById<TextView>(R.id.globalZLabel)
        val globalZ = dialogView.findViewById<SeekBar>(R.id.globalZSeekBar)

        // 1. Head
        val headCheckBox = dialogView.findViewById<CheckBox>(R.id.headCheckBox)
        val headLabel = dialogView.findViewById<TextView>(R.id.headLabel)
        val headY = dialogView.findViewById<SeekBar>(R.id.headYSeekBar)

        // 2. Chest
        val chestCheckBox = dialogView.findViewById<CheckBox>(R.id.chestCheckBox)
        val chestLabel = dialogView.findViewById<TextView>(R.id.chestLabel)
        val chestY = dialogView.findViewById<SeekBar>(R.id.chestYSeekBar)

        // 3. Elbows
        val elbowsCheckBox = dialogView.findViewById<CheckBox>(R.id.elbowsCheckBox)
        val elbowsLabel = dialogView.findViewById<TextView>(R.id.elbowsLabel)
        val elbowsX = dialogView.findViewById<SeekBar>(R.id.elbowsXSeekBar)
        val elbowsY = dialogView.findViewById<SeekBar>(R.id.elbowsYSeekBar)

        // 4. Hip
        val hipCheckBox = dialogView.findViewById<CheckBox>(R.id.hipCheckBox)
        val hipLabel = dialogView.findViewById<TextView>(R.id.hipLabel)
        val hipY = dialogView.findViewById<SeekBar>(R.id.hipYSeekBar)

        // 5. Knees
        val kneesCheckBox = dialogView.findViewById<CheckBox>(R.id.kneesCheckBox)
        val kneesLabel = dialogView.findViewById<TextView>(R.id.kneesLabel)
        val kneesX = dialogView.findViewById<SeekBar>(R.id.kneesXSeekBar)
        val kneesY = dialogView.findViewById<SeekBar>(R.id.kneesYSeekBar)

        // 6. Feet
        val feetCheckBox = dialogView.findViewById<CheckBox>(R.id.feetCheckBox)
        val feetLabel = dialogView.findViewById<TextView>(R.id.feetLabel)
        val feetX = dialogView.findViewById<SeekBar>(R.id.feetXSeekBar)
        val feetY = dialogView.findViewById<SeekBar>(R.id.feetYSeekBar)

        val resetButton = dialogView.findViewById<Button>(R.id.resetOffsetsButton)
        val closeButton = dialogView.findViewById<Button>(R.id.closeDialogButton)

        val initialPaddingLeft = dialogView.paddingLeft
        val initialPaddingTop = dialogView.paddingTop
        val initialPaddingRight = dialogView.paddingRight
        val initialPaddingBottom = dialogView.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(dialogView) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            dialogView.setPadding(
                initialPaddingLeft + bars.left,
                initialPaddingTop + bars.top,
                initialPaddingRight + bars.right,
                initialPaddingBottom + bars.bottom
            )
            WindowInsetsCompat.CONSUMED
        }

        // Conversione con step esatto di 0.02 (Range: da -1.00m a +1.00m con max = 100)
        fun metersFromProgress(p: Int): Float = (p - 50) * 0.02f
        fun progressFromMeters(m: Float): Int = ((m / 0.02f) + 50f).roundToInt().coerceIn(0, 100)

        fun updateLabels() {
            globalZLabel.text = String.format(Locale.US, "Global Z Offset (Z: %.2fm)", savedConfig.globalZOffset)
            headLabel.text = String.format(Locale.US, "Head Offset (Y: %.2fm)", savedConfig.headOffset.y)
            chestLabel.text = String.format(Locale.US, "Chest Offset (Y: %.2fm)", savedConfig.chestOffset.y)
            elbowsLabel.text = String.format(Locale.US, "Elbows Offset (Sym X: %.2fm, Y: %.2fm)", savedConfig.elbowsOffset.x, savedConfig.elbowsOffset.y)
            hipLabel.text = String.format(Locale.US, "Hip Offset (Y: %.2fm)", savedConfig.hipOffset.y)
            kneesLabel.text = String.format(Locale.US, "Knees Offset (Sym X: %.2fm, Y: %.2fm)", savedConfig.kneesOffset.x, savedConfig.kneesOffset.y)
            feetLabel.text = String.format(Locale.US, "Feet Offset (Sym X: %.2fm, Y: %.2fm)", savedConfig.feetOffset.x, savedConfig.feetOffset.y)
        }

        fun applyCurrentDialogValues() {
            savedConfig = savedConfig.copy(
                globalZOffset = metersFromProgress(globalZ.progress),
                headOffset = JointOffset(0f, metersFromProgress(headY.progress)),
                chestOffset = JointOffset(0f, metersFromProgress(chestY.progress)),
                elbowsOffset = JointOffset(metersFromProgress(elbowsX.progress), metersFromProgress(elbowsY.progress)),
                hipOffset = JointOffset(0f, metersFromProgress(hipY.progress)),
                kneesOffset = JointOffset(metersFromProgress(kneesX.progress), metersFromProgress(kneesY.progress)),
                feetOffset = JointOffset(metersFromProgress(feetX.progress), metersFromProgress(feetY.progress)),
                enableHead = headCheckBox.isChecked,
                enableChest = chestCheckBox.isChecked,
                enableElbows = elbowsCheckBox.isChecked,
                enableHip = hipCheckBox.isChecked,
                enableKnees = kneesCheckBox.isChecked,
                enableFeet = feetCheckBox.isChecked
            )
            updateLabels()
            onConfigUpdated(savedConfig)
        }

        fun setControlsFromConfig() {
            globalZ.progress = progressFromMeters(savedConfig.globalZOffset)
            headY.progress = progressFromMeters(savedConfig.headOffset.y)
            chestY.progress = progressFromMeters(savedConfig.chestOffset.y)
            elbowsX.progress = progressFromMeters(savedConfig.elbowsOffset.x)
            elbowsY.progress = progressFromMeters(savedConfig.elbowsOffset.y)
            hipY.progress = progressFromMeters(savedConfig.hipOffset.y)
            kneesX.progress = progressFromMeters(savedConfig.kneesOffset.x)
            kneesY.progress = progressFromMeters(savedConfig.kneesOffset.y)
            feetX.progress = progressFromMeters(savedConfig.feetOffset.x)
            feetY.progress = progressFromMeters(savedConfig.feetOffset.y)

            headCheckBox.isChecked = savedConfig.enableHead
            chestCheckBox.isChecked = savedConfig.enableChest
            elbowsCheckBox.isChecked = savedConfig.enableElbows
            hipCheckBox.isChecked = savedConfig.enableHip
            kneesCheckBox.isChecked = savedConfig.enableKnees
            feetCheckBox.isChecked = savedConfig.enableFeet

            updateLabels()
        }

        setControlsFromConfig()

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) applyCurrentDialogValues()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }

        listOf(globalZ, headY, chestY, elbowsX, elbowsY, hipY, kneesX, kneesY, feetX, feetY).forEach {
            it.setOnSeekBarChangeListener(listener)
        }

        listOf(headCheckBox, chestCheckBox, elbowsCheckBox, hipCheckBox, kneesCheckBox, feetCheckBox).forEach {
            it.setOnCheckedChangeListener { _, _ -> applyCurrentDialogValues() }
        }

        val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(dialogView)

        resetButton.setOnClickListener {
            savedConfig = savedConfig.copy(
                globalZOffset = 0f,
                headOffset = JointOffset(),
                chestOffset = JointOffset(),
                elbowsOffset = JointOffset(),
                hipOffset = JointOffset(),
                kneesOffset = JointOffset(),
                feetOffset = JointOffset(),
                enableHead = true,
                enableChest = true,
                enableElbows = true,
                enableHip = true,
                enableKnees = true,
                enableFeet = true
            )
            setControlsFromConfig()
            onConfigUpdated(savedConfig)
        }

        closeButton.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.BLACK))
        }
    }
}