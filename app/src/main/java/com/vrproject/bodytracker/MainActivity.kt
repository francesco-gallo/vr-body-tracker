package com.vrproject.bodytracker

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doOnTextChanged
import com.vrproject.bodytracker.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
class MainActivity : AppCompatActivity() {
    private val logTag = "BodyTrackerDebug"
    private lateinit var binding: ActivityMainBinding

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var poseTracker: PoseTracker
    private val poseProcessor = PoseProcessor()
    private val oscSender = OscSender()

    @Volatile private var streamEnabled = false
    private var lastStatusUpdateMs = 0L
    private var lastSentAtMs = 0L
    private var lastDebugUpdateMs = 0L
    private var lastFpsWindowStartMs = 0L
    private var framesSentWindow = 0
    private var sendFps = 0f
    private var sendJob: Job? = null

    // Cached configuration values
    @Volatile private var cachedHost = ""
    @Volatile private var cachedPort: Int? = null
    @Volatile private var cachedPrefix = "/tracking/pose"
    @Volatile private var cachedHeightMeters = 1.70f
    @Volatile private var cachedFps = 60

    private var useFrontCamera = false
    private var invertCameraView = false
    private var vrchatModeEnabled = true
    private var pendingCalibration = false
    private var uiVisible = true
    private lateinit var savedConfig: AppConfig

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                binding.statusText.text = getString(R.string.status_camera_permission_needed)
                Toast.makeText(this, getString(R.string.status_camera_permission_needed), Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        savedConfig = AppConfigStore.load(this)

        poseTracker = PoseTracker { frame ->
            val processedFrame = processFrame(frame)
            updateOverlay(processedFrame)

            if (!streamEnabled) {
                maybeUpdateDebug(processedFrame, 0)
                return@PoseTracker
            }

            val host = cachedHost
            val port = cachedPort ?: return@PoseTracker

            if (!canSendNow(processedFrame.timestampMs)) {
                return@PoseTracker
            }

            val messages = PoseOscMapper.toMessages(
                frame = processedFrame,
                estimatedHeightMeters = cachedHeightMeters
            )

            val useBundle = binding.bundleSwitch.isChecked

            appScope.launch(Dispatchers.IO) {
                oscSender.send(host, port, messages, useBundle)
            }

            updateSendFps(processedFrame.timestampMs)
            maybeUpdateStatus(processedFrame)
            maybeUpdateDebug(processedFrame, messages.size)
        }

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
        val cacheUpdateListener = {
            updateCachedValues()
            updateButtonState()
            persistCurrentConfig()
        }

        binding.ipEditText.doOnTextChanged { _, _, _, _ -> cacheUpdateListener() }
        binding.portEditText.doOnTextChanged { _, _, _, _ -> cacheUpdateListener() }
        binding.heightEditText.doOnTextChanged { _, _, _, _ -> cacheUpdateListener() }
        binding.fpsEditText.doOnTextChanged { _, _, _, _ -> cacheUpdateListener() }

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
            appScope.launch(Dispatchers.Main) {
                setUiControlsEnabled(false)

                for (secondsLeft in 5 downTo 1) {
                    binding.statusText.text = "Calibrazione tra $secondsLeft secondi... Mettiti in posizione!"
                    delay(1000L)
                }

                pendingCalibration = true
                binding.statusText.text = getString(R.string.status_calibrate_pending)

                setUiControlsEnabled(true)
            }
        }

        binding.smoothingSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.smoothingLabel.text = getString(R.string.smoothing_label_value, progress)
                if (fromUser) persistCurrentConfig()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.streamButton.setOnClickListener {
            val host = cachedHost
            val port = cachedPort
            if (host.isEmpty() || port == null) {
                binding.statusText.text = getString(R.string.status_invalid_endpoint)
                return@setOnClickListener
            }

            streamEnabled = !streamEnabled
            if (!streamEnabled) {
                poseProcessor.clear()
                binding.debugText.text = getString(R.string.debug_waiting)
            }

            binding.streamButton.text = if (streamEnabled) getString(R.string.stop_stream) else getString(R.string.start_stream)
            binding.statusText.text = if (streamEnabled) getString(R.string.status_streaming, host, port) else getString(R.string.status_idle)
        }

        binding.toggleUiButton.setOnClickListener {
            uiVisible = !uiVisible
            binding.controlPanel.visibility = if (uiVisible) View.VISIBLE else View.GONE
            binding.toggleUiButton.text = if (uiVisible) getString(R.string.hide_ui) else getString(R.string.show_ui)
        }

        binding.resetButton.setOnClickListener {
            val defaultConfig = AppConfigStore.defaultConfig()
            AppConfigStore.clear(this)
            savedConfig = defaultConfig
            populateUiFromConfig(defaultConfig)
            binding.statusText.text = "Settings reset to defaults."
        }

        binding.bundleSwitch.setOnCheckedChangeListener { _, _ -> persistCurrentConfig() }

