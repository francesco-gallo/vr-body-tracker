package com.vrproject.bodyosc

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.SeekBar
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.graphics.Insets
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doOnTextChanged
import com.vrproject.bodyosc.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val logTag = "BodyOscDebug"
    private lateinit var binding: ActivityMainBinding

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var poseTracker: PoseTracker
    private val poseProcessor = PoseProcessor()
    private val oscSender = OscSender()

    private var streamEnabled = false
    private var lastStatusUpdateMs = 0L
    private var lastSentAtMs = 0L
    private var lastDebugUpdateMs = 0L
    private var lastFpsWindowStartMs = 0L
    private var framesSentWindow = 0
    private var sendFps = 0f
    private var sendJob: Job? = null
    private var useFrontCamera = false
    private var invertCameraView = false
    private var vrchatModeEnabled = true
    private var pendingCalibration = false
    private var uiVisible = true
    private var endpointReady = false
    private var lastEndpointCheckMs = 0L
    private lateinit var savedConfig: AppConfig

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                binding.statusText.text = getString(R.string.status_camera_permission_needed)
                Toast.makeText(this, getString(R.string.status_camera_permission_needed), Toast.LENGTH_LONG)
                    .show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        poseTracker = PoseTracker { frame ->
            val processedFrame = processFrame(frame)
            updateOverlay(processedFrame)

            if (!streamEnabled) {
                maybeUpdateDebug(processedFrame, 0)
                return@PoseTracker
            }

            val host = binding.ipEditText.text?.toString()?.trim().orEmpty()
            val port = parsePort()
            val prefix = binding.prefixEditText.text?.toString()?.trim().orEmpty()
            if (host.isEmpty() || port == null) {
                return@PoseTracker
            }

            if (!endpointReady || System.currentTimeMillis() - lastEndpointCheckMs > 5000L) {
                appScope.launch {
                    val reachable = oscSender.checkEndpoint(host, port)
                    endpointReady = reachable
                    lastEndpointCheckMs = System.currentTimeMillis()
                    if (!reachable) {
                        runOnUiThread {
                            binding.statusText.text = "Endpoint unreachable: $host:$port"
                        }
                    }
                }
                if (!endpointReady) {
                    return@PoseTracker
                }
            }

            if (!canSendNow(processedFrame.timestampMs)) {
                return@PoseTracker
            }

            val mode = if (vrchatModeEnabled) {
                OscOutputMode.VRCHAT_TRACKERS
            } else {
                OscOutputMode.RAW_LANDMARKS
            }
            val heightMeters = parseHeightMeters()
            val messages = PoseOscMapper.toMessages(
                frame = processedFrame,
                prefix = prefix,
                mode = mode,
                includeHeadAlignment = false,
                estimatedHeightMeters = heightMeters,
                enabledBodyParts = currentBodyPartSelection()
            )
            val useBundle = binding.bundleSwitch.isChecked
            sendJob?.cancel()
            sendJob = appScope.launch {
                oscSender.send(host, port, messages, useBundle)
            }

            updateSendFps(processedFrame.timestampMs)

            maybeUpdateStatus(processedFrame)
            maybeUpdateDebug(processedFrame, messages.size)
        }

        savedConfig = AppConfigStore.load(this)
        setupUi()
        applyInsets()
        populateUiFromConfig(savedConfig)
        updateLastBuildTimestamp()

        if (hasCameraPermission()) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        poseTracker.close()
        oscSender.close()
        sendJob?.cancel()
        poseProcessor.clear()
    }

    private fun setupUi() {
        binding.portEditText.doOnTextChanged { _, _, _, _ ->
            updateButtonState()
        }
        binding.ipEditText.doOnTextChanged { _, _, _, _ ->
            updateButtonState()
        }

        binding.frontCameraSwitch.setOnCheckedChangeListener { _, checked ->
            useFrontCamera = checked
            bindUseCases()
            persistCurrentConfig()
        }

        binding.invertCameraSwitch.setOnCheckedChangeListener { _, checked ->
            invertCameraView = checked
            binding.jointOverlay.setFrameJoints(
                items = binding.jointOverlay.currentFrameJoints,
                shouldMirrorX = invertCameraView || useFrontCamera,
                sourceWidth = binding.jointOverlay.currentSourceWidth,
                sourceHeight = binding.jointOverlay.currentSourceHeight
            )
            persistCurrentConfig()
        }

        binding.calibrateButton.setOnClickListener {
            pendingCalibration = true
            binding.statusText.text = getString(R.string.status_calibrate_pending)
        }

        binding.smoothingSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.smoothingLabel.text = getString(R.string.smoothing_label_value, progress)
                if (fromUser) {
                    persistCurrentConfig()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                // no-op
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // no-op
            }
        })

        binding.streamButton.setOnClickListener {
            val host = binding.ipEditText.text?.toString()?.trim().orEmpty()
            val port = parsePort()
            if (host.isEmpty() || port == null) {
                binding.statusText.text = getString(R.string.status_invalid_endpoint)
                return@setOnClickListener
            }

            appScope.launch {
                val isReachable = oscSender.checkEndpoint(host, port)
                endpointReady = isReachable
                lastEndpointCheckMs = System.currentTimeMillis()
                runOnUiThread {
                    if (!isReachable) {
                        streamEnabled = false
                        binding.streamButton.text = getString(R.string.start_stream)
                        binding.statusText.text = "Endpoint unreachable: $host:$port"
                        return@runOnUiThread
                    }

                    streamEnabled = !streamEnabled
                    if (!streamEnabled) {
                        poseProcessor.clear()
                        binding.debugText.text = getString(R.string.debug_waiting)
                    }
                    binding.streamButton.text =
                        if (streamEnabled) getString(R.string.stop_stream) else getString(R.string.start_stream)
                    binding.statusText.text =
                        if (streamEnabled) getString(R.string.status_streaming, host, port) else getString(R.string.status_idle)
                }
            }
        }

        binding.toggleUiButton.setOnClickListener {
            uiVisible = !uiVisible
            binding.controlPanel.visibility = if (uiVisible) android.view.View.VISIBLE else android.view.View.GONE
            binding.toggleUiButton.text = if (uiVisible) {
                getString(R.string.hide_ui)
            } else {
                getString(R.string.show_ui)
            }
        }

        binding.resetButton.setOnClickListener {
            val defaultConfig = AppConfigStore.defaultConfig()
            AppConfigStore.clear(this)
            savedConfig = defaultConfig
            populateUiFromConfig(defaultConfig)
            binding.statusText.text = "Settings reset to defaults."
        }

        binding.ipEditText.doOnTextChanged { _, _, _, _ -> persistCurrentConfig() }
        binding.portEditText.doOnTextChanged { _, _, _, _ -> persistCurrentConfig() }
        binding.prefixEditText.doOnTextChanged { _, _, _, _ -> persistCurrentConfig() }
        binding.heightEditText.doOnTextChanged { _, _, _, _ -> persistCurrentConfig() }
        binding.fpsEditText.doOnTextChanged { _, _, _, _ -> persistCurrentConfig() }
        binding.bundleSwitch.setOnCheckedChangeListener { _, _ -> persistCurrentConfig() }
        binding.invertXCheck.setOnCheckedChangeListener { _, _ -> persistCurrentConfig() }
        binding.invertYCheck.setOnCheckedChangeListener { _, _ -> persistCurrentConfig() }
        binding.invertZCheck.setOnCheckedChangeListener { _, _ -> persistCurrentConfig() }
        binding.bodyHeadToggle.setOnCheckedChangeListener { _, _ -> persistCurrentConfig() }
        binding.bodyTorsoToggle.setOnCheckedChangeListener { _, _ -> persistCurrentConfig() }
        binding.bodyLeftArmToggle.setOnCheckedChangeListener { _, _ -> persistCurrentConfig() }
        binding.bodyRightArmToggle.setOnCheckedChangeListener { _, _ -> persistCurrentConfig() }
        binding.bodyLeftLegToggle.setOnCheckedChangeListener { _, _ -> persistCurrentConfig() }
        binding.bodyRightLegToggle.setOnCheckedChangeListener { _, _ -> persistCurrentConfig() }

        binding.toggleUiButton.text = getString(R.string.hide_ui)
        binding.smoothingLabel.text = getString(R.string.smoothing_label_value, binding.smoothingSeekBar.progress)
        binding.statusText.text = getString(R.string.status_idle)
        updateButtonState()
    }

    private fun applyInsets() {
        val initialPanelBottomPadding = binding.controlPanel.paddingBottom
        val initialPanelLeftPadding = binding.controlPanel.paddingLeft
        val initialPanelRightPadding = binding.controlPanel.paddingRight
        val initialToggleTopPadding = binding.toggleUiButton.paddingTop
        val initialToggleRightPadding = binding.toggleUiButton.paddingRight

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars: Insets = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            binding.controlPanel.setPadding(
                initialPanelLeftPadding + bars.left,
                binding.controlPanel.paddingTop,
                initialPanelRightPadding + bars.right,
                initialPanelBottomPadding + bars.bottom
            )

            binding.toggleUiButton.setPadding(
                binding.toggleUiButton.paddingLeft,
                initialToggleTopPadding + bars.top,
                initialToggleRightPadding + bars.right,
                binding.toggleUiButton.paddingBottom
            )

            WindowInsetsCompat.CONSUMED
        }
    }

    private fun updateButtonState() {
        val host = binding.ipEditText.text?.toString()?.trim().orEmpty()
        val validPort = parsePort() != null
        binding.streamButton.isEnabled = host.isNotEmpty() && validPort
    }

    private fun populateUiFromConfig(config: AppConfig) {
        binding.ipEditText.setText(config.ip)
        binding.portEditText.setText(config.port.toString())
        binding.prefixEditText.setText(config.prefix)
        vrchatModeEnabled = config.vrchatTrackers
        binding.heightEditText.setText(config.heightMeters.toString())
        useFrontCamera = config.frontCamera
        binding.frontCameraSwitch.isChecked = config.frontCamera
        invertCameraView = config.invertX || config.invertY
        binding.invertCameraSwitch.isChecked = invertCameraView
        binding.fpsEditText.setText(config.fps.toString())
        binding.smoothingSeekBar.progress = config.smoothing
        binding.bundleSwitch.isChecked = config.bundle
        binding.invertXCheck.isChecked = config.invertX
        binding.invertYCheck.isChecked = config.invertY
        binding.invertZCheck.isChecked = config.invertZ
        binding.bodyHeadToggle.isChecked = config.bodyParts.head
        binding.bodyTorsoToggle.isChecked = config.bodyParts.torso
        binding.bodyLeftArmToggle.isChecked = config.bodyParts.leftArm
        binding.bodyRightArmToggle.isChecked = config.bodyParts.rightArm
        binding.bodyLeftLegToggle.isChecked = config.bodyParts.leftLeg
        binding.bodyRightLegToggle.isChecked = config.bodyParts.rightLeg
        binding.smoothingLabel.text = getString(R.string.smoothing_label_value, config.smoothing)
        updateButtonState()
        if (cameraProvider != null) {
            bindUseCases()
        }
    }

    private fun persistCurrentConfig() {
        val prefixText = binding.prefixEditText.text?.toString()?.trim().orEmpty()
        val config = AppConfig(
            ip = binding.ipEditText.text?.toString()?.trim().orEmpty().ifEmpty { "192.168.1.10" },
            port = parsePort() ?: 9000,
            prefix = if (prefixText.isEmpty()) "/tracking/pose" else prefixText,
            vrchatTrackers = vrchatModeEnabled,
            heightMeters = parseHeightMeters(),
            frontCamera = binding.frontCameraSwitch.isChecked,
            fps = parseFps() ?: 20,
            smoothing = binding.smoothingSeekBar.progress,
            bundle = binding.bundleSwitch.isChecked,
            invertX = binding.invertXCheck.isChecked,
            invertY = binding.invertYCheck.isChecked,
            invertZ = binding.invertZCheck.isChecked,
            bodyParts = currentBodyPartSelection()
        )
        savedConfig = config
        AppConfigStore.save(this, config)
    }

    private fun currentBodyPartSelection(): BodyPartSelection = BodyPartSelection(
        head = binding.bodyHeadToggle.isChecked,
        torso = binding.bodyTorsoToggle.isChecked,
        leftArm = binding.bodyLeftArmToggle.isChecked,
        rightArm = binding.bodyRightArmToggle.isChecked,
        leftLeg = binding.bodyLeftLegToggle.isChecked,
        rightLeg = binding.bodyRightLegToggle.isChecked
    )

    private fun updateLastBuildTimestamp() {
        val timestamp = BuildConfig.BUILD_TIMESTAMP
        val formatted = if (timestamp > 0L) {
            val date = Date(timestamp)
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(date)
        } else {
            "unknown"
        }
        binding.lastBuildText.text = getString(R.string.last_build_label, formatted)
    }

    private fun parsePort(): Int? {
        val raw = binding.portEditText.text?.toString()?.trim().orEmpty()
        val value = raw.toIntOrNull() ?: return null
        return if (value in 1..65535) value else null
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener(
            {
                cameraProvider = providerFuture.get()
                bindUseCases()
            },
            ContextCompat.getMainExecutor(this)
        )
    }

    private fun bindUseCases() {
        val provider = cameraProvider ?: return

        val preview = Preview.Builder().build().also {
            it.surfaceProvider = binding.previewView.surfaceProvider
        }

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(cameraExecutor, poseTracker)
            }

        provider.unbindAll()
        val selector = if (useFrontCamera) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
        provider.bindToLifecycle(
            this,
            selector,
            preview,
            analysis
        )
    }

    private fun processFrame(frame: PoseFrame): PoseFrame {
        if (pendingCalibration) {
            poseProcessor.calibrate(frame)
            pendingCalibration = false
            runOnUiThread {
                binding.statusText.text = getString(R.string.status_calibrated)
            }
        }

        val alpha = (binding.smoothingSeekBar.progress / 100f).coerceIn(0f, 0.95f)
        val config = StreamConfig(
            smoothingAlpha = alpha,
            invertX = binding.invertXCheck.isChecked,
            invertY = binding.invertYCheck.isChecked,
            invertZ = binding.invertZCheck.isChecked
        )
        return poseProcessor.process(frame, config)
    }

    private fun canSendNow(nowMs: Long): Boolean {
        val fps = parseFps()
        if (fps == null) {
            return true
        }

        val minInterval = 1000L / fps
        if (nowMs - lastSentAtMs < minInterval) {
            return false
        }
        lastSentAtMs = nowMs
        return true
    }

    private fun parseFps(): Int? {
        val raw = binding.fpsEditText.text?.toString()?.trim().orEmpty()
        val value = raw.toIntOrNull() ?: return null
        return if (value in 1..120) value else null
    }

    private fun parseHeightMeters(): Float {
        val raw = binding.heightEditText.text?.toString()?.trim().orEmpty()
        val value = raw.toFloatOrNull() ?: return 1.70f
        return value.coerceIn(1.0f, 2.5f)
    }

    private fun updateSendFps(nowMs: Long) {
        if (lastFpsWindowStartMs == 0L) {
            lastFpsWindowStartMs = nowMs
        }
        framesSentWindow += 1

        val elapsed = nowMs - lastFpsWindowStartMs
        if (elapsed >= 1000L) {
            sendFps = (framesSentWindow * 1000f) / elapsed.toFloat()
            framesSentWindow = 0
            lastFpsWindowStartMs = nowMs
        }
    }

    private fun maybeUpdateDebug(frame: PoseFrame, messageCount: Int) {
        val now = System.currentTimeMillis()
        if (now - lastDebugUpdateMs < 500L) {
            return
        }
        lastDebugUpdateMs = now

        val chest = averageJoint(frame, "left_shoulder", "right_shoulder")
        val hip = averageJoint(frame, "left_hip", "right_hip")
        val leftAnkle = findJoint(frame, "left_ankle")
        val rightAnkle = findJoint(frame, "right_ankle")
        val coverage = assessBodyCoverage(frame)
val mode = if (vrchatModeEnabled) "vrchat" else "raw"

        val debug = buildString {
            append("mode=").append(mode)
            append(" msgs=").append(messageCount)
            append(" fps=").append(String.format("%.1f", sendFps)).append('\n')
            append("joints=").append(frame.joints.size)
            append(" smooth=").append(binding.smoothingSeekBar.progress).append('%')
            append(" bundle=").append(if (binding.bundleSwitch.isChecked) "on" else "off")
            append(" body=").append(if (coverage.complete) "full" else "partial").append('\n')
            append("coverage=").append(coverage.visible).append('/').append(coverage.required).append('\n')
            append("chest").append(' ').append(formatJoint(chest)).append('\n')
            append("hip  ").append(formatJoint(hip)).append('\n')
            append("ankL ").append(formatJoint(leftAnkle)).append('\n')
            append("ankR ").append(formatJoint(rightAnkle))
        }

        runOnUiThread {
            binding.debugText.text = debug
        }
        Log.d(logTag, debug)
    }

    private fun findJoint(frame: PoseFrame, name: String): JointSample? {
        return frame.joints.firstOrNull { it.name == name }
    }

    private fun averageJoint(frame: PoseFrame, leftName: String, rightName: String): JointSample? {
        val left = findJoint(frame, leftName)
        val right = findJoint(frame, rightName)
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
            name = "mid",
            x = (left.x + right.x) * 0.5f,
            y = (left.y + right.y) * 0.5f,
            z = (left.z + right.z) * 0.5f,
            visibility = (left.visibility + right.visibility) * 0.5f
        )
    }

    private fun formatJoint(joint: JointSample?): String {
        if (joint == null) {
            return "n/a"
        }

        return String.format(
            "x=%.2f y=%.2f z=%.2f v=%.2f",
            joint.x,
            joint.y,
            joint.z,
            joint.visibility
        )
    }

    private fun updateOverlay(frame: PoseFrame) {
        runOnUiThread {
            binding.jointOverlay.setFrameJoints(
                items = frame.joints,
                shouldMirrorX = invertCameraView || useFrontCamera,
                sourceWidth = frame.imageWidth,
                sourceHeight = frame.imageHeight
            )
        }
    }

    private fun assessBodyCoverage(frame: PoseFrame): BodyCoverage {
        val required = listOf(
            "left_shoulder",
            "right_shoulder",
            "left_elbow",
            "right_elbow",
            "left_wrist",
            "right_wrist",
            "left_hip",
            "right_hip",
            "left_knee",
            "right_knee",
            "left_ankle",
            "right_ankle"
        )
        val visible = required.count { name ->
            val joint = findJoint(frame, name)
            joint != null && joint.visibility > 0.25f
        }
        return BodyCoverage(required.size, visible, visible >= required.size / 2)
    }

    private data class BodyCoverage(
        val required: Int,
        val visible: Int,
        val complete: Boolean
    )

    private fun maybeUpdateStatus(frame: PoseFrame) {
        val now = System.currentTimeMillis()
        if (now - lastStatusUpdateMs < 300L) {
            return
        }

        lastStatusUpdateMs = now
        val coverage = assessBodyCoverage(frame)
        runOnUiThread {
            val host = binding.ipEditText.text?.toString()?.trim().orEmpty()
            val port = parsePort() ?: 0
            val cameraName = if (useFrontCamera) getString(R.string.camera_front) else getString(R.string.camera_back)
            val mode = if (vrchatModeEnabled) {
                getString(R.string.mode_vrchat_trackers)
            } else {
                getString(R.string.mode_raw_landmarks)
            }
            val statusText = if (coverage.complete) {
                getString(
                    R.string.status_streaming_joints,
                    host,
                    port,
                    frame.joints.size,
                    cameraName,
                    mode
                )
            } else {
                "Partial body detected: ${coverage.visible}/${coverage.required} major joints visible"
            }
            binding.statusText.text = statusText
        }
    }
}
