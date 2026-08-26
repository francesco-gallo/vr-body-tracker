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
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
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
    private val context: Context,
    private val modelTypeProvider: () -> TrackerModelType = { TrackerModelType.MEDIAPIPE_LITE },
    private val targetFpsProvider: () -> Int = { 60 },
    private val onCheckShouldCaptureBitmap: (() -> Boolean)? = null,
    private val onFrame: (PoseFrame, Bitmap?, Int) -> Unit
) : ImageAnalysis.Analyzer {

    private val isProcessing = AtomicBoolean(false)
    @Volatile private var lastAnalyzedTimeMs = 0L

    private var currentModelType: TrackerModelType? = null
    private var mediaPipeDetector: PoseLandmarker? = null
    private var mlKitDetector: com.google.mlkit.vision.pose.PoseDetector? = null

    @Volatile private var activeRawBitmap: Bitmap? = null
    @Volatile private var activeRotationDegrees: Int = 0

    private fun ensureEngine(desired: TrackerModelType) {
        if (currentModelType == desired && mediaPipeDetector != null) return

        closeEngines()
        currentModelType = desired

        when (desired) {
            TrackerModelType.MEDIAPIPE_LITE, TrackerModelType.MEDIAPIPE_FULL, TrackerModelType.MEDIAPIPE_HEAVY -> {
                val modelFile = when (desired) {
                    TrackerModelType.MEDIAPIPE_LITE -> "pose_landmarker_lite.task"
                    TrackerModelType.MEDIAPIPE_FULL -> "pose_landmarker_full.task"
                    TrackerModelType.MEDIAPIPE_HEAVY -> "pose_landmarker_heavy.task"
                    else -> "pose_landmarker_lite.task"
                }

                try {
                    val baseOptions = BaseOptions.builder()
                        .setModelAssetPath(modelFile)
                        .setDelegate(Delegate.GPU)
                        .build()

                    val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                        .setBaseOptions(baseOptions)
                        .setMinPoseDetectionConfidence(0.5f)
                        .setMinPosePresenceConfidence(0.5f)
                        .setMinTrackingConfidence(0.5f)
                        .setRunningMode(RunningMode.LIVE_STREAM)
                        .setResultListener { result, _ -> handleMediaPipeResult(result) }
                        .setErrorListener { _ -> isProcessing.set(false) }
                        .build()

                    mediaPipeDetector = PoseLandmarker.createFromOptions(context, options)
                } catch (_: Exception) {
                    val baseOptions = BaseOptions.builder()
                        .setModelAssetPath(modelFile)
                        .setDelegate(Delegate.CPU)
                        .build()

                    val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                        .setBaseOptions(baseOptions)
                        .setMinPoseDetectionConfidence(0.5f)
                        .setMinPosePresenceConfidence(0.5f)
                        .setMinTrackingConfidence(0.5f)
                        .setRunningMode(RunningMode.LIVE_STREAM)
                        .setResultListener { result, _ -> handleMediaPipeResult(result) }
                        .setErrorListener { _ -> isProcessing.set(false) }
                        .build()

                    mediaPipeDetector = PoseLandmarker.createFromOptions(context, options)
                }
            }
            TrackerModelType.MLKIT -> {
                val options = PoseDetectorOptions.Builder()
                    .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                    .build()
                mlKitDetector = PoseDetection.getClient(options)
            }
        }
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
        activeRotationDegrees = rotationDegrees

        val shouldCaptureBitmap = onCheckShouldCaptureBitmap?.invoke() ?: false

        if (desiredModel == TrackerModelType.MLKIT) {
            val mediaImage = imageProxy.image
            if (mediaImage == null) {
                isProcessing.set(false)
                imageProxy.close()
                return
            }

            val capturedBitmap = if (shouldCaptureBitmap) {
                try { imageProxy.toBitmap() } catch (_: Exception) { null }
            } else null

            val image = InputImage.fromMediaImage(mediaImage, rotationDegrees)
            val isRotated = rotationDegrees == 90 || rotationDegrees == 270
            val width = if (isRotated) imageProxy.height.toFloat() else imageProxy.width.toFloat()
            val height = if (isRotated) imageProxy.width.toFloat() else imageProxy.height.toFloat()

            mlKitDetector?.process(image)
                ?.addOnSuccessListener { pose ->
                    val frame = convertMlKitPose(pose, width, height)
                    onFrame(frame, capturedBitmap, rotationDegrees)
                }
                ?.addOnFailureListener {
                    capturedBitmap?.recycle()
                }
                ?.addOnCompleteListener {
                    isProcessing.set(false)
                    imageProxy.close()
                }
        } else {
            val rawBitmap = try {
                imageProxy.toBitmap()
            } catch (_: Exception) {
                isProcessing.set(false)
                imageProxy.close()
                return
            } finally {
                imageProxy.close()
            }

            val configToUse = rawBitmap.config ?: Bitmap.Config.ARGB_8888
            activeRawBitmap = if (shouldCaptureBitmap) {
                rawBitmap.copy(configToUse, true)
            } else null

            val rotatedBitmap = if (rotationDegrees != 0) {
                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true).also {
                    if (it != rawBitmap) rawBitmap.recycle()
                }
            } else {
                rawBitmap
            }

            // Perform center crop AFTER rotation to guarantee a true 1:1 aspect ratio square input
            val minDim = minOf(rotatedBitmap.width, rotatedBitmap.height)
            val startX = (rotatedBitmap.width - minDim) / 2
            val startY = (rotatedBitmap.height - minDim) / 2
            val squareBitmap = Bitmap.createBitmap(rotatedBitmap, startX, startY, minDim, minDim).also {
                if (it != rotatedBitmap) rotatedBitmap.recycle()
            }

            val mpImage: MPImage = BitmapImageBuilder(squareBitmap).build()

            try {
                // Call directly on analyzer thread to keep frame execution synchronized
                mediaPipeDetector?.detectAsync(mpImage, now)
            } catch (_: Exception) {
                squareBitmap.recycle()
                activeRawBitmap?.recycle()
                activeRawBitmap = null
                isProcessing.set(false)
            }
        }
    }

    private fun handleMediaPipeResult(result: PoseLandmarkerResult) {
        try {
            val poseFrame = convertMediaPipeResult(result)
            onFrame(poseFrame, activeRawBitmap, activeRotationDegrees)
        } finally {
            activeRawBitmap = null
            isProcessing.set(false)
        }
    }

    private fun convertMediaPipeResult(result: PoseLandmarkerResult): PoseFrame {
        val joints = mutableListOf<JointSample>()
        val landmarks = result.landmarks().firstOrNull()
        val worldLandmarks = result.worldLandmarks().firstOrNull()

        if (landmarks != null) {
            if (landmarks.isNotEmpty()) {
                val nose = landmarks[0]
                val worldNose = worldLandmarks?.getOrNull(0)
                joints += JointSample(
                    name = "head",
                    x = nose.x(),
                    y = nose.y(),
                    z = worldNose?.z() ?: (nose.z() * 1000f),
                    visibility = nose.presence().orElse(0.5f)
                )
            }

            for ((index, name) in MEDIAPIPE_LANDMARK_MAPPING) {
                if (index < landmarks.size) {
                    val lm = landmarks[index]
                    val worldLm = worldLandmarks?.getOrNull(index)

                    joints += JointSample(
                        name = name,
                        x = lm.x(),
                        y = lm.y(),
                        z = worldLm?.z() ?: (lm.z() * 1000f),
                        visibility = lm.presence().orElse(0.5f)
                    )
                }
            }
        }

        return PoseFrame(
            timestampMs = System.currentTimeMillis(),
            imageWidth = 480,
            imageHeight = 480,
            joints = joints
        )
    }

    private fun convertMlKitPose(pose: Pose, width: Float, height: Float): PoseFrame {
        val joints = mutableListOf<JointSample>()
        val noseLandmark = pose.getPoseLandmark(PoseLandmark.NOSE)
        if (noseLandmark != null) {
            val p2 = noseLandmark.position
            val p3 = noseLandmark.position3D
            joints += JointSample(
                name = "head",
                x = p2.x / width,
                y = p2.y / height,
                z = p3.z,
                visibility = noseLandmark.inFrameLikelihood
            )
        }

        for (landmark in pose.allPoseLandmarks) {
            val type = landmark.landmarkType
            if (type in MLKIT_BODY_TYPES) {
                val p2 = landmark.position
                val p3 = landmark.position3D
                joints += JointSample(
                    name = MLKIT_NAMES[type] ?: "type_$type",
                    x = p2.x / width,
                    y = p2.y / height,
                    z = p3.z,
                    visibility = landmark.inFrameLikelihood
                )
            }
        }

        return PoseFrame(
            timestampMs = System.currentTimeMillis(),
            imageWidth = width.toInt(),
            imageHeight = height.toInt(),
            joints = joints
        )
    }

    private fun closeEngines() {
        try { mediaPipeDetector?.close() } catch (_: Exception) {}
        try { mlKitDetector?.close() } catch (_: Exception) {}
        mediaPipeDetector = null
        mlKitDetector = null
    }

    fun close() {
        closeEngines()
    }

    companion object {
        private val MEDIAPIPE_LANDMARK_MAPPING = mapOf(
            11 to "left_shoulder",
            12 to "right_shoulder",
            13 to "left_elbow",
            14 to "right_elbow",
            15 to "left_wrist",
            16 to "right_wrist",
            23 to "left_hip",
            24 to "right_hip",
            25 to "left_knee",
            26 to "right_knee",
            27 to "left_ankle",
            28 to "right_ankle"
        )

        private val MLKIT_BODY_TYPES = setOf(
            PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER,
            PoseLandmark.LEFT_ELBOW, PoseLandmark.RIGHT_ELBOW,
            PoseLandmark.LEFT_WRIST, PoseLandmark.RIGHT_WRIST,
            PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP,
            PoseLandmark.LEFT_KNEE, PoseLandmark.RIGHT_KNEE,
            PoseLandmark.LEFT_ANKLE, PoseLandmark.RIGHT_ANKLE
        )

        private val MLKIT_NAMES = mapOf(
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