package com.vrproject.bodytracker

import kotlin.math.max

data class StreamConfig(
    val smoothingAlpha: Float,
    val invertX: Boolean,
    val invertY: Boolean,
    val invertZ: Boolean
)

class PoseProcessor {
    private val previous = mutableMapOf<String, JointSample>()
    private var calibrationCenter = mutableMapOf<String, Triple<Float, Float, Float>>()

    fun clear() {
        previous.clear()
        calibrationCenter.clear()
    }

    fun calibrate(frame: PoseFrame) {
        calibrationCenter = frame.joints.associate { joint ->
            joint.name to Triple(joint.x, joint.y, joint.z)
        }.toMutableMap()
    }

    fun process(frame: PoseFrame, config: StreamConfig): PoseFrame {
        val alpha = config.smoothingAlpha.coerceIn(0f, 1f)
        val out = ArrayList<JointSample>(frame.joints.size)

        for (joint in frame.joints) {
            val centered = applyCalibration(joint)
            val mapped = applyAxisMapping(centered, config)
            val filtered = applySmoothing(mapped, alpha)
            previous[filtered.name] = filtered
            out += filtered
        }

        return PoseFrame(
            timestampMs = frame.timestampMs,
            imageWidth = frame.imageWidth,
            imageHeight = frame.imageHeight,
            joints = out
        )
    }

    private fun applyCalibration(joint: JointSample): JointSample {
        val center = calibrationCenter[joint.name] ?: return joint
        return joint.copy(
            x = joint.x - center.first,
            y = joint.y - center.second,
            z = joint.z - center.third
        )
    }

    private fun applyAxisMapping(joint: JointSample, config: StreamConfig): JointSample {
        return joint.copy(
            x = if (config.invertX) -joint.x else joint.x,
            y = if (config.invertY) -joint.y else joint.y,
            z = if (config.invertZ) -joint.z else joint.z
        )
    }

    private fun applySmoothing(joint: JointSample, alpha: Float): JointSample {
        val last = previous[joint.name] ?: return joint
        val a = max(0f, alpha)
        val keep = 1f - a
        return joint.copy(
            x = (a * last.x) + (keep * joint.x),
            y = (a * last.y) + (keep * joint.y),
            z = (a * last.z) + (keep * joint.z),
            visibility = (a * last.visibility) + (keep * joint.visibility)
        )
    }
}
