package com.vrproject.bodytracker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class JointSample(
    val name: String,
    val x: Float,
    val y: Float,
    val z: Float,
    val visibility: Float
)

data class PoseFrame(
    val timestampMs: Long,
    val imageWidth: Int,
    val imageHeight: Int,
    val joints: List<JointSample>
)

/**
 * Camera frame analyzer that delegates actual pose inference to a [PoseEngine]
 * (MediaPipe or ML Kit), swapping engines on the fly when [modelTypeProvider] changes.
 */
class PoseTracker(
    private val context: Context,
    private val modelTypeProvider: () -> TrackerModelType = { TrackerModelType.MEDIAPIPE_LITE },
    private val targetFpsProvider: () -> Int = { 60 },
    private val onCheckShouldCaptureBitmap: (() -> Boolean)? = null,
    private val onFrame: PoseFrameCallback
) : ImageAnalysis.Analyzer {

    private val isProcessing = AtomicBoolean(false)
    @Volatile private var lastAnalyzedTimeMs = 0L

    private var currentModelType: TrackerModelType? = null
    private var currentEngine: PoseEngine? = null

    private fun ensureEngine(desired: TrackerModelType) {
        if (currentModelType == desired && currentEngine != null) return

        currentEngine?.close()
        currentEngine = when (desired) {
            TrackerModelType.MEDIAPIPE_LITE, TrackerModelType.MEDIAPIPE_FULL, TrackerModelType.MEDIAPIPE_HEAVY ->
                MediaPipeEngine(context, MediaPipeEngine.modelFileFor(desired), onFrame)
            TrackerModelType.MLKIT ->
                MlKitEngine(onFrame)
        }
        currentModelType = desired
    }

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()
        val targetFps = targetFpsProvider().coerceIn(10, 60)
        val minIntervalMs = 1000L / targetFps

        if (now - lastAnalyzedTimeMs < minIntervalMs) {
            imageProxy.close()
            return
        }

        if (!isProcessing.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }

        lastAnalyzedTimeMs = now
        val desiredModel = modelTypeProvider()

        try {
            ensureEngine(desiredModel)
        } catch (_: Exception) {
            isProcessing.set(false)
            imageProxy.close()
            return
        }

        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val shouldCaptureBitmap = onCheckShouldCaptureBitmap?.invoke() ?: false

        currentEngine?.analyze(imageProxy, rotationDegrees, shouldCaptureBitmap, now) {
            isProcessing.set(false)
        }
    }

    fun close() {
        currentEngine?.close()
        currentEngine = null
        currentModelType = null
    }

    companion object {
        fun renderProcessedWebFrame(
            srcBitmap: Bitmap,
            processedFrame: PoseFrame,
            rotationDegrees: Int,
            mirrorX: Boolean = false
        ): ByteArray? {
            return try {
                val matrix = Matrix()
                if (rotationDegrees != 0) {
                    matrix.postRotate(rotationDegrees.toFloat())
                }
                val rotatedBitmap = Bitmap.createBitmap(
                    srcBitmap, 0, 0, srcBitmap.width, srcBitmap.height, matrix, true
                )

                val mutableBitmap = rotatedBitmap.copy(Bitmap.Config.ARGB_8888, true)
                if (rotatedBitmap != srcBitmap) {
                    rotatedBitmap.recycle()
                }

                val canvas = Canvas(mutableBitmap)
                val w = mutableBitmap.width.toFloat()
                val h = mutableBitmap.height.toFloat()

                val boneLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(220, 0, 255, 120)
                    strokeWidth = 3f
                    style = Paint.Style.STROKE
                }
                val boneFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(40, 0, 255, 120)
                    style = Paint.Style.FILL
                }
                val axisXPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED; strokeWidth = 4f }
                val axisYPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GREEN; strokeWidth = 4f }
                val axisZPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.CYAN; strokeWidth = 4f }
                val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
                val pathBuffer = Path()

                val byName = processedFrame.joints.associateBy { it.name }.toMutableMap()

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

                fun mappedX(normX: Float): Float {
                    val x = if (mirrorX) 1.0f - normX else normX
                    return x * w
                }

                // Calculate 20 cm box width proportional to average torso scale in screen space
                val pixelScale = estimateScreenPixelsPerMeter(byName, h)
                val boxWidthMeters = 0.20f // Fixed 20 cm
                val boxRadiusPx = (boxWidthMeters * pixelScale * 0.5f).coerceIn(12f, 80f)

                // 1. Draw 3D Box Beams fully accounting for Z depth at both ends
                for ((a, b) in BONES) {
                    val ja = byName[a]
                    val jb = byName[b]
                    if (ja != null && jb != null && ja.visibility > 0.3f && jb.visibility > 0.3f) {
                        drawTorqued3DBoxBeam(
                            canvas = canvas,
                            pathBuffer = pathBuffer,
                            boneLinePaint = boneLinePaint,
                            boneFillPaint = boneFillPaint,
                            x1 = mappedX(ja.x), y1 = ja.y * h, z1 = ja.z,
                            x2 = mappedX(jb.x), y2 = jb.y * h, z2 = jb.z,
                            baseRadius = boxRadiusPx,
                            sourceJoint = ja,
                            targetJoint = jb
                        )
                    }
                }

                val axisLength = 30f
                val drawableJoints = byName.values.filter {
                    it.name in TARGET_DISPLAY_JOINTS && it.visibility > 0.3f
                }

                for (joint in drawableJoints) {
                    val px = mappedX(joint.x)
                    val py = joint.y * h

                    pointPaint.color = getZColor(joint.z)
                    canvas.drawCircle(px, py, 12f, pointPaint)

                    canvas.drawLine(px, py, px + axisLength, py, axisXPaint)
                    canvas.drawLine(px, py, px, py + axisLength, axisYPaint)
                    val zOffset = (joint.z / 150f).coerceIn(-1f, 1f) * axisLength
                    canvas.drawLine(px, py, px - zOffset, py - zOffset, axisZPaint)
                }

                val out = ByteArrayOutputStream()
                mutableBitmap.compress(Bitmap.CompressFormat.JPEG, 50, out)
                mutableBitmap.recycle()
                out.toByteArray()
            } catch (_: Exception) {
                null
            }
        }

        private fun estimateScreenPixelsPerMeter(joints: Map<String, JointSample>, viewHeight: Float): Float {
            val ls = joints["left_shoulder"]
            val rs = joints["right_shoulder"]
            val lh = joints["left_hip"]
            val rh = joints["right_hip"]

            if (ls != null && rs != null && lh != null && rh != null) {
                val shoulderY = (ls.y + rs.y) * 0.5f
                val hipY = (lh.y + rh.y) * 0.5f
                val torsoNormH = kotlin.math.abs(hipY - shoulderY)
                if (torsoNormH > 0.05f) {
                    val torsoPixels = torsoNormH * viewHeight
                    val estimatedTorsoMeters = 0.50f
                    return torsoPixels / estimatedTorsoMeters
                }
            }
            return viewHeight * 0.6f
        }

        private fun calculateJointTorqueAngle(source: JointSample, target: JointSample): Float {
            val dx = target.x - source.x
            val dy = -(target.y - source.y)
            val dz = (target.z - source.z) / 1000f

            val yaw = atan2(dx, dz)
            val pitch = atan2(dy, sqrt(dx * dx + dz * dz))

            return (yaw * 0.5f + pitch * 0.5f)
        }

        private fun drawTorqued3DBoxBeam(
            canvas: Canvas,
            pathBuffer: Path,
            boneLinePaint: Paint,
            boneFillPaint: Paint,
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

        private fun getZColor(z: Float): Int {
            val normalizedZ = ((z + 100f) / 200f).coerceIn(0f, 1f)
            val red = (255 * (1f - normalizedZ)).toInt()
            val green = (200 * (1f - kotlin.math.abs(normalizedZ - 0.5f) * 2)).toInt()
            val blue = (255 * normalizedZ).toInt()
            return Color.rgb(red, green, blue)
        }

        private val TARGET_DISPLAY_JOINTS = setOf(
            "head", "chest_mid", "hip_mid",
            "left_shoulder", "right_shoulder",
            "left_elbow", "right_elbow",
            "left_knee", "right_knee",
            "left_ankle", "right_ankle"
        )

        private val BONES = listOf(
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
