package com.vrproject.bodytracker

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

object PoseOscMapper {

    private const val VISIBILITY_THRESHOLD = 0.5f

    private var lastTorsoRot = Vec3(0f, 0f, 0f)
    private val lastRotations = HashMap<String, Vec3>()
    private val reusableMessageList = ArrayList<OscMessageData>(18)

    // Baseline root reference captured during calibration
    private var rootCalibrationOffset: Vec3? = null

    fun calibrateRoot(frame: PoseFrame) {
        val joints = frame.joints
        val leftHip = findJoint(joints, "left_hip")
        val rightHip = findJoint(joints, "right_hip")
        val hip = averageJoint("hip_mid", leftHip, rightHip)
        val leftShoulder = findJoint(joints, "left_shoulder")
        val rightShoulder = findJoint(joints, "right_shoulder")
        val chest = averageJoint("chest_mid", leftShoulder, rightShoulder)

        val anchor = hip ?: chest
        if (anchor != null) {
            rootCalibrationOffset = Vec3(anchor.x, anchor.y, anchor.z)
        }
    }

    fun resetCalibration() {
        rootCalibrationOffset = null
    }

    fun toMessages(
        frame: PoseFrame,
        estimatedHeightMeters: Float,
        isFrontCamera: Boolean,
        config: AppConfig
    ): List<OscMessageData> {
        reusableMessageList.clear()
        return toVrchatTrackerMessages(
            frame = frame,
            estimatedHeightMeters = estimatedHeightMeters,
            isFrontCamera = isFrontCamera,
            config = config
        )
    }

    private fun toVrchatTrackerMessages(
        frame: PoseFrame,
        estimatedHeightMeters: Float,
        isFrontCamera: Boolean,
        config: AppConfig
    ): List<OscMessageData> {
        val joints = frame.joints

        val leftHip = findJoint(joints, "left_hip")
        val rightHip = findJoint(joints, "right_hip")
        val leftShoulder = findJoint(joints, "left_shoulder")
        val rightShoulder = findJoint(joints, "right_shoulder")

        val head = findJoint(joints, "head")
        val hip = averageJoint("hip_mid", leftHip, rightHip)
        val chest = averageJoint("chest_mid", leftShoulder, rightShoulder)
        val leftFoot = findJoint(joints, "left_ankle")
        val rightFoot = findJoint(joints, "right_ankle")
        val leftKnee = findJoint(joints, "left_knee")
        val rightKnee = findJoint(joints, "right_knee")
        val leftElbow = findJoint(joints, "left_elbow")
        val rightElbow = findJoint(joints, "right_elbow")

        val currentRoot = hip ?: chest ?: return emptyList()

        // Use calibrated root anchor if available, otherwise fallback to current frame root
        val rootAnchor = if (rootCalibrationOffset != null) {
            JointSample(
                name = "calibrated_root",
                x = rootCalibrationOffset!!.x,
                y = rootCalibrationOffset!!.y,
                z = rootCalibrationOffset!!.z,
                visibility = 1.0f
            )
        } else {
            currentRoot
        }

        val observedHeight = estimateObservedHeight(joints)
        val safeObserved = max(0.2f, observedHeight)
        val safeTargetHeight = estimatedHeightMeters.coerceIn(1.0f, 2.5f)
        val metersPerNorm = safeTargetHeight / safeObserved

        val xMultiplier = if (isFrontCamera) -1f else 1f

        val torsoRot = calculateTorsoOrientation(leftShoulder, rightShoulder, leftHip, rightHip, isFrontCamera)
        val headRot = torsoRot

        val leftKneeRot = rotationFromDirection("left_knee", hip, leftKnee, defaultRot = torsoRot, isFrontCamera = isFrontCamera)
        val rightKneeRot = rotationFromDirection("right_knee", hip, rightKnee, defaultRot = torsoRot, isFrontCamera = isFrontCamera)

        val leftFootRot = leftKneeRot
        val rightFootRot = rightKneeRot

        val leftElbowRot = rotationFromDirection("left_elbow", leftShoulder, leftElbow, defaultRot = torsoRot, isFrontCamera = isFrontCamera)
        val rightElbowRot = rotationFromDirection("right_elbow", rightShoulder, rightElbow, defaultRot = torsoRot, isFrontCamera = isFrontCamera)

        val gz = config.globalZOffset

        // 1: Hip
        appendIndexedTracker(reusableMessageList, 1, hip, torsoRot, rootAnchor, metersPerNorm, xMultiplier, config.hipOffset.x, config.hipOffset.y, gz)
        // 2: Chest
        appendIndexedTracker(reusableMessageList, 2, chest, torsoRot, rootAnchor, metersPerNorm, xMultiplier, config.chestOffset.x, config.chestOffset.y, gz)

        // 3: Left Foot & 4: Right Foot
        appendIndexedTracker(reusableMessageList, 3, leftFoot, leftFootRot, rootAnchor, metersPerNorm, xMultiplier, -config.feetOffset.x, config.feetOffset.y, gz)
        appendIndexedTracker(reusableMessageList, 4, rightFoot, rightFootRot, rootAnchor, metersPerNorm, xMultiplier, config.feetOffset.x, config.feetOffset.y, gz)

        // 5: Left Knee & 6: Right Knee
        appendIndexedTracker(reusableMessageList, 5, leftKnee, leftKneeRot, rootAnchor, metersPerNorm, xMultiplier, -config.kneesOffset.x, config.kneesOffset.y, gz)
        appendIndexedTracker(reusableMessageList, 6, rightKnee, rightKneeRot, rootAnchor, metersPerNorm, xMultiplier, config.kneesOffset.x, config.kneesOffset.y, gz)

        // 7: Left Elbow & 8: Right Elbow
        appendIndexedTracker(reusableMessageList, 7, leftElbow, leftElbowRot, rootAnchor, metersPerNorm, xMultiplier, -config.elbowsOffset.x, config.elbowsOffset.y, gz)
        appendIndexedTracker(reusableMessageList, 8, rightElbow, rightElbowRot, rootAnchor, metersPerNorm, xMultiplier, config.elbowsOffset.x, config.elbowsOffset.y, gz)

        // 9: Head
        appendIndexedTracker(reusableMessageList, 9, head, headRot, rootAnchor, metersPerNorm, xMultiplier, config.headOffset.x, config.headOffset.y, gz)

        return reusableMessageList
    }

