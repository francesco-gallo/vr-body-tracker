package com.vrproject.bodytracker

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

object PoseOscMapper {

    private var lastTorsoYaw = 0f
    private val lastRotations = HashMap<Int, Vec3>()

    fun toMessages(
        frame: PoseFrame,
        estimatedHeightMeters: Float
    ): List<OscMessageData> {
        return toVrchatTrackerMessages(
            frame = frame,
            estimatedHeightMeters = estimatedHeightMeters
        )
    }

    private fun toVrchatTrackerMessages(
        frame: PoseFrame,
        estimatedHeightMeters: Float
    ): List<OscMessageData> {
        val byName = frame.joints.associateBy { it.name }

        val leftHip = getByName(byName, "left_hip")
        val rightHip = getByName(byName, "right_hip")
        val leftShoulder = getByName(byName, "left_shoulder")
        val rightShoulder = getByName(byName, "right_shoulder")

        val head = averageJoint("head_mid", getByName(byName, "left_ear"), getByName(byName, "right_ear"))
            ?: averageJoint("head_mid", getByName(byName, "left_eye"), getByName(byName, "right_eye"))
            ?: getByName(byName, "nose")

        val hip = averageJoint("hip_mid", leftHip, rightHip)
        val chest = averageJoint("chest_mid", leftShoulder, rightShoulder)
        val leftFoot = getByName(byName, "left_ankle")
        val rightFoot = getByName(byName, "right_ankle")
        val leftKnee = getByName(byName, "left_knee")
        val rightKnee = getByName(byName, "right_knee")
        val leftElbow = getByName(byName, "left_elbow")
        val rightElbow = getByName(byName, "right_elbow")

        val rootAnchor = hip ?: chest ?: return emptyList()

        val observedHeight = estimateObservedHeight(byName)
        val safeObserved = max(0.2f, observedHeight)
        val safeTargetHeight = estimatedHeightMeters.coerceIn(1.0f, 2.5f)
        val metersPerNorm = safeTargetHeight / safeObserved
        val depthScale = metersPerNorm * 0.25f

        val torsoYaw = estimateTorsoYawDegrees(leftShoulder, rightShoulder, leftHip, rightHip)

        val messages = ArrayList<OscMessageData>(20)

        val torsoRot = Vec3(0f, torsoYaw, 0f)
        val headRot = Vec3(0f, torsoYaw, 0f)
        val leftFootRot = rotationFromDirection(3, leftKnee, leftFoot, defaultYaw = torsoYaw)
        val rightFootRot = rotationFromDirection(4, rightKnee, rightFoot, defaultYaw = torsoYaw)
        val leftKneeRot = rotationFromDirection(5, hip, leftKnee, defaultYaw = torsoYaw)
        val rightKneeRot = rotationFromDirection(6, hip, rightKnee, defaultYaw = torsoYaw)
        val leftElbowRot = rotationFromDirection(7, leftShoulder, leftElbow, defaultYaw = torsoYaw)
        val rightElbowRot = rotationFromDirection(8, rightShoulder, rightElbow, defaultYaw = torsoYaw)

        // Tracker VRChat Standard
        appendTracker(messages, 0, head, headRot, rootAnchor, metersPerNorm, depthScale)
        if (head != null) {
            val headPos = toTrackingVector(head, rootAnchor, metersPerNorm, depthScale)
            messages.add(
                OscMessageData(
                    address = "/tracking/trackers/head/position",
                    args = listOf(headPos.x, headPos.y, headPos.z)
                )
            )
            messages.add(
                OscMessageData(
                    address = "/tracking/trackers/head/rotation",
                    args = listOf(headRot.x, headRot.y, headRot.z)
                )
            )
        }

        appendTracker(messages, 1, hip, torsoRot, rootAnchor, metersPerNorm, depthScale)
        appendTracker(messages, 2, chest, torsoRot, rootAnchor, metersPerNorm, depthScale)
        appendTracker(messages, 3, leftFoot, leftFootRot, rootAnchor, metersPerNorm, depthScale)
        appendTracker(messages, 5, leftKnee, leftKneeRot, rootAnchor, metersPerNorm, depthScale)
        appendTracker(messages, 4, rightFoot, rightFootRot, rootAnchor, metersPerNorm, depthScale)
        appendTracker(messages, 6, rightKnee, rightKneeRot, rootAnchor, metersPerNorm, depthScale)
        appendTracker(messages, 7, leftElbow, leftElbowRot, rootAnchor, metersPerNorm, depthScale)
        appendTracker(messages, 8, rightElbow, rightElbowRot, rootAnchor, metersPerNorm, depthScale)

        return messages
    }

    private fun appendTracker(
        out: MutableList<OscMessageData>,
        id: Int,
        joint: JointSample?,
        rotationEuler: Vec3,
        origin: JointSample,
        metersPerNorm: Float,
        depthScale: Float
    ) {
        if (joint == null) return

        val p = toTrackingVector(joint, origin, metersPerNorm, depthScale)
        out.add(
            OscMessageData(
                address = "/tracking/trackers/$id/position",
                args = listOf(p.x, p.y, p.z)
            )
        )
        out.add(
            OscMessageData(
                address = "/tracking/trackers/$id/rotation",
                args = listOf(rotationEuler.x, rotationEuler.y, rotationEuler.z)
            )
        )
    }

    private fun estimateTorsoYawDegrees(
        leftShoulder: JointSample?,
        rightShoulder: JointSample?,
        leftHip: JointSample?,
        rightHip: JointSample?
    ): Float {
        val shoulderYaw = estimateYawFromLeftRight(leftShoulder, rightShoulder)
        val hipYaw = estimateYawFromLeftRight(leftHip, rightHip)

        val rawYaw = when {
            shoulderYaw != null && hipYaw != null -> (shoulderYaw + hipYaw) * 0.5f
            shoulderYaw != null -> shoulderYaw
            hipYaw != null -> hipYaw
            else -> lastTorsoYaw
        }

        val smoothedYaw = lerpAngle(lastTorsoYaw, rawYaw, 0.25f)
        lastTorsoYaw = smoothedYaw
        return smoothedYaw
    }

    private fun estimateYawFromLeftRight(left: JointSample?, right: JointSample?): Float? {
        if (left == null || right == null) return null

        val sideX = right.x - left.x
        val sideZ = right.z - left.z
        if (abs(sideX) < 0.0001f && abs(sideZ) < 0.0001f) return null

        val forwardX = -sideZ
        val forwardZ = sideX
        return radiansToDegrees(atan2(forwardX, forwardZ))
    }

    private fun rotationFromDirection(
        trackerId: Int,
        start: JointSample?,
        end: JointSample?,
        defaultYaw: Float
    ): Vec3 {
        val lastRot = lastRotations[trackerId] ?: Vec3(0f, defaultYaw, 0f)

        if (start == null || end == null || start.visibility < 0.4f || end.visibility < 0.4f) {
            return lastRot
        }

        val dx = end.x - start.x
        val dy = -(end.y - start.y)
        val dz = end.z - start.z

        val horizontal = sqrt((dx * dx) + (dz * dz))
        if (horizontal < 0.01f) {
            return lastRot
        }

        val rawYaw = radiansToDegrees(atan2(dx, dz))
        val rawPitch = radiansToDegrees(atan2(dy, horizontal))

        val newYaw = lerpAngle(lastRot.y, rawYaw, 0.3f)
        val newPitch = lerpAngle(lastRot.x, rawPitch, 0.3f)

        val smoothedVec = Vec3(x = newPitch, y = newYaw, z = 0f)
        lastRotations[trackerId] = smoothedVec

        return smoothedVec
    }

    private fun lerpAngle(from: Float, to: Float, alpha: Float): Float {
        var diff = (to - from) % 360f
        if (diff < -180f) diff += 360f
        if (diff > 180f) diff -= 360f
        return from + diff * alpha
    }

    private fun radiansToDegrees(value: Float): Float {
        return value * 57.29578f
    }

    private fun toTrackingVector(
        joint: JointSample,
        origin: JointSample,
        metersPerNorm: Float,
        depthScale: Float
    ): Vec3 {
        val x = (joint.x - origin.x) * metersPerNorm
        val y = (origin.y - joint.y) * metersPerNorm
        val z = (joint.z - origin.z) * depthScale
        return Vec3(x, y, z)
    }

    private fun getByName(byName: Map<String, JointSample>, name: String): JointSample? {
        return byName[name]
    }

    private fun averageJoint(name: String, left: JointSample?, right: JointSample?): JointSample? {
        if (left == null && right == null) return null
        if (left == null) return right
        if (right == null) return left

        return JointSample(
            name = name,
            x = (left.x + right.x) * 0.5f,
            y = (left.y + right.y) * 0.5f,
            z = (left.z + right.z) * 0.5f,
            visibility = (left.visibility + right.visibility) * 0.5f
        )
    }

    private fun estimateObservedHeight(byName: Map<String, JointSample>): Float {
        val yValues = listOfNotNull(
            byName["left_hip"]?.y,
            byName["right_hip"]?.y,
            byName["left_shoulder"]?.y,
            byName["right_shoulder"]?.y,
            byName["left_ankle"]?.y,
            byName["right_ankle"]?.y
        )

        if (yValues.size < 2) return 1f

        val minY = yValues.minOrNull() ?: return 1f
        val maxY = yValues.maxOrNull() ?: return 1f
        return abs(maxY - minY)
    }
}

private data class Vec3(
    val x: Float,
    val y: Float,
    val z: Float
)