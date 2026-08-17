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
    // Cache per mantenere gli ultimi valori di rotazione tra un frame e l'altro (Low-pass filter)
    private var lastTorsoYaw = 0f
    private val lastRotations = HashMap<Int, Vec3>()
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
        val leftFootRot = rotationFromDirection(3, leftKnee, leftFoot, defaultYaw = torsoYaw)
        val rightFootRot = rotationFromDirection(4, rightKnee, rightFoot, defaultYaw = torsoYaw)
        val leftKneeRot = rotationFromDirection(5, hip, leftKnee, defaultYaw = torsoYaw)
        val rightKneeRot = rotationFromDirection(6, hip, rightKnee, defaultYaw = torsoYaw)
        val leftElbowRot = rotationFromDirection(7, leftShoulder, leftElbow, defaultYaw = torsoYaw)
        val rightElbowRot = rotationFromDirection(8, rightShoulder, rightElbow, defaultYaw = torsoYaw)

        if (enabledBodyParts.head) {
            // 1. Invia al Tracker 0 (Standard VRChat OSC Trackers ID 0 = Head)
            appendTracker(messages, 0, head, headRot, origin, metersPerNorm, depthScale)

            // 2. Invia anche all'endpoint dedicato esplicito per la testa
            if (head != null) {
                val headPos = toTrackingVector(head, origin, metersPerNorm, depthScale)

                // Posizione della testa in Metri [X, Y, Z]
                messages.add(
                    OscMessageData(
                        address = "/tracking/trackers/head/position",
                        args = listOf(headPos.x, headPos.y, headPos.z)
                    )
                )
                // Rotazione della testa in Gradi Euleriani [Pitch, Yaw, Roll]
                messages.add(
                    OscMessageData(
                        address = "/tracking/trackers/head/rotation",
                        args = listOf(headRot.x, headRot.y, headRot.z)
                    )
                )
            }
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

        val rawYaw = when {
            shoulderYaw != null && hipYaw != null -> (shoulderYaw + hipYaw) * 0.5f
            shoulderYaw != null -> shoulderYaw
            hipYaw != null -> hipYaw
            else -> lastTorsoYaw
        }

        // Filtro Passa-Basso per evitare scatti bruschi nel Torso
        val smoothedYaw = lerpAngle(lastTorsoYaw, rawYaw, 0.25f)
        lastTorsoYaw = smoothedYaw
        return smoothedYaw
    }

    // Funzione helper per interpolare gli angoli senza problemi di scavalcamento (da -180 a 180)
    private fun lerpAngle(from: Float, to: Float, alpha: Float): Float {
        var diff = (to - from) % 360f
        if (diff < -180f) diff += 360f
        if (diff > 180f) diff -= 360f
        return from + diff * alpha
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

    private fun rotationFromDirection(
        trackerId: Int,
        start: JointSample?,
        end: JointSample?,
        defaultYaw: Float
    ): Vec3 {
        val lastRot = lastRotations[trackerId] ?: Vec3(0f, defaultYaw, 0f)

        // Se la visibilità dell'arto è bassa o manca un joint, mantieni l'ultima rotazione valida
        if (start == null || end == null || start.visibility < 0.4f || end.visibility < 0.4f) {
            return lastRot
        }

        val dx = end.x - start.x
        val dy = -(end.y - start.y)
        val dz = end.z - start.z

        val horizontal = sqrt((dx * dx) + (dz * dz))

        // Evita il "Gimbal Lock" se l'arto è perfettamente verticale
        if (horizontal < 0.01f) {
            return lastRot
        }

        val rawYaw = radiansToDegrees(atan2(dx, dz))
        val rawPitch = radiansToDegrees(atan2(dy, horizontal))

        // Applica lo smoothing sugli angoli (Alfa 0.3 = 30% nuovo dato, 70% vecchio)
        val newYaw = lerpAngle(lastRot.y, rawYaw, 0.3f)
        val newPitch = lerpAngle(lastRot.x, rawPitch, 0.3f)

        val smoothedVec = Vec3(x = newPitch, y = newYaw, z = 0f)
        lastRotations[trackerId] = smoothedVec

        return smoothedVec
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