    private fun findJoint(joints: List<JointSample>, name: String): JointSample? {
        for (i in joints.indices) {
            val j = joints[i]
            if (j.name == name && j.visibility >= VISIBILITY_THRESHOLD) {
                return j
            }
        }
        return null
    }

    private fun appendIndexedTracker(
        out: MutableList<OscMessageData>,
        trackerIndex: Int,
        joint: JointSample?,
        rotationEuler: Vec3,
        origin: JointSample,
        metersPerNorm: Float,
        xMultiplier: Float,
        offsetX: Float,
        offsetY: Float,
        offsetZ: Float
    ) {
        if (joint == null || joint.visibility < VISIBILITY_THRESHOLD) return

        val p = toTrackingVector(joint, origin, metersPerNorm, xMultiplier, offsetX, offsetY, offsetZ)
        out.add(
            OscMessageData(
                address = "/tracking/trackers/$trackerIndex/position",
                args = listOf(p.x, p.y, p.z)
            )
        )
        out.add(
            OscMessageData(
                address = "/tracking/trackers/$trackerIndex/rotation",
                args = listOf(rotationEuler.x, rotationEuler.y, rotationEuler.z)
            )
        )
    }

    private fun calculateTorsoOrientation(
        leftShoulder: JointSample?,
        rightShoulder: JointSample?,
        leftHip: JointSample?,
        rightHip: JointSample?,
        isFrontCamera: Boolean
    ): Vec3 {
        if (leftShoulder == null || rightShoulder == null || leftHip == null || rightHip == null) {
            return lastTorsoRot
        }

        var rx = rightShoulder.x - leftShoulder.x
        var ry = -(rightShoulder.y - leftShoulder.y)
        var rz = rightShoulder.z - leftShoulder.z

        val hipMidX = (leftHip.x + rightHip.x) * 0.5f
        val hipMidY = (leftHip.y + rightHip.y) * 0.5f
        val hipMidZ = (leftHip.z + rightHip.z) * 0.5f

        val shoulderMidX = (leftShoulder.x + rightShoulder.x) * 0.5f
        val shoulderMidY = (leftShoulder.y + rightShoulder.y) * 0.5f
        val shoulderMidZ = (leftShoulder.z + rightShoulder.z) * 0.5f

        var ux = shoulderMidX - hipMidX
        var uy = -(shoulderMidY - hipMidY)
        var uz = shoulderMidZ - hipMidZ

        val uLen = sqrt(ux * ux + uy * uy + uz * uz)
        if (uLen < 0.001f) return lastTorsoRot
        ux /= uLen; uy /= uLen; uz /= uLen

        val rLen = sqrt(rx * rx + ry * ry + rz * rz)
        if (rLen < 0.001f) return lastTorsoRot
        rx /= rLen; ry /= rLen; rz /= rLen

        var fx = uy * rz - uz * ry
        var fy = uz * rx - ux * rz
        var fz = ux * ry - uy * rx

        val fLen = sqrt(fx * fx + fy * fy + fz * fz)
        if (fLen < 0.001f) return lastTorsoRot
        fx /= fLen; fy /= fLen; fz /= fLen

        val rawPitch = radiansToDegrees(asin((-fy).coerceIn(-1f, 1f)))
        var rawYaw = radiansToDegrees(atan2(fx, fz))
        var rawRoll = radiansToDegrees(atan2(rx, ry))

        if (isFrontCamera) {
            rawYaw = -rawYaw
            rawRoll = -rawRoll
        }

        val smoothedRot = Vec3(
            x = lerpAngle(lastTorsoRot.x, rawPitch, 0.25f),
            y = lerpAngle(lastTorsoRot.y, rawYaw, 0.25f),
            z = lerpAngle(lastTorsoRot.z, rawRoll, 0.25f)
        )
        lastTorsoRot = smoothedRot
        return smoothedRot
    }

    private fun rotationFromDirection(
        jointName: String,
        start: JointSample?,
        end: JointSample?,
        defaultRot: Vec3,
        isFrontCamera: Boolean
    ): Vec3 {
        val lastRot = lastRotations[jointName] ?: defaultRot

        if (start == null || end == null || start.visibility < VISIBILITY_THRESHOLD || end.visibility < VISIBILITY_THRESHOLD) {
            return lastRot
        }

        val dx = end.x - start.x
        val dy = -(end.y - start.y)
        val dz = (end.z - start.z) / 1000f

        val horizontal = sqrt(dx * dx + dz * dz)
        if (horizontal < 0.001f && abs(dy) < 0.001f) {
            return lastRot
        }

        var rawYaw = radiansToDegrees(atan2(dx, dz))
        val rawPitch = radiansToDegrees(atan2(dy, horizontal))

        if (isFrontCamera) {
            rawYaw = -rawYaw
        }

        val smoothedVec = Vec3(
            x = lerpAngle(lastRot.x, rawPitch, 0.3f),
            y = lerpAngle(lastRot.y, rawYaw, 0.3f),
            z = lerpAngle(lastRot.z, defaultRot.z, 0.3f)
        )
        lastRotations[jointName] = smoothedVec

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
        xMultiplier: Float,
        offsetX: Float,
        offsetY: Float,
        offsetZ: Float
    ): Vec3 {
        val x = ((joint.x - origin.x) * metersPerNorm * xMultiplier) + offsetX
        val y = ((origin.y - joint.y) * metersPerNorm) + offsetY
        val deltaZNorm = (joint.z - origin.z) / 1000f
        val z = (deltaZNorm * metersPerNorm) + offsetZ

        return Vec3(x, y, z)
    }

    private fun averageJoint(name: String, left: JointSample?, right: JointSample?): JointSample? {
        if (left == null || right == null) return null
        if (left.visibility < VISIBILITY_THRESHOLD || right.visibility < VISIBILITY_THRESHOLD) return null

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
            if (j.visibility >= VISIBILITY_THRESHOLD && (
                        j.name == "left_hip" || j.name == "right_hip" ||
                                j.name == "left_shoulder" || j.name == "right_shoulder" ||
                                j.name == "left_ankle" || j.name == "right_ankle")) {
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