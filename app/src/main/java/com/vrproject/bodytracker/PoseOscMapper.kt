package com.vrproject.bodytracker

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

enum class OscOutputMode {
    VRCHAT_TRACKERS,
    RAW_LANDMARKS
}

object PoseOscMapper {
    fun toMessages(
        frame: PoseFrame,
        prefix: String,
        mode: OscOutputMode,
        includeHeadAlignment: Boolean,
        estimatedHeightMeters: Float,
        enabledBodyParts: BodyPartSelection = BodyPartSelection()
    ): List<OscMessageData> {
        return when (mode) {
            OscOutputMode.VRCHAT_TRACKERS -> toVrchatTrackerMessages(
                frame = frame,
                includeHeadAlignment = includeHeadAlignment,
                estimatedHeightMeters = estimatedHeightMeters,
                enabledBodyParts = enabledBodyParts
            )

            OscOutputMode.RAW_LANDMARKS -> toRawMessages(frame, prefix, enabledBodyParts)
        }
    }

    private fun toRawMessages(
        frame: PoseFrame,
        prefix: String,
        enabledBodyParts: BodyPartSelection
    ): List<OscMessageData> {
        val root = sanitizePrefix(prefix)
        val messages = mutableListOf<OscMessageData>()

        for (joint in frame.joints) {
            if (!isJointAllowedByBodyPart(joint.name, enabledBodyParts)) {
                continue
            }
            messages += OscMessageData(
                address = "$root/${joint.name}",
                args = listOf(joint.x, joint.y, joint.z, joint.visibility)
            )
        }

        messages += OscMessageData(
            address = "$root/frame_time_ms",
            args = listOf(frame.timestampMs.toInt())
        )

        return messages
    }

    private fun toVrchatTrackerMessages(
        frame: PoseFrame,
        includeHeadAlignment: Boolean,
        estimatedHeightMeters: Float,
        enabledBodyParts: BodyPartSelection
    ): List<OscMessageData> {
        val byName = frame.joints.associateBy { it.name }

        val hip = averageByName(byName, "left_hip", "right_hip")
        val chest = averageByName(byName, "left_shoulder", "right_shoulder")
        val head = averageByName(byName, "left_ear", "right_ear")
            ?: averageByName(byName, "left_eye", "right_eye")
            ?: getByName(byName, "nose")
        val leftFoot = getByName(byName, "left_ankle")
        val rightFoot = getByName(byName, "right_ankle")
        val leftKnee = getByName(byName, "left_knee")
        val rightKnee = getByName(byName, "right_knee")
        val leftElbow = getByName(byName, "left_elbow")
        val rightElbow = getByName(byName, "right_elbow")
        val leftShoulder = getByName(byName, "left_shoulder")
        val rightShoulder = getByName(byName, "right_shoulder")
        val leftHip = getByName(byName, "left_hip")
        val rightHip = getByName(byName, "right_hip")

        val origin = hip ?: chest ?: return emptyList()

        val observedHeight = estimateObservedHeight(byName)
        val safeObserved = max(0.2f, observedHeight)
        val safeTargetHeight = estimatedHeightMeters.coerceIn(1.0f, 2.5f)
        val metersPerNorm = safeTargetHeight / safeObserved
        val depthScale = metersPerNorm * 0.25f
        val torsoYaw = estimateTorsoYawDegrees(leftShoulder, rightShoulder, leftHip, rightHip)

        val messages = mutableListOf<OscMessageData>()
        val hipRot = Vec3(0f, torsoYaw, 0f)
        val chestRot = Vec3(0f, torsoYaw, 0f)
        val headRot = Vec3(0f, torsoYaw, 0f)
        val leftFootRot = rotationFromDirection(leftKnee, leftFoot, defaultYaw = torsoYaw)
        val rightFootRot = rotationFromDirection(rightKnee, rightFoot, defaultYaw = torsoYaw)
        val leftKneeRot = rotationFromDirection(hip, leftKnee, defaultYaw = torsoYaw)
        val rightKneeRot = rotationFromDirection(hip, rightKnee, defaultYaw = torsoYaw)
        val leftElbowRot = rotationFromDirection(leftShoulder, leftElbow, defaultYaw = torsoYaw)
        val rightElbowRot = rotationFromDirection(rightShoulder, rightElbow, defaultYaw = torsoYaw)

        if (enabledBodyParts.head) {
            appendTracker(messages, 0, head, headRot, origin, metersPerNorm, depthScale)
        }
        if (enabledBodyParts.torso) {
            appendTracker(messages, 1, hip, hipRot, origin, metersPerNorm, depthScale)
            appendTracker(messages, 2, chest, chestRot, origin, metersPerNorm, depthScale)
        }
        if (enabledBodyParts.rightLeg) {
            appendTracker(messages, 3, leftFoot, leftFootRot, origin, metersPerNorm, depthScale)
        }
        if (enabledBodyParts.leftLeg) {
            appendTracker(messages, 4, rightFoot, rightFootRot, origin, metersPerNorm, depthScale)
        }
        if (enabledBodyParts.leftLeg) {
            appendTracker(messages, 5, leftKnee, leftKneeRot, origin, metersPerNorm, depthScale)
        }
        if (enabledBodyParts.rightLeg) {
            appendTracker(messages, 6, rightKnee, rightKneeRot, origin, metersPerNorm, depthScale)
        }
        if (enabledBodyParts.leftArm) {
            appendTracker(messages, 7, leftElbow, leftElbowRot, origin, metersPerNorm, depthScale)
        }
        if (enabledBodyParts.rightArm) {
            appendTracker(messages, 8, rightElbow, rightElbowRot, origin, metersPerNorm, depthScale)
        }

        // Body-only mode: no face or head landmarks are sent.

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
        if (joint == null) {
            return
        }

        val p = toTrackingVector(joint, origin, metersPerNorm, depthScale)
        out += OscMessageData(
            address = "/tracking/trackers/$id/position",
            args = listOf(p.x, p.y, p.z)
        )
        out += OscMessageData(
            address = "/tracking/trackers/$id/rotation",
            args = listOf(rotationEuler.x, rotationEuler.y, rotationEuler.z)
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

        return when {
            shoulderYaw != null && hipYaw != null -> (shoulderYaw + hipYaw) * 0.5f
            shoulderYaw != null -> shoulderYaw
            hipYaw != null -> hipYaw
            else -> 0f
        }
    }

    private fun estimateYawFromLeftRight(left: JointSample?, right: JointSample?): Float? {
        if (left == null || right == null) {
            return null
        }

        val sideX = right.x - left.x
        val sideZ = right.z - left.z
        if (abs(sideX) < 0.0001f && abs(sideZ) < 0.0001f) {
            return null
        }

        val forwardX = -sideZ
        val forwardZ = sideX
        return radiansToDegrees(atan2(forwardX, forwardZ))
    }

    private fun rotationFromDirection(start: JointSample?, end: JointSample?, defaultYaw: Float): Vec3 {
        if (start == null || end == null) {
            return Vec3(0f, defaultYaw, 0f)
        }

        val dx = end.x - start.x
        val dy = start.y - end.y
        val dz = end.z - start.z

        val yaw = radiansToDegrees(atan2(dx, dz))
        val horizontal = sqrt((dx * dx) + (dz * dz)).coerceAtLeast(0.0001f)
        val pitch = radiansToDegrees(atan2(dy, horizontal))

        return Vec3(
            x = pitch,
            y = yaw,
            z = 0f
        )
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
        // Convert from image-space normalized coords to Unity-like tracker coords.
        val x = (joint.x - origin.x) * metersPerNorm
        val y = (origin.y - joint.y) * metersPerNorm
        val z = (joint.z - origin.z) * depthScale
        return Vec3(x, y, z)
    }

    private fun getByName(byName: Map<String, JointSample>, name: String): JointSample? {
        return byName[name]
    }

    private fun averageByName(byName: Map<String, JointSample>, a: String, b: String): JointSample? {
        val left = byName[a]
        val right = byName[b]
        if (left == null && right == null) {
            return null
        }
        if (left == null) {
            return right
        }
        if (right == null) {
            return left
        }
        return JointSample(
            name = "${a}_${b}_mid",
            x = (left.x + right.x) * 0.5f,
            y = (left.y + right.y) * 0.5f,
            z = (left.z + right.z) * 0.5f,
            visibility = (left.visibility + right.visibility) * 0.5f
        )
    }

    private fun isJointAllowedByBodyPart(name: String, enabledBodyParts: BodyPartSelection): Boolean {
        val headNames = setOf("left_ear", "right_ear", "nose", "left_eye", "right_eye")
        val torsoNames = setOf(
            "left_shoulder", "right_shoulder", "left_hip", "right_hip",
            "left_pinky", "right_pinky", "left_index", "right_index",
            "left_thumb", "right_thumb"
        )
        val leftArmNames = setOf("left_elbow", "left_wrist", "left_pinky", "left_index", "left_thumb")
        val rightArmNames = setOf("right_elbow", "right_wrist", "right_pinky", "right_index", "right_thumb")
        val leftLegNames = setOf("left_knee", "left_ankle", "left_heel", "left_foot_index")
        val rightLegNames = setOf("right_knee", "right_ankle", "right_heel", "right_foot_index")

        return when {
            headNames.contains(name) -> enabledBodyParts.head
            torsoNames.contains(name) -> enabledBodyParts.torso
            leftArmNames.contains(name) -> enabledBodyParts.leftArm
            rightArmNames.contains(name) -> enabledBodyParts.rightArm
            leftLegNames.contains(name) -> enabledBodyParts.leftLeg
            rightLegNames.contains(name) -> enabledBodyParts.rightLeg
            else -> true
        }
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

        if (yValues.size < 2) {
            return 1f
        }

        val minY = yValues.minOrNull() ?: return 1f
        val maxY = yValues.maxOrNull() ?: return 1f
        return abs(maxY - minY)
    }

    private fun sanitizePrefix(prefix: String): String {
        val trimmed = prefix.trim()
        if (trimmed.isEmpty()) {
            return "/tracking/pose"
        }

        val startsWithSlash = if (trimmed.startsWith('/')) trimmed else "/$trimmed"
        return startsWithSlash.trimEnd('/')
    }
}

private data class Vec3(
    val x: Float,
    val y: Float,
    val z: Float
)
