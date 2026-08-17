package com.vrproject.bodytracker

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

object PoseOscMapper {

    private var lastTorsoYaw = 0f
    private val lastRotations = HashMap<Int, Vec3>()
    private val reusableMessageList = ArrayList<OscMessageData>(20)

    fun toMessages(
        frame: PoseFrame,
        estimatedHeightMeters: Float,
        isFrontCamera: Boolean
    ): List<OscMessageData> {
        reusableMessageList.clear()
        return toVrchatTrackerMessages(
            frame = frame,
            estimatedHeightMeters = estimatedHeightMeters,
            isFrontCamera = isFrontCamera
        )
    }

    private fun toVrchatTrackerMessages(
        frame: PoseFrame,
        estimatedHeightMeters: Float,
        isFrontCamera: Boolean
    ): List<OscMessageData> {
        val joints = frame.joints

        val leftHip = findJoint(joints, "left_hip")
        val rightHip = findJoint(joints, "right_hip")
        val leftShoulder = findJoint(joints, "left_shoulder")
        val rightShoulder = findJoint(joints, "right_shoulder")

        // Usa direttamente la testa singola "head"
        val head = findJoint(joints, "head")

        val hip = averageJoint("hip_mid", leftHip, rightHip)
        val chest = averageJoint("chest_mid", leftShoulder, rightShoulder)
        val leftFoot = findJoint(joints, "left_ankle")
        val rightFoot = findJoint(joints, "right_ankle")
        val leftKnee = findJoint(joints, "left_knee")
        val rightKnee = findJoint(joints, "right_knee")
        val leftElbow = findJoint(joints, "left_elbow")
        val rightElbow = findJoint(joints, "right_elbow")

        val rootAnchor = hip ?: chest ?: return emptyList()

        val observedHeight = estimateObservedHeight(joints)
        val safeObserved = max(0.2f, observedHeight)
        val safeTargetHeight = estimatedHeightMeters.coerceIn(1.0f, 2.5f)
        val metersPerNorm = safeTargetHeight / safeObserved

        val torsoYaw = estimateTorsoYawDegrees(leftShoulder, rightShoulder, leftHip, rightHip)

        val torsoRot = Vec3(0f, torsoYaw, 0f)
        val headRot = Vec3(0f, torsoYaw, 0f)
        val leftFootRot = rotationFromDirection(3, leftKnee, leftFoot, defaultYaw = torsoYaw)
        val rightFootRot = rotationFromDirection(4, rightKnee, rightFoot, defaultYaw = torsoYaw)
        val leftKneeRot = rotationFromDirection(5, hip, leftKnee, defaultYaw = torsoYaw)
        val rightKneeRot = rotationFromDirection(6, hip, rightKnee, defaultYaw = torsoYaw)
        val leftElbowRot = rotationFromDirection(7, leftShoulder, leftElbow, defaultYaw = torsoYaw)
        val rightElbowRot = rotationFromDirection(8, rightShoulder, rightElbow, defaultYaw = torsoYaw)

        val xMultiplier = if (isFrontCamera) -1f else 1f

        // Head
        appendTracker(reusableMessageList, 0, head, headRot, rootAnchor, metersPerNorm, xMultiplier)
        if (head != null) {
            val headPos = toTrackingVector(head, rootAnchor, metersPerNorm, xMultiplier)
            reusableMessageList.add(
                OscMessageData(
                    address = "/tracking/trackers/head/position",
                    args = listOf(headPos.x, headPos.y, headPos.z)
                )
            )
            reusableMessageList.add(
                OscMessageData(
                    address = "/tracking/trackers/head/rotation",
                    args = listOf(headRot.x, headRot.y, headRot.z)
                )
            )
        }

        appendTracker(reusableMessageList, 1, hip, torsoRot, rootAnchor, metersPerNorm, xMultiplier)
        appendTracker(reusableMessageList, 2, chest, torsoRot, rootAnchor, metersPerNorm, xMultiplier)
        appendTracker(reusableMessageList, 3, leftFoot, leftFootRot, rootAnchor, metersPerNorm, xMultiplier)
        appendTracker(reusableMessageList, 5, leftKnee, leftKneeRot, rootAnchor, metersPerNorm, xMultiplier)
        appendTracker(reusableMessageList, 4, rightFoot, rightFootRot, rootAnchor, metersPerNorm, xMultiplier)
        appendTracker(reusableMessageList, 6, rightKnee, rightKneeRot, rootAnchor, metersPerNorm, xMultiplier)
        appendTracker(reusableMessageList, 7, leftElbow, leftElbowRot, rootAnchor, metersPerNorm, xMultiplier)
        appendTracker(reusableMessageList, 8, rightElbow, rightElbowRot, rootAnchor, metersPerNorm, xMultiplier)

        return reusableMessageList
    }

    private fun findJoint(joints: List<JointSample>, name: String): JointSample? {
        for (i in joints.indices) {
            if (joints[i].name == name) return joints[i]
        }
        return null
    }

    private fun appendTracker(
        out: MutableList<OscMessageData>,
        id: Int,
        joint: JointSample?,
        rotationEuler: Vec3,
        origin: JointSample,
        metersPerNorm: Float,
        xMultiplier: Float
    ) {
        if (joint == null) return

        val p = toTrackingVector(joint, origin, metersPerNorm, xMultiplier)
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
        xMultiplier: Float
    ): Vec3 {
        val x = (joint.x - origin.x) * metersPerNorm * xMultiplier
        val y = (origin.y - joint.y) * metersPerNorm
        val deltaZNorm = (joint.z - origin.z) / 1000f
        val z = deltaZNorm * metersPerNorm

        return Vec3(x, y, z)
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

    private fun estimateObservedHeight(joints: List<JointSample>): Float {
        var minY = Float.MAX_VALUE
        var maxY = Float.MIN_VALUE
        var count = 0

        for (i in joints.indices) {
            val j = joints[i]
            if (j.name == "left_hip" || j.name == "right_hip" ||
                j.name == "left_shoulder" || j.name == "right_shoulder" ||
                j.name == "left_ankle" || j.name == "right_ankle") {
                if (j.y < minY) minY = j.y
                if (j.y > maxY) maxY = j.y
                count++
            }
        }

        if (count < 2 || minY >= maxY) return 1f
        return abs(maxY - minY)
    }
}

private data class Vec3(
    val x: Float,
    val y: Float,
    val z: Float
)