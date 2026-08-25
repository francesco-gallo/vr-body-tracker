package com.vrproject.bodytracker

data class StreamConfig(
    val smoothingAlpha: Float = 0.35f
)

class PoseProcessor {
    private val smoothedJoints = HashMap<String, JointSample>()
    private val reusableProcessedJoints = ArrayList<JointSample>(32)

    fun calibrate(frame: PoseFrame) {
        // Calibration logic handled natively during OSC translation relative to root anchor
    }

    fun clear() {
        smoothedJoints.clear()
        reusableProcessedJoints.clear()
    }

    fun process(frame: PoseFrame, config: StreamConfig): PoseFrame {
        reusableProcessedJoints.clear()
        val alpha = config.smoothingAlpha.coerceIn(0f, 0.95f)

        for (i in frame.joints.indices) {
            val joint = frame.joints[i]

            // Exponential Moving Average (EMA) smoothing without modifying coordinate space
            val previous = smoothedJoints[joint.name]

            val finalJoint = if (previous == null || alpha <= 0.01f) {
                JointSample(
                    name = joint.name,
                    x = joint.x,
                    y = joint.y,
                    z = joint.z,
                    visibility = joint.visibility
                )
            } else {
                JointSample(
                    name = joint.name,
                    x = lerp(previous.x, joint.x, 1f - alpha),
                    y = lerp(previous.y, joint.y, 1f - alpha),
                    z = lerp(previous.z, joint.z, 1f - alpha),
                    visibility = lerp(previous.visibility, joint.visibility, 1f - alpha)
                )
            }

            smoothedJoints[joint.name] = finalJoint
            reusableProcessedJoints.add(finalJoint)
        }

        return PoseFrame(
            timestampMs = frame.timestampMs,
            imageWidth = frame.imageWidth,
            imageHeight = frame.imageHeight,
            joints = ArrayList(reusableProcessedJoints)
        )
    }

    private fun lerp(start: Float, stop: Float, amount: Float): Float {
        return start + (stop - start) * amount
    }
}