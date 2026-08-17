package com.vrproject.bodytracker

import kotlin.math.abs

data class StreamConfig(
    val smoothingAlpha: Float = 0.35f
)

class PoseProcessor {
    private val smoothedJoints = HashMap<String, JointSample>()
    private val calibrationOffsets = HashMap<String, Vec3Offset>()

    fun calibrate(frame: PoseFrame) {
        calibrationOffsets.clear()
        for (joint in frame.joints) {
            calibrationOffsets[joint.name] = Vec3Offset(joint.x, joint.y, joint.z)
        }
    }

    fun clear() {
        smoothedJoints.clear()
        calibrationOffsets.clear()
    }

    fun process(frame: PoseFrame, config: StreamConfig): PoseFrame {
        val outJoints = ArrayList<JointSample>(frame.joints.size)

        for (joint in frame.joints) {
            // 1. Sottrazione offset di calibrazione neutra (se presente)
            val offset = calibrationOffsets[joint.name]
            val calibratedX = if (offset != null) joint.x - offset.x else joint.x
            val calibratedY = if (offset != null) joint.y - offset.y else joint.y
            val calibratedZ = if (offset != null) joint.z - offset.z else joint.z

            // 2. Smoothing (Filtro Passa-Basso Exponential Moving Average)
            val previous = smoothedJoints[joint.name]
            val alpha = config.smoothingAlpha.coerceIn(0f, 0.95f)

            val finalJoint = if (previous == null || alpha <= 0.01f) {
                JointSample(
                    name = joint.name,
                    x = calibratedX,
                    y = calibratedY,
                    z = calibratedZ,
                    visibility = joint.visibility
                )
            } else {
                JointSample(
                    name = joint.name,
                    x = lerp(previous.x, calibratedX, 1f - alpha),
                    y = lerp(previous.y, calibratedY, 1f - alpha),
                    z = lerp(previous.z, calibratedZ, 1f - alpha),
                    visibility = lerp(previous.visibility, joint.visibility, 1f - alpha)
                )
            }

            smoothedJoints[joint.name] = finalJoint
            outJoints.add(finalJoint)
        }

        return PoseFrame(
            timestampMs = frame.timestampMs,
            imageWidth = frame.imageWidth,
            imageHeight = frame.imageHeight,
            joints = outJoints
        )
    }

    private fun lerp(start: Float, stop: Float, amount: Float): Float {
        return start + (stop - start) * amount
    }

    private data class Vec3Offset(
        val x: Float,
        val y: Float,
        val z: Float
    )
}