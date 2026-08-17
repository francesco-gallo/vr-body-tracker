package com.vrproject.bodytracker

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
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

class PoseTracker(
    private val onCheckShouldCaptureBitmap: (() -> Boolean)? = null,
    private val onFrame: (PoseFrame, Bitmap?, Int) -> Unit
) : ImageAnalysis.Analyzer {

    private val isProcessing = AtomicBoolean(false)

    private val detector by lazy {
        val options = PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()
        PoseDetection.getClient(options)
    }

    override fun analyze(imageProxy: ImageProxy) {
        if (!isProcessing.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            isProcessing.set(false)
            imageProxy.close()
            return
        }

        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val image = InputImage.fromMediaImage(mediaImage, rotationDegrees)

        val isRotated = rotationDegrees == 90 || rotationDegrees == 270
        val width = if (isRotated) imageProxy.height.toFloat() else imageProxy.width.toFloat()
        val height = if (isRotated) imageProxy.width.toFloat() else imageProxy.height.toFloat()

        // Converte in Bitmap solo se c'è un browser connesso al server web
        val shouldCaptureBitmap = onCheckShouldCaptureBitmap?.invoke() ?: false
        val rawBitmap = if (shouldCaptureBitmap) {
            try {
                imageProxy.toBitmap()
            } catch (_: Exception) {
                null
            }
        } else null

        detector.process(image)
            .addOnSuccessListener { pose ->
                val poseFrame = convertPose(pose, width, height)
                onFrame(poseFrame, rawBitmap, rotationDegrees)
            }
            .addOnCompleteListener {
                isProcessing.set(false)
                imageProxy.close()
            }
    }

    fun close() {
        detector.close()
    }

    private fun ImageProxy.toBitmap(): Bitmap? {
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, this.width, this.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, this.width, this.height), 100, out)
        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    private fun convertPose(pose: Pose, width: Float, height: Float): PoseFrame {
        val joints = mutableListOf<JointSample>()

        var headXSum = 0f
        var headYSum = 0f
        var headZSum = 0f
        var headVisSum = 0f
        var headCount = 0

        for (landmark in pose.allPoseLandmarks) {
            val type = landmark.landmarkType
            if (type in HEAD_LANDMARK_TYPES) {
                headXSum += landmark.position.x / width
                headYSum += landmark.position.y / height
                headZSum += landmark.position3D.z
                headVisSum += landmark.inFrameLikelihood
                headCount++
            } else if (type in BODY_LANDMARK_TYPES) {
                val p2 = landmark.position
                val p3 = landmark.position3D
                joints += JointSample(
                    name = LANDMARK_NAMES[type] ?: "type_$type",
                    x = p2.x / width,
                    y = p2.y / height,
                    z = p3.z,
                    visibility = landmark.inFrameLikelihood
                )
            }
        }

        if (headCount > 0) {
            joints += JointSample(
                name = "head",
                x = headXSum / headCount,
                y = headYSum / headCount,
                z = headZSum / headCount,
                visibility = headVisSum / headCount
            )
        }

        return PoseFrame(
            timestampMs = System.currentTimeMillis(),
            imageWidth = width.toInt(),
            imageHeight = height.toInt(),
            joints = joints
        )
    }

    companion object {
        private val HEAD_LANDMARK_TYPES: Set<Int> = setOf(
            PoseLandmark.NOSE,
            PoseLandmark.LEFT_EYE,
            PoseLandmark.RIGHT_EYE,
            PoseLandmark.LEFT_EAR,
            PoseLandmark.RIGHT_EAR
        )

        private val BODY_LANDMARK_TYPES: Set<Int> = setOf(
            PoseLandmark.LEFT_SHOULDER,
            PoseLandmark.RIGHT_SHOULDER,
            PoseLandmark.LEFT_ELBOW,
            PoseLandmark.RIGHT_ELBOW,
            PoseLandmark.LEFT_WRIST,
            PoseLandmark.RIGHT_WRIST,
            PoseLandmark.LEFT_HIP,
            PoseLandmark.RIGHT_HIP,
            PoseLandmark.LEFT_KNEE,
            PoseLandmark.RIGHT_KNEE,
            PoseLandmark.LEFT_ANKLE,
            PoseLandmark.RIGHT_ANKLE
        )

        private val LANDMARK_NAMES: Map<Int, String> = mapOf(
            PoseLandmark.LEFT_SHOULDER to "left_shoulder",
            PoseLandmark.RIGHT_SHOULDER to "right_shoulder",
            PoseLandmark.LEFT_ELBOW to "left_elbow",
            PoseLandmark.RIGHT_ELBOW to "right_elbow",
            PoseLandmark.LEFT_WRIST to "left_wrist",
            PoseLandmark.RIGHT_WRIST to "right_wrist",
            PoseLandmark.LEFT_HIP to "left_hip",
            PoseLandmark.RIGHT_HIP to "right_hip",
            PoseLandmark.LEFT_KNEE to "left_knee",
            PoseLandmark.RIGHT_KNEE to "right_knee",
            PoseLandmark.LEFT_ANKLE to "left_ankle",
            PoseLandmark.RIGHT_ANKLE to "right_ankle"
        )

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
                val canvas = Canvas(mutableBitmap)
                val w = mutableBitmap.width.toFloat()
                val h = mutableBitmap.height.toFloat()

                val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(200, 0, 255, 0)
                    strokeWidth = 5f
                    style = Paint.Style.STROKE
                }
                val axisXPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED; strokeWidth = 3f }
                val axisYPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GREEN; strokeWidth = 3f }
                val axisZPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.CYAN; strokeWidth = 3f }
                val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

                val byName = processedFrame.joints.associateBy { it.name }.toMutableMap()

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

                for ((a, b) in BONES) {
                    val ja = byName[a]
                    val jb = byName[b]
                    if (ja != null && jb != null && ja.visibility > 0.25f && jb.visibility > 0.25f) {
                        canvas.drawLine(ja.x * w, ja.y * h, jb.x * w, jb.y * h, linePaint)
                    }
                }

                val axisLength = 25f
                for (joint in processedFrame.joints) {
                    if (joint.visibility > 0.25f) {
                        val px = joint.x * w
                        val py = joint.y * h

                        pointPaint.color = getZColor(joint.z)
                        canvas.drawCircle(px, py, 10f, pointPaint)

                        canvas.drawLine(px, py, px + axisLength, py, axisXPaint)
                        canvas.drawLine(px, py, px, py + axisLength, axisYPaint)
                        val zOffset = (joint.z / 150f).coerceIn(-1f, 1f) * axisLength
                        canvas.drawLine(px, py, px - zOffset, py - zOffset, axisZPaint)
                    }
                }

                val out = ByteArrayOutputStream()
                mutableBitmap.compress(Bitmap.CompressFormat.JPEG, 60, out)
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