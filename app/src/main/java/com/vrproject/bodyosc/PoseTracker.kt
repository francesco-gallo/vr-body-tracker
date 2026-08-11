package com.vrproject.bodyosc

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
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
    private val onFrame: (PoseFrame) -> Unit
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

        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        val width = imageProxy.width.toFloat().coerceAtLeast(1f)
        val height = imageProxy.height.toFloat().coerceAtLeast(1f)

        detector.process(image)
            .addOnSuccessListener { pose ->
                onFrame(convertPose(pose, width, height))
            }
            .addOnCompleteListener {
                isProcessing.set(false)
                imageProxy.close()
            }
    }

    fun close() {
        detector.close()
    }

    private fun convertPose(pose: Pose, width: Float, height: Float): PoseFrame {
        val joints = mutableListOf<JointSample>()
        for (landmark in pose.allPoseLandmarks) {
            if (landmark.landmarkType !in BODY_LANDMARK_TYPES) {
                continue
            }
            val p2 = landmark.position
            val p3 = landmark.position3D
            joints += JointSample(
                name = LANDMARK_NAMES[landmark.landmarkType] ?: "type_${landmark.landmarkType}",
                x = p2.x / width,
                y = p2.y / height,
                z = p3.z,
                visibility = landmark.inFrameLikelihood
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
        private val BODY_LANDMARK_TYPES: Set<Int> = setOf(
            PoseLandmark.NOSE,
            PoseLandmark.LEFT_EYE,
            PoseLandmark.RIGHT_EYE,
            PoseLandmark.LEFT_EAR,
            PoseLandmark.RIGHT_EAR,
            PoseLandmark.LEFT_SHOULDER,
            PoseLandmark.RIGHT_SHOULDER,
            PoseLandmark.LEFT_ELBOW,
            PoseLandmark.RIGHT_ELBOW,
            PoseLandmark.LEFT_WRIST,
            PoseLandmark.RIGHT_WRIST,
            PoseLandmark.LEFT_PINKY,
            PoseLandmark.RIGHT_PINKY,
            PoseLandmark.LEFT_INDEX,
            PoseLandmark.RIGHT_INDEX,
            PoseLandmark.LEFT_THUMB,
            PoseLandmark.RIGHT_THUMB,
            PoseLandmark.LEFT_HIP,
            PoseLandmark.RIGHT_HIP,
            PoseLandmark.LEFT_KNEE,
            PoseLandmark.RIGHT_KNEE,
            PoseLandmark.LEFT_ANKLE,
            PoseLandmark.RIGHT_ANKLE,
            PoseLandmark.LEFT_HEEL,
            PoseLandmark.RIGHT_HEEL,
            PoseLandmark.LEFT_FOOT_INDEX,
            PoseLandmark.RIGHT_FOOT_INDEX
        )

        private val LANDMARK_NAMES: Map<Int, String> = mapOf(
            PoseLandmark.NOSE to "nose",
            PoseLandmark.LEFT_EYE to "left_eye",
            PoseLandmark.RIGHT_EYE to "right_eye",
            PoseLandmark.LEFT_EAR to "left_ear",
            PoseLandmark.RIGHT_EAR to "right_ear",
            PoseLandmark.LEFT_SHOULDER to "left_shoulder",
            PoseLandmark.RIGHT_SHOULDER to "right_shoulder",
            PoseLandmark.LEFT_ELBOW to "left_elbow",
            PoseLandmark.RIGHT_ELBOW to "right_elbow",
            PoseLandmark.LEFT_WRIST to "left_wrist",
            PoseLandmark.RIGHT_WRIST to "right_wrist",
            PoseLandmark.LEFT_PINKY to "left_pinky",
            PoseLandmark.RIGHT_PINKY to "right_pinky",
            PoseLandmark.LEFT_INDEX to "left_index",
            PoseLandmark.RIGHT_INDEX to "right_index",
            PoseLandmark.LEFT_THUMB to "left_thumb",
            PoseLandmark.RIGHT_THUMB to "right_thumb",
            PoseLandmark.LEFT_HIP to "left_hip",
            PoseLandmark.RIGHT_HIP to "right_hip",
            PoseLandmark.LEFT_KNEE to "left_knee",
            PoseLandmark.RIGHT_KNEE to "right_knee",
            PoseLandmark.LEFT_ANKLE to "left_ankle",
            PoseLandmark.RIGHT_ANKLE to "right_ankle",
            PoseLandmark.LEFT_HEEL to "left_heel",
            PoseLandmark.RIGHT_HEEL to "right_heel",
            PoseLandmark.LEFT_FOOT_INDEX to "left_foot_index",
            PoseLandmark.RIGHT_FOOT_INDEX to "right_foot_index"
        )
    }
}
