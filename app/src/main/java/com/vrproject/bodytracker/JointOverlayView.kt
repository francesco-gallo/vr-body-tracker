package com.vrproject.bodytracker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class JointOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val boneLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 0, 255, 120)
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val boneFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(40, 0, 255, 120)
        style = Paint.Style.FILL
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

    private val pathBuffer = Path()

    fun setFrameJoints(
        items: List<JointSample>,
        shouldMirrorX: Boolean,
        sourceWidth: Int,
        sourceHeight: Int
    ) {
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

        // Calculate 20 cm box width proportional to average torso scale in screen space
        val pixelScale = estimateScreenPixelsPerMeter(byName, viewH)
        val boxWidthMeters = 0.20f // Fixed 20 cm
        val boxRadiusPx = (boxWidthMeters * pixelScale * 0.5f).coerceIn(12f, 80f)

        // 1. Draw 3D Box Beams fully accounting for Z depth at both ends
        for ((a, b) in CONNECTIONS) {
            val ja = byName[a]
            val jb = byName[b]
            if (ja != null && jb != null && ja.visibility > 0.3f && jb.visibility > 0.3f) {
                val x1 = getMappedX(ja.x, viewW)
                val y1 = ja.y * viewH
                val z1 = ja.z

                val x2 = getMappedX(jb.x, viewW)
                val y2 = jb.y * viewH
                val z2 = jb.z

                drawTorqued3DBoxBeam(
                    canvas = canvas,
                    x1 = x1, y1 = y1, z1 = z1,
                    x2 = x2, y2 = y2, z2 = z2,
                    baseRadius = boxRadiusPx,
                    sourceJoint = ja,
                    targetJoint = jb
                )
            }
        }

        // 2. Draw Joint Markers & Coordinates
        val axisLength = 30f
        val drawableJoints = byName.values.filter {
            it.name in TARGET_DISPLAY_JOINTS && it.visibility > 0.3f
        }

        for (joint in drawableJoints) {
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

    private fun drawTorqued3DBoxBeam(
        canvas: Canvas,
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        baseRadius: Float,
        sourceJoint: JointSample,
        targetJoint: JointSample
    ) {
        val dx = x2 - x1
        val dy = y2 - y1
        // Scale normalized Z-depth into screen-space pixel displacement
        val dz = (z2 - z1)

        val len = sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 1f) return

        // 3D Direction vector along the bone segment
        val dirX = dx / len
        val dirY = dy / len
        val dirZ = dz / len

        // Z-based perspective scale factors at source and target
        val scale1 = (1f + (z1 / 500f)).coerceIn(0.5f, 2.0f)
        val scale2 = (1f + (z2 / 500f)).coerceIn(0.5f, 2.0f)

        val radius1 = baseRadius * scale1
        val radius2 = baseRadius * scale2

        // Compute Torque / Roll Rotation angle from source to target
        val torqueAngle = calculateJointTorqueAngle(sourceJoint, targetJoint)

        // Construct 3D coordinate system perpendicular to bone direction
        var refX = -dirY
        var refY = dirX
        var refZ = 0f
        val refLen = sqrt(refX * refX + refY * refY)

        if (refLen < 0.001f) {
            refX = 1f; refY = 0f; refZ = 0f
        } else {
            refX /= refLen; refY /= refLen
        }

        val cosT = cos(torqueAngle)
        val sinT = sin(torqueAngle)

        // Up vector perpendicular to bone vector and refX
        val upX = dirY * refZ - dirZ * refY
        val upY = dirZ * refX - dirX * refZ
        val upZ = dirX * refY - dirY * refX

        val perpX1 = (refX * cosT + upX * sinT)
        val perpY1 = (refY * cosT + upY * sinT)
        val perpZ1 = (refZ * cosT + upZ * sinT)

        val perpX2 = (-refX * sinT + upX * cosT)
        val perpY2 = (-refY * sinT + upY * cosT)
        val perpZ2 = (-refZ * sinT + upZ * cosT)

        // 4 corners in 3D space for Source Joint
        val f1 = floatArrayOf(x1 + perpX1 * radius1, y1 + perpY1 * radius1, z1 + perpZ1 * radius1)
        val f2 = floatArrayOf(x1 + perpX2 * radius1, y1 + perpY2 * radius1, z1 + perpZ2 * radius1)
        val f3 = floatArrayOf(x1 - perpX1 * radius1, y1 - perpY1 * radius1, z1 - perpZ1 * radius1)
        val f4 = floatArrayOf(x1 - perpX2 * radius1, y1 - perpY2 * radius1, z1 - perpZ2 * radius1)

        // 4 corners in 3D space for Target Joint
        val b1 = floatArrayOf(x2 + perpX1 * radius2, y2 + perpY1 * radius2, z2 + perpZ1 * radius2)
        val b2 = floatArrayOf(x2 + perpX2 * radius2, y2 + perpY2 * radius2, z2 + perpZ2 * radius2)
        val b3 = floatArrayOf(x2 - perpX1 * radius2, y2 - perpY1 * radius2, z2 - perpZ1 * radius2)
        val b4 = floatArrayOf(x2 - perpX2 * radius2, y2 - perpY2 * radius2, z2 - perpZ2 * radius2)

        // Project 3D coordinates (X, Y, Z) onto 2D viewport
        fun projX(p: FloatArray): Float = p[0] - (p[2] / 150f).coerceIn(-1.5f, 1.5f) * 8f
        fun projY(p: FloatArray): Float = p[1] - (p[2] / 150f).coerceIn(-1.5f, 1.5f) * 8f

        // Draw Source Joint 3D Polygon Face
        pathBuffer.reset()
        pathBuffer.moveTo(projX(f1), projY(f1))
        pathBuffer.lineTo(projX(f2), projY(f2))
        pathBuffer.lineTo(projX(f3), projY(f3))
        pathBuffer.lineTo(projX(f4), projY(f4))
        pathBuffer.close()
        canvas.drawPath(pathBuffer, boneFillPaint)
        canvas.drawPath(pathBuffer, boneLinePaint)

        // Draw Target Joint 3D Polygon Face
        pathBuffer.reset()
        pathBuffer.moveTo(projX(b1), projY(b1))
        pathBuffer.lineTo(projX(b2), projY(b2))
        pathBuffer.lineTo(projX(b3), projY(b3))
        pathBuffer.lineTo(projX(b4), projY(b4))
        pathBuffer.close()
        canvas.drawPath(pathBuffer, boneLinePaint)

        // Connecting Long Edges between Source and Target
        canvas.drawLine(projX(f1), projY(f1), projX(b1), projY(b1), boneLinePaint)
        canvas.drawLine(projX(f2), projY(f2), projX(b2), projY(b2), boneLinePaint)
        canvas.drawLine(projX(f3), projY(f3), projX(b3), projY(b3), boneLinePaint)
        canvas.drawLine(projX(f4), projY(f4), projX(b4), projY(b4), boneLinePaint)
    }

    private fun calculateJointTorqueAngle(source: JointSample, target: JointSample): Float {
        val dx = target.x - source.x
        val dy = -(target.y - source.y)
        val dz = (target.z - source.z) / 1000f

        val yaw = atan2(dx, dz)
        val pitch = atan2(dy, sqrt(dx * dx + dz * dz))

        return (yaw * 0.5f + pitch * 0.5f)
    }

    private fun estimateScreenPixelsPerMeter(joints: Map<String, JointSample>, viewHeight: Float): Float {
        val ls = joints["left_shoulder"]
        val rs = joints["right_shoulder"]
        val lh = joints["left_hip"]
        val rh = joints["right_hip"]

        if (ls != null && rs != null && lh != null && rh != null) {
            val shoulderY = (ls.y + rs.y) * 0.5f
            val hipY = (lh.y + rh.y) * 0.5f
            val torsoNormH = abs(hipY - shoulderY)
            if (torsoNormH > 0.05f) {
                val torsoPixels = torsoNormH * viewHeight
                val estimatedTorsoMeters = 0.50f
                return torsoPixels / estimatedTorsoMeters
            }
        }
        return viewHeight * 0.6f
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
        private val TARGET_DISPLAY_JOINTS = setOf(
            "head", "chest_mid", "hip_mid",
            "left_shoulder", "right_shoulder",
            "left_elbow", "right_elbow",
            "left_knee", "right_knee",
            "left_ankle", "right_ankle"
        )

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