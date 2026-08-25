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

        val headCheckBox = dialogView.findViewById<CheckBox>(R.id.headCheckBox)
        val headLabel = dialogView.findViewById<TextView>(R.id.headLabel)
        val headY = dialogView.findViewById<SeekBar>(R.id.headYSeekBar)

        val hipCheckBox = dialogView.findViewById<CheckBox>(R.id.hipCheckBox)
        val hipLabel = dialogView.findViewById<TextView>(R.id.hipLabel)
        val hipY = dialogView.findViewById<SeekBar>(R.id.hipYSeekBar)

        val chestCheckBox = dialogView.findViewById<CheckBox>(R.id.chestCheckBox)
        val chestLabel = dialogView.findViewById<TextView>(R.id.chestLabel)
        val chestY = dialogView.findViewById<SeekBar>(R.id.chestYSeekBar)

        val feetCheckBox = dialogView.findViewById<CheckBox>(R.id.feetCheckBox)
        val feetLabel = dialogView.findViewById<TextView>(R.id.feetLabel)
        val feetX = dialogView.findViewById<SeekBar>(R.id.feetXSeekBar)
        val feetY = dialogView.findViewById<SeekBar>(R.id.feetYSeekBar)

        val kneesCheckBox = dialogView.findViewById<CheckBox>(R.id.kneesCheckBox)
        val kneesLabel = dialogView.findViewById<TextView>(R.id.kneesLabel)
        val kneesX = dialogView.findViewById<SeekBar>(R.id.kneesXSeekBar)
        val kneesY = dialogView.findViewById<SeekBar>(R.id.kneesYSeekBar)

        val elbowsCheckBox = dialogView.findViewById<CheckBox>(R.id.elbowsCheckBox)
        val elbowsLabel = dialogView.findViewById<TextView>(R.id.elbowsLabel)
        val elbowsX = dialogView.findViewById<SeekBar>(R.id.elbowsXSeekBar)
        val elbowsY = dialogView.findViewById<SeekBar>(R.id.elbowsYSeekBar)

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

        fun metersFromProgress(p: Int): Float = (p - 100) / 100f
        fun progressFromMeters(m: Float): Int = ((m * 100f) + 100f).toInt().coerceIn(0, 200)

        fun updateLabels() {
            globalZLabel.text = String.format(Locale.US, "Global Z Offset (Z: %.2fm)", savedConfig.globalZOffset)
            headLabel.text = String.format(Locale.US, "Head Offset (Y: %.2fm)", savedConfig.headOffset.y)
            hipLabel.text = String.format(Locale.US, "Hip Offset (Y: %.2fm)", savedConfig.hipOffset.y)
            chestLabel.text = String.format(Locale.US, "Chest Offset (Y: %.2fm)", savedConfig.chestOffset.y)
            feetLabel.text = String.format(Locale.US, "Feet Offset (Sym X: %.2fm, Y: %.2fm)", savedConfig.feetOffset.x, savedConfig.feetOffset.y)
            kneesLabel.text = String.format(Locale.US, "Knees Offset (Sym X: %.2fm, Y: %.2fm)", savedConfig.kneesOffset.x, savedConfig.kneesOffset.y)
            elbowsLabel.text = String.format(Locale.US, "Elbows Offset (Sym X: %.2fm, Y: %.2fm)", savedConfig.elbowsOffset.x, savedConfig.elbowsOffset.y)
        }

        fun applyCurrentDialogValues() {
            savedConfig = savedConfig.copy(
                globalZOffset = metersFromProgress(globalZ.progress),
                headOffset = JointOffset(0f, metersFromProgress(headY.progress)),
                hipOffset = JointOffset(0f, metersFromProgress(hipY.progress)),
                chestOffset = JointOffset(0f, metersFromProgress(chestY.progress)),
                feetOffset = JointOffset(metersFromProgress(feetX.progress), metersFromProgress(feetY.progress)),
                kneesOffset = JointOffset(metersFromProgress(kneesX.progress), metersFromProgress(kneesY.progress)),
                elbowsOffset = JointOffset(metersFromProgress(elbowsX.progress), metersFromProgress(elbowsY.progress)),
                enableHead = headCheckBox.isChecked,
                enableHip = hipCheckBox.isChecked,
                enableChest = chestCheckBox.isChecked,
                enableFeet = feetCheckBox.isChecked,
                enableKnees = kneesCheckBox.isChecked,
                enableElbows = elbowsCheckBox.isChecked
            )
            updateLabels()
            onConfigUpdated(savedConfig)
        }

        fun setControlsFromConfig() {
            globalZ.progress = progressFromMeters(savedConfig.globalZOffset)
            headY.progress = progressFromMeters(savedConfig.headOffset.y)
            hipY.progress = progressFromMeters(savedConfig.hipOffset.y)
            chestY.progress = progressFromMeters(savedConfig.chestOffset.y)
            feetX.progress = progressFromMeters(savedConfig.feetOffset.x)
            feetY.progress = progressFromMeters(savedConfig.feetOffset.y)
            kneesX.progress = progressFromMeters(savedConfig.kneesOffset.x)
            kneesY.progress = progressFromMeters(savedConfig.kneesOffset.y)
            elbowsX.progress = progressFromMeters(savedConfig.elbowsOffset.x)
            elbowsY.progress = progressFromMeters(savedConfig.elbowsOffset.y)

            headCheckBox.isChecked = savedConfig.enableHead
            hipCheckBox.isChecked = savedConfig.enableHip
            chestCheckBox.isChecked = savedConfig.enableChest
            feetCheckBox.isChecked = savedConfig.enableFeet
            kneesCheckBox.isChecked = savedConfig.enableKnees
            elbowsCheckBox.isChecked = savedConfig.enableElbows

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

        listOf(globalZ, headY, hipY, chestY, feetX, feetY, kneesX, kneesY, elbowsX, elbowsY).forEach {
            it.setOnSeekBarChangeListener(listener)
        }

        listOf(headCheckBox, hipCheckBox, chestCheckBox, feetCheckBox, kneesCheckBox, elbowsCheckBox).forEach {
            it.setOnCheckedChangeListener { _, _ -> applyCurrentDialogValues() }
        }

        val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(dialogView)

        resetButton.setOnClickListener {
            savedConfig = savedConfig.copy(
                globalZOffset = 0f,
                headOffset = JointOffset(),
                hipOffset = JointOffset(),
                chestOffset = JointOffset(),
                feetOffset = JointOffset(),
                kneesOffset = JointOffset(),
                elbowsOffset = JointOffset(),
                enableHead = true,
                enableHip = true,
                enableChest = true,
                enableFeet = true,
                enableKnees = true,
                enableElbows = true
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