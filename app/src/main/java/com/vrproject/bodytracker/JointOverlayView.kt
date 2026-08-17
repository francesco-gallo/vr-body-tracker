package com.vrproject.bodytracker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class JointOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 22f
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 0, 255, 0)
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }

    private val axisXPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        strokeWidth = 3f
    }
    private val axisYPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        strokeWidth = 3f
    }
    private val axisZPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN
        strokeWidth = 3f
    }

    private var joints: List<JointSample> = emptyList()
    private var mirrorX: Boolean = false
    private var sourceWidth: Int = 0
    private var sourceHeight: Int = 0
    var currentFrameJoints: List<JointSample> = emptyList()
        private set
    var currentSourceWidth: Int = 0
        private set
    var currentSourceHeight: Int = 0
        private set

    fun setFrameJoints(
        items: List<JointSample>,
        shouldMirrorX: Boolean,
        sourceWidth: Int,
        sourceHeight: Int
    ) {
        joints = items
        currentFrameJoints = items
        mirrorX = shouldMirrorX
        this.sourceWidth = sourceWidth
        this.sourceHeight = sourceHeight
        currentSourceWidth = sourceWidth
        currentSourceHeight = sourceHeight
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (joints.isEmpty()) return

        drawSkeleton(canvas)

        val axisLength = 30f
        for (i in joints.indices) {
            val joint = joints[i]
            if (joint.visibility < 0.25f) continue

            val px = mapX(joint.x)
            val py = mapY(joint.y)

            pointPaint.color = getZColor(joint.z)
            canvas.drawCircle(px, py, 10f, pointPaint)

            val xDir = if (mirrorX) -axisLength else axisLength
            canvas.drawLine(px, py, px + xDir, py, axisXPaint)
            canvas.drawLine(px, py, px, py + axisLength, axisYPaint)

            val zOffset = (joint.z / 100f).coerceIn(-1f, 1f) * axisLength
            canvas.drawLine(px, py, px - zOffset, py - zOffset, axisZPaint)

            canvas.drawText(joint.name, px + 12f, py - 12f, textPaint)
        }
    }

    private fun getZColor(z: Float): Int {
        val normalizedZ = ((z + 100f) / 200f).coerceIn(0f, 1f)
        val red = (255 * (1f - normalizedZ)).toInt()
        val green = (200 * (1f - absVal(normalizedZ - 0.5f) * 2)).toInt()
        val blue = (255 * normalizedZ).toInt()
        return Color.rgb(red, green, blue)
    }

    private fun absVal(v: Float) = if (v < 0) -v else v

    private fun drawSkeleton(canvas: Canvas) {
        val byName = joints.associateBy { it.name }.toMutableMap()

        val ls = byName["left_shoulder"]
        val rs = byName["right_shoulder"]
        if (ls != null && rs != null) {
            byName["neck"] = JointSample(
                name = "neck",
                x = (ls.x + rs.x) * 0.5f,
                y = (ls.y + rs.y) * 0.5f,
                z = (ls.z + rs.z) * 0.5f,
                visibility = (ls.visibility + rs.visibility) * 0.5f
            )
        }

        for (pairIndex in BONES.indices) {
            val bone = BONES[pairIndex]
            val ja = byName[bone.first]
            val jb = byName[bone.second]
            if (ja == null || jb == null || ja.visibility < 0.25f || jb.visibility < 0.25f) {
                continue
            }
            canvas.drawLine(mapX(ja.x), mapY(ja.y), mapX(jb.x), mapY(jb.y), linePaint)
        }
    }

    private fun findJoint(name: String): JointSample? {
        for (i in joints.indices) {
            if (joints[i].name == name) return joints[i]
        }
        return null
    }

    private fun mapX(normX: Float): Float {
        val x = normX.coerceIn(0f, 1f)
        val mapped = if (mirrorX) 1f - x else x
        val render = renderBounds()
        return render.offsetX + mapped * render.width
    }

    private fun mapY(normY: Float): Float {
        val y = normY.coerceIn(0f, 1f)
        val render = renderBounds()
        return render.offsetY + y * render.height
    }

    private data class RenderBounds(
        val offsetX: Float,
        val offsetY: Float,
        val width: Float,
        val height: Float
    )

    private fun renderBounds(): RenderBounds {
        if (sourceWidth <= 0 || sourceHeight <= 0 || width <= 0 || height <= 0) {
            return RenderBounds(0f, 0f, width.toFloat(), height.toFloat())
        }

        val scale = max(width.toFloat() / sourceWidth.toFloat(), height.toFloat() / sourceHeight.toFloat())
        val drawWidth = sourceWidth * scale
        val drawHeight = sourceHeight * scale
        val offsetX = (width - drawWidth) / 2f
        val offsetY = (height - drawHeight) / 2f
        return RenderBounds(offsetX, offsetY, drawWidth, drawHeight)
    }

    companion object {
        private val BONES = listOf(
            "head" to "neck",
            "neck" to "left_shoulder",
            "neck" to "right_shoulder",
            "left_shoulder" to "left_elbow",
            "left_elbow" to "left_wrist",
            "right_shoulder" to "right_elbow",
            "right_elbow" to "right_wrist",
            "left_shoulder" to "left_hip",
            "right_shoulder" to "right_hip",
            "left_hip" to "right_hip",
            "left_hip" to "left_knee",
            "left_knee" to "left_ankle",
            "right_hip" to "right_knee",
            "right_knee" to "right_ankle"
        )
    }
}