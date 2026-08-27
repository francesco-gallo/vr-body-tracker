package com.vrproject.bodytracker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

/**
 * Pose detection backed by MediaPipe's PoseLandmarker task.
 *
 * Unlike ML Kit, results arrive asynchronously via [PoseLandmarker.PoseLandmarkerOptions]'s
 * result listener rather than as a return value from [analyze], so the in-flight bitmap and
 * rotation are cached and consumed when [handleResult] fires.
 */
class MediaPipeEngine(
    context: Context,
    modelFile: String,
    private val onFrame: PoseFrameCallback
) : PoseEngine {

    private val detector: PoseLandmarker = createDetector(context, modelFile, Delegate.GPU)
        ?: createDetector(context, modelFile, Delegate.CPU)
        ?: throw IllegalStateException("Unable to initialize MediaPipe PoseLandmarker")

    @Volatile private var activeRawBitmap: Bitmap? = null
    @Volatile private var activeRotationDegrees: Int = 0
    @Volatile private var onProcessingDone: (() -> Unit)? = null

    private fun createDetector(context: Context, modelFile: String, delegate: Delegate): PoseLandmarker? {
        return try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(modelFile)
                .setDelegate(delegate)
                .build()

            val options = PoseLandmarker.PoseLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setMinPoseDetectionConfidence(0.5f)
                .setMinPosePresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener { result, _ -> handleResult(result) }
                .setErrorListener { _ -> onProcessingDone?.invoke() }
                .build()

            PoseLandmarker.createFromOptions(context, options)
        } catch (_: Exception) {
            null
        }
    }

    override fun analyze(
        imageProxy: ImageProxy,
        rotationDegrees: Int,
        shouldCaptureBitmap: Boolean,
        timestampMs: Long,
        onProcessingDone: () -> Unit
    ) {
        val rawBitmap = try {
            imageProxy.toBitmap()
        } catch (_: Exception) {
            imageProxy.close()
            onProcessingDone()
            return
        } finally {
            imageProxy.close()
        }

        val configToUse = rawBitmap.config ?: Bitmap.Config.ARGB_8888
        val capturedBitmap = if (shouldCaptureBitmap) rawBitmap.copy(configToUse, true) else null

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

        this.activeRawBitmap = capturedBitmap
        this.activeRotationDegrees = rotationDegrees
        this.onProcessingDone = onProcessingDone

        try {
            // Call directly on analyzer thread to keep frame execution synchronized
            detector.detectAsync(mpImage, timestampMs)
        } catch (_: Exception) {
            squareBitmap.recycle()
            this.activeRawBitmap?.recycle()
            this.activeRawBitmap = null
            this.onProcessingDone = null
            onProcessingDone()
        }
    }

    private fun handleResult(result: PoseLandmarkerResult) {
        try {
            val poseFrame = convertResult(result)
            onFrame(poseFrame, activeRawBitmap, activeRotationDegrees)
        } finally {
            activeRawBitmap = null
            val done = onProcessingDone
            onProcessingDone = null
            done?.invoke()
        }
    }

    private fun convertResult(result: PoseLandmarkerResult): PoseFrame {
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

            for ((index, name) in LANDMARK_MAPPING) {
                if (index < landmarks.size) {
                    val lm = landmarks[index]
                    val worldLm = worldLandmarks?.getOrNull(index)

                    joints += JointSample(
                        name = name,
                        x = lm.x(),
                        y = lm.y(),
                        z = (worldLm?.z() ?: lm.z()) * 1000f,
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

    override fun close() {
        try { detector.close() } catch (_: Exception) {}
    }

    companion object {
        fun modelFileFor(modelType: TrackerModelType): String = when (modelType) {
            TrackerModelType.MEDIAPIPE_LITE -> "pose_landmarker_lite.task"
            TrackerModelType.MEDIAPIPE_FULL -> "pose_landmarker_full.task"
            TrackerModelType.MEDIAPIPE_HEAVY -> "pose_landmarker_heavy.task"
            TrackerModelType.MLKIT -> "pose_landmarker_lite.task"
        }

        private val LANDMARK_MAPPING = mapOf(
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
    }
}
