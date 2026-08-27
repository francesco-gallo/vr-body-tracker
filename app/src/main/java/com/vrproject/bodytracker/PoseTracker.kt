package com.vrproject.bodytracker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

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
            rotationDegrees: Int
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

                val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(220, 0, 255, 120)
                    strokeWidth = 6f
                    style = Paint.Style.STROKE
                }
                val axisXPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED; strokeWidth = 4f }
                val axisYPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GREEN; strokeWidth = 4f }
                val axisZPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.CYAN; strokeWidth = 4f }
                val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

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

                for ((a, b) in BONES) {
                    val ja = byName[a]
                    val jb = byName[b]
                    if (ja != null && jb != null && ja.visibility > 0.3f && jb.visibility > 0.3f) {
                        canvas.drawLine(ja.x * w, ja.y * h, jb.x * w, jb.y * h, linePaint)
                    }
                }

                val axisLength = 30f
                val drawableJoints = byName.values.filter {
                    it.name in TARGET_DISPLAY_JOINTS && it.visibility > 0.3f
                }

                for (joint in drawableJoints) {
                    val px = joint.x * w
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