        binding.toggleUiButton.text = getString(R.string.hide_ui)
        binding.statusText.text = getString(R.string.status_idle)
        updateButtonState()
    }

    private fun setUiControlsEnabled(enabled: Boolean) {
        binding.calibrateButton.isEnabled = enabled
        binding.resetButton.isEnabled = enabled
        binding.ipEditText.isEnabled = enabled
        binding.portEditText.isEnabled = enabled
        binding.heightEditText.isEnabled = enabled
        binding.fpsEditText.isEnabled = enabled
        binding.frontCameraSwitch.isEnabled = enabled
        binding.invertCameraSwitch.isEnabled = enabled
        binding.smoothingSeekBar.isEnabled = enabled
        binding.bundleSwitch.isEnabled = enabled

        if (enabled) {
            updateButtonState()
        } else {
            binding.streamButton.isEnabled = false
        }
    }

    private fun updateCachedValues() {
        cachedHost = binding.ipEditText.text?.toString()?.trim().orEmpty()
        cachedPort = parsePort()
        cachedHeightMeters = parseHeightMeters()
        cachedFps = parseFps() ?: 60
    }

    private fun applyInsets() {
        val initialPanelBottomPadding = binding.controlPanel.paddingBottom
        val initialPanelLeftPadding = binding.controlPanel.paddingLeft
        val initialPanelRightPadding = binding.controlPanel.paddingRight
        val initialToggleBottomMargin = (binding.toggleUiButton.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())

            binding.controlPanel.setPadding(
                initialPanelLeftPadding + bars.left,
                binding.controlPanel.paddingTop,
                initialPanelRightPadding + bars.right,
                initialPanelBottomPadding + bars.bottom
            )

            (binding.toggleUiButton.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.let { params ->
                params.bottomMargin = initialToggleBottomMargin + bars.bottom
                binding.toggleUiButton.layoutParams = params
            }

            WindowInsetsCompat.CONSUMED
        }
    }

    private fun updateButtonState() {
        binding.streamButton.isEnabled = cachedHost.isNotEmpty() && cachedPort != null
    }

    private fun populateUiFromConfig(config: AppConfig) {
        binding.ipEditText.setText(config.ip)
        binding.portEditText.setText(config.port.toString())
        vrchatModeEnabled = true
        binding.heightEditText.setText(config.heightMeters.toString())
        useFrontCamera = config.frontCamera
        binding.frontCameraSwitch.isChecked = config.frontCamera
        binding.fpsEditText.setText(config.fps.toString())
        binding.smoothingSeekBar.progress = config.smoothing
        binding.bundleSwitch.isChecked = config.bundle
        binding.smoothingLabel.text = getString(R.string.smoothing_label_value, config.smoothing)

        updateCachedValues()
        updateButtonState()
        if (cameraProvider != null) {
            bindUseCases()
        }
    }

    private fun persistCurrentConfig() {
        val config = AppConfig(
            ip = cachedHost.ifEmpty { "192.168.1.10" },
            port = cachedPort ?: 9000,
            heightMeters = cachedHeightMeters,
            frontCamera = binding.frontCameraSwitch.isChecked,
            fps = cachedFps,
            smoothing = binding.smoothingSeekBar.progress,
            bundle = binding.bundleSwitch.isChecked
        )
        savedConfig = config
        AppConfigStore.save(this, config)
    }

    private fun updateLastBuildTimestamp() {
        val timestamp = BuildConfig.BUILD_TIMESTAMP
        val formatted = if (timestamp > 0L) {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timestamp))
        } else {
            "unknown"
        }
        binding.lastBuildText.text = getString(R.string.last_build_label, formatted)
    }

    private fun parsePort(): Int? {
        val value = binding.portEditText.text?.toString()?.trim()?.toIntOrNull() ?: return null
        return if (value in 1..65535) value else null
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
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

        // Configurazione moderna per la risoluzione
        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(480, 640),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(resolutionSelector)
            .build()
            .also {
                it.setAnalyzer(cameraExecutor, poseTracker)
            }

        provider.unbindAll()
        val selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        provider.bindToLifecycle(this, selector, preview, analysis)
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
            invertX = false,
            invertY = false,
            invertZ = false
        )
        return poseProcessor.process(frame, config)
    }

    private fun canSendNow(nowMs: Long): Boolean {
        val fps = cachedFps.coerceIn(1, 120)
        val minInterval = 1000L / fps
        if (nowMs - lastSentAtMs < minInterval) {
            return false
        }
        lastSentAtMs = nowMs
        return true
    }

    private fun parseFps(): Int? {
        val value = binding.fpsEditText.text?.toString()?.trim()?.toIntOrNull() ?: return null
        return if (value in 1..120) value else null
    }

    private fun parseHeightMeters(): Float {
        val value = binding.heightEditText.text?.toString()?.trim()?.toFloatOrNull() ?: return 1.70f
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
        // Debug text nascosto
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
            "left_shoulder", "right_shoulder",
            "left_elbow", "right_elbow",
            "left_wrist", "right_wrist",
            "left_hip", "right_hip",
            "left_knee", "right_knee",
            "left_ankle", "right_ankle"
        )
        val visible = required.count { name ->
            val joint = frame.joints.firstOrNull { it.name == name }
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
        if (now - lastStatusUpdateMs < 300L) return

        lastStatusUpdateMs = now
        val coverage = assessBodyCoverage(frame)
        runOnUiThread {
            val host = cachedHost
            val port = cachedPort ?: 0
            val cameraName = if (useFrontCamera) getString(R.string.camera_front) else getString(R.string.camera_back)
            val mode = if (vrchatModeEnabled) getString(R.string.mode_vrchat_trackers) else getString(R.string.mode_raw_landmarks)

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