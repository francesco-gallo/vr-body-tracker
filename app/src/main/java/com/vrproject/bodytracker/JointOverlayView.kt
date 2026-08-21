package com.vrproject.bodytracker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class JointOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 0, 255, 120)
        strokeWidth = 6f
        style = Paint.Style.STROKE
    }

    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val axisXPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED; strokeWidth = 4f }
    private val axisYPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GREEN; strokeWidth = 4f }
    private val axisZPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.CYAN; strokeWidth = 4f }

    var currentFrameJoints: List<JointSample> = emptyList()
        private set
    var currentSourceWidth: Int = 1
        private set
    var currentSourceHeight: Int = 1
        private set
    private var mirrorX: Boolean = false

    fun setFrameJoints(
        items: List<JointSample>,
        shouldMirrorX: Boolean,
        sourceWidth: Int,
        sourceHeight: Int
    ) {
        val mirrorChanged = mirrorX != shouldMirrorX
        currentFrameJoints = items
        mirrorX = shouldMirrorX
        currentSourceWidth = if (sourceWidth > 0) sourceWidth else 1
        currentSourceHeight = if (sourceHeight > 0) sourceHeight else 1

        invalidate()
    }

    fun setMirrorX(shouldMirrorX: Boolean) {
        if (mirrorX != shouldMirrorX) {
            mirrorX = shouldMirrorX
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (currentFrameJoints.isEmpty()) return

        val viewW = width.toFloat()
        val viewH = height.toFloat()

        val byName = currentFrameJoints.associateBy { it.name }.toMutableMap()

        val ls = byName["left_shoulder"]
        val rs = byName["right_shoulder"]
        if (ls != null && rs != null) {
            byName["chest_mid"] = JointSample(
                name = "chest_mid",
                x = (ls.x + rs.x) * 0.5f,
                y = (ls.y + rs.y) * 0.5f,
                z = (ls.z + rs.z) * 0.5f,
                visibility = (ls.visibility + rs.visibility) * 0.5f
            )
        }

        val lh = byName["left_hip"]
        val rh = byName["right_hip"]
        if (lh != null && rh != null) {
            byName["hip_mid"] = JointSample(
                name = "hip_mid",
                x = (lh.x + rh.x) * 0.5f,
                y = (lh.y + rh.y) * 0.5f,
                z = (lh.z + rh.z) * 0.5f,
                visibility = (lh.visibility + rh.visibility) * 0.5f
            )
        }

        for ((a, b) in CONNECTIONS) {
            val ja = byName[a]
            val jb = byName[b]
            if (ja != null && jb != null && ja.visibility > 0.3f && jb.visibility > 0.3f) {
                val x1 = getMappedX(ja.x, viewW)
                val y1 = ja.y * viewH
                val x2 = getMappedX(jb.x, viewW)
                val y2 = jb.y * viewH
                canvas.drawLine(x1, y1, x2, y2, linePaint)
            }
        }

        val axisLength = 30f
        for (joint in currentFrameJoints) {
            if (joint.visibility > 0.3f) {
                val px = getMappedX(joint.x, viewW)
                val py = joint.y * viewH

                pointPaint.color = getZColor(joint.z)
                canvas.drawCircle(px, py, 12f, pointPaint)

                canvas.drawLine(px, py, px + axisLength, py, axisXPaint)
                canvas.drawLine(px, py, px, py + axisLength, axisYPaint)
                val zOffset = (joint.z / 150f).coerceIn(-1f, 1f) * axisLength
                canvas.drawLine(px, py, px - zOffset, py - zOffset, axisZPaint)
            }
        }
    }

    private fun getMappedX(normX: Float, viewWidth: Float): Float {
        val x = if (mirrorX) 1.0f - normX else normX
        return x * viewWidth
    }

    private fun getZColor(z: Float): Int {
        val normalizedZ = ((z + 100f) / 200f).coerceIn(0f, 1f)
        val red = (255 * (1f - normalizedZ)).toInt()
        val green = (200 * (1f - kotlin.math.abs(normalizedZ - 0.5f) * 2)).toInt()
        val blue = (255 * normalizedZ).toInt()
        return Color.rgb(red, green, blue)
    }

    companion object {
        private val CONNECTIONS = listOf(
            "left_shoulder" to "left_elbow",
            "right_shoulder" to "right_elbow",
            "left_shoulder" to "right_shoulder",
            "chest_mid" to "hip_mid",
            "hip_mid" to "left_knee",
            "left_knee" to "left_ankle",
            "hip_mid" to "right_knee",
            "right_knee" to "right_ankle"
        )
    }
}