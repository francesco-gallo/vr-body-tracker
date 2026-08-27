package com.vrproject.bodytracker

import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseDetector
import com.google.mlkit.vision.pose.PoseDetectorOptionsBase
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions

/**
 * Pose detection backed by ML Kit's streaming [PoseDetector].
 *
 * Unlike MediaPipe, ML Kit consumes the camera's [InputImage] directly (no manual
 * rotate/crop-to-square preprocessing) and reports results via a per-call [Task] listener,
 * so the image lifecycle and completion signal are handled entirely within a single
 * [analyze] invocation.
 */
class MlKitEngine(
    private val onFrame: PoseFrameCallback
) : PoseEngine {

    private val detector: PoseDetector = PoseDetection.getClient(
        PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .setPreferredHardwareConfigs(PoseDetectorOptionsBase.CPU_GPU)
            .build()
    )

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(
        imageProxy: ImageProxy,
        rotationDegrees: Int,
        shouldCaptureBitmap: Boolean,
        timestampMs: Long,
        onProcessingDone: () -> Unit
    ) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            onProcessingDone()
            return
        }

        val capturedBitmap = if (shouldCaptureBitmap) {
            try { imageProxy.toBitmap() } catch (_: Exception) { null }
        } else null

        val image = InputImage.fromMediaImage(mediaImage, rotationDegrees)
        val isRotated = rotationDegrees == 90 || rotationDegrees == 270
        val width = if (isRotated) imageProxy.height.toFloat() else imageProxy.width.toFloat()
        val height = if (isRotated) imageProxy.width.toFloat() else imageProxy.height.toFloat()

        detector.process(image)
            .addOnSuccessListener { pose ->
                val frame = convertPose(pose, width, height)
                onFrame(frame, capturedBitmap, rotationDegrees)
            }
            .addOnFailureListener {
                capturedBitmap?.recycle()
            }
            .addOnCompleteListener {
                onProcessingDone()
                imageProxy.close()
            }
    }

    private fun convertPose(pose: Pose, width: Float, height: Float): PoseFrame {
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
            if (type in BODY_TYPES) {
                val p2 = landmark.position
                val p3 = landmark.position3D
                joints += JointSample(
                    name = NAMES[type] ?: "type_$type",
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

    override fun close() {
        try { detector.close() } catch (_: Exception) {}
    }

    companion object {
        private val BODY_TYPES = setOf(
            PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER,
            PoseLandmark.LEFT_ELBOW, PoseLandmark.RIGHT_ELBOW,
            PoseLandmark.LEFT_WRIST, PoseLandmark.RIGHT_WRIST,
            PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP,
            PoseLandmark.LEFT_KNEE, PoseLandmark.RIGHT_KNEE,
            PoseLandmark.LEFT_ANKLE, PoseLandmark.RIGHT_ANKLE
        )

        private val NAMES = mapOf(
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
    }
}
