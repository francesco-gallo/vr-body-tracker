package com.vrproject.bodytracker

import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doOnTextChanged
import com.vrproject.bodytracker.databinding.ActivityMainBinding
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

class MainActivity : AppCompatActivity(), ConfigProvider, CameraProviderInfo {

    private lateinit var binding: ActivityMainBinding
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var cameraManager: CameraManager
    private lateinit var trackingController: TrackingController

    private var mjpegServer: MjpegServer? = null
    private var lastWebFrameTimeMs = 0L
    private var lastStatusUpdateMs = 0L
    private var uiVisible = true

    // Suppresses listener-driven persistCurrentConfig() calls while the UI is being
    // programmatically populated from the loaded config (setText/setSelection/setChecked
    // all synchronously fire their listeners, which would otherwise read still-default
    // widget values and immediately overwrite the freshly loaded settings on disk).
    private var isPopulatingUi = false

    override var currentConfig: AppConfig = AppConfig()
        private set

    override val selectedCameraItem: CameraItem?
        get() = cameraManager.selectedCameraItem

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        currentConfig = AppConfigStore.load(this)

        isPopulatingUi = true
        setupManagers()
        startMjpegServer()
        setupUi()
        applyInsets()
        populateUiFromConfig(currentConfig)
        updateLastBuildTimestamp()
        // Posted so it runs after any listener callbacks Android itself queues as a
        // side effect of adapter/selection assignment above (e.g. Spinner's initial
        // selection notification), not just the synchronous ones.
        binding.root.post { isPopulatingUi = false }

        cameraManager.checkAndStartPermissions()
    }

    private fun setupManagers() {
        cameraManager = CameraManager(
            activity = this,
            onCameraReady = {
                val providerFuture = ProcessCameraProvider.getInstance(this)
                val provider = providerFuture.get()
                cameraManager.queryAvailableCameras(provider, currentConfig.cameraId)
                updateCameraSpinner()

                cameraManager.selectedCameraItem?.let { selected ->
                    if (currentConfig.cameraId != selected.id) {
                        currentConfig = currentConfig.copy(cameraId = selected.id)
                        persistCurrentConfig()
                    }
                }

                trackingController.bindUseCases(binding.previewView.surfaceProvider, cameraManager.selectedCameraItem)
            },
            onPermissionDenied = {
                binding.statusText.text = getString(R.string.status_camera_permission_needed)
                Toast.makeText(this, getString(R.string.status_camera_permission_needed), Toast.LENGTH_LONG).show()
            }
        )

        trackingController = TrackingController(
            activity = this,
            appScope = appScope,
            configProvider = { currentConfig },
            cameraInfoProvider = { cameraManager.selectedCameraItem },
            onFrameProcessed = { frame -> updateOverlay(frame) },
            onWebFrameReady = { rawBitmap, frame, rotation -> processWebFrame(rawBitmap, frame, rotation) },
            onCameraFpsUpdated = { fps, level -> updateCameraInfoUi(fps, level) },
            onStatusUpdateNeeded = { frame -> maybeUpdateStatus(frame) }
        )

        trackingController.initTracker(
            hasWebClientsProvider = { mjpegServer?.hasClients() == true }
        )
    }

    override fun onResume() {
        super.onResume()
        if (cameraManager.hasCameraPermission()) {
            trackingController.bindUseCases(binding.previewView.surfaceProvider, cameraManager.selectedCameraItem)
        }
    }

    override fun onPause() {
        super.onPause()
        if (trackingController.streamEnabled) {
            trackingController.streamEnabled = false
            binding.streamButton.text = getString(R.string.start_stream)
            binding.statusText.text = getString(R.string.status_idle)
            binding.ipEditText.isEnabled = true
            binding.portEditText.isEnabled = true
        }
        trackingController.cameraProvider?.unbindAll()
    }

    override fun onDestroy() {
        super.onDestroy()
        trackingController.destroy()
        PoseOscMapper.resetCalibration()
        stopMjpegServer()
    }

    private fun processWebFrame(rawBitmap: Bitmap, frame: PoseFrame, rotation: Int) {
        val now = System.currentTimeMillis()
        if (now - lastWebFrameTimeMs > 66) {
            lastWebFrameTimeMs = now
            appScope.launch(Dispatchers.Default) {
                val processedJpeg = PoseTracker.renderProcessedWebFrame(rawBitmap, frame, rotation)
                rawBitmap.recycle()
                if (processedJpeg != null) mjpegServer?.updateFrame(processedJpeg)
            }
        } else {
            rawBitmap.recycle()
        }
    }

    private fun setupUi() {
        val modelTypes = TrackerModelType.entries
        binding.modelSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modelTypes.map { it.displayName })
        binding.modelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                currentConfig = currentConfig.copy(modelType = modelTypes[pos])
                if (!isPopulatingUi) persistCurrentConfig()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        binding.cameraSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val items = cameraManager.availableCameras
                if (pos in items.indices && cameraManager.selectedCameraItem?.id != items[pos].id) {
                    val selectedItem = items[pos]
                    cameraManager.selectCamera(selectedItem)
                    currentConfig = currentConfig.copy(cameraId = selectedItem.id)
                    trackingController.bindUseCases(binding.previewView.surfaceProvider, selectedItem)
                    if (!isPopulatingUi) persistCurrentConfig()
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        val cacheUpdateListener = {
            if (!isPopulatingUi) {
                updateConfigFromUi(); updateButtonState(); persistCurrentConfig()
            }
        }
        binding.ipEditText.doOnTextChanged { _, _, _, _ -> cacheUpdateListener() }
        binding.portEditText.doOnTextChanged { _, _, _, _ -> cacheUpdateListener() }

        binding.heightSeekBar.setOnSeekBarChangeListener(createSeekBarListener { progress ->
            val h = 1.00f + (progress / 100f)
            binding.heightLabel.text = String.format(Locale.US, "User Height: %.2f m", h)
            cacheUpdateListener()
        })

        binding.fpsSeekBar.setOnSeekBarChangeListener(createSeekBarListener { progress ->
            binding.fpsLabel.text = getString(R.string.fps_label_value, progress + 10)
            cacheUpdateListener()
        })

        binding.invertCameraSwitch.setOnCheckedChangeListener { _, checked ->
            currentConfig = currentConfig.copy(invertCamera = checked)
            updateOverlayMirroring()
            if (!isPopulatingUi) persistCurrentConfig()
        }

        binding.adjustJointsButton.setOnClickListener {
            JointAdjustmentsDialog(this, { currentConfig }) { updated ->
                currentConfig = updated
                AppConfigStore.save(this, updated)
            }.show()
        }

        binding.calibrateButton.setOnClickListener {
            appScope.launch(Dispatchers.Main) {
                setUiControlsEnabled(false)
                for (sec in 5 downTo 1) {
                    binding.statusText.text = getString(R.string.status_calibrating, sec)
                    delay(1.seconds)
                }
                trackingController.pendingCalibration = true
                binding.statusText.text = getString(R.string.status_calibrate_pending)
                setUiControlsEnabled(true)
            }
        }

        binding.smoothingSeekBar.setOnSeekBarChangeListener(createSeekBarListener { progress ->
            binding.smoothingLabel.text = getString(R.string.smoothing_label_value, progress)
            currentConfig = currentConfig.copy(smoothing = progress)
            persistCurrentConfig()
        })

        binding.streamButton.setOnClickListener {
            if (currentConfig.ip.isEmpty()) {
                binding.statusText.text = getString(R.string.status_invalid_endpoint)
                return@setOnClickListener
            }
            trackingController.streamEnabled = !trackingController.streamEnabled
            binding.streamButton.text = if (trackingController.streamEnabled) getString(R.string.stop_stream) else getString(R.string.start_stream)
            binding.statusText.text = if (trackingController.streamEnabled) getString(R.string.status_streaming, currentConfig.ip, currentConfig.port) else getString(R.string.status_idle)
            // Prevent editing the destination while actively streaming to it.
            binding.ipEditText.isEnabled = !trackingController.streamEnabled
            binding.portEditText.isEnabled = !trackingController.streamEnabled
        }

        binding.toggleUiButton.setOnClickListener {
            uiVisible = !uiVisible
            binding.controlPanel.visibility = if (uiVisible) View.VISIBLE else View.GONE
            binding.toggleUiButton.text = if (uiVisible) getString(R.string.hide_ui) else getString(R.string.show_ui)
        }

        binding.resetButton.setOnClickListener {
            val defaultConfig = AppConfigStore.defaultConfig()
            AppConfigStore.clear(this)
            currentConfig = defaultConfig
            PoseOscMapper.resetCalibration()
            populateUiFromConfig(defaultConfig)
            binding.statusText.text = getString(R.string.status_settings_reset)
        }
    }

    private fun updateConfigFromUi() {
        val host = binding.ipEditText.text?.toString()?.trim().orEmpty()
        val port = binding.portEditText.text?.toString()?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 9000
        val height = 1.00f + (binding.heightSeekBar.progress / 100f)
        val fps = binding.fpsSeekBar.progress + 10

        currentConfig = currentConfig.copy(
            ip = host,
            port = port,
            heightMeters = height,
            fps = fps,
            cameraId = cameraManager.selectedCameraItem?.id ?: ""
        )
    }

    private fun populateUiFromConfig(config: AppConfig) {
        binding.ipEditText.setText(config.ip)
        binding.portEditText.setText(config.port.toString())

        binding.heightSeekBar.progress = ((config.heightMeters.coerceIn(1.0f, 2.0f) - 1.0f) * 100f).toInt()
        binding.heightLabel.text = String.format(Locale.US, "User Height: %.2f m", config.heightMeters)

        binding.fpsSeekBar.progress = (config.fps - 10).coerceIn(0, 50)
        binding.fpsLabel.text = getString(R.string.fps_label_value, config.fps)

        binding.smoothingSeekBar.progress = config.smoothing
        binding.invertCameraSwitch.isChecked = config.invertCamera
        binding.smoothingLabel.text = getString(R.string.smoothing_label_value, config.smoothing)

        val index = TrackerModelType.entries.indexOf(config.modelType)
        if (index >= 0) binding.modelSpinner.setSelection(index)

        updateButtonState()
    }

    private fun persistCurrentConfig() {
        AppConfigStore.save(this, currentConfig)
    }

    private fun updateCameraSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, cameraManager.availableCameras)
        binding.cameraSpinner.adapter = adapter
        val matchIndex = cameraManager.availableCameras.indexOfFirst { it.id == currentConfig.cameraId }
        if (matchIndex >= 0) binding.cameraSpinner.setSelection(matchIndex)
    }

    private fun updateOverlay(frame: PoseFrame) {
        runOnUiThread {
            updateOverlayMirroring()
            binding.jointOverlay.setFrameJoints(
                items = frame.joints,
                shouldMirrorX = currentConfig.invertCamera xor (cameraManager.selectedCameraItem?.isFront ?: false),
                sourceWidth = frame.imageWidth,
                sourceHeight = frame.imageHeight
            )
        }
    }

    private fun updateOverlayMirroring() {
        val isFront = cameraManager.selectedCameraItem?.isFront ?: false
        binding.jointOverlay.setMirrorX(currentConfig.invertCamera xor isFront)
    }

    private fun updateCameraInfoUi(fps: Float, levelName: String) {
        val fpsText = if (fps > 0f) String.format(Locale.US, "%.1f Hz", fps) else "Measuring..."
        binding.cameraLevelText.text = getString(R.string.camera_info_format, levelName, fpsText)
    }

    private fun maybeUpdateStatus(frame: PoseFrame) {
        val now = System.currentTimeMillis()
        if (now - lastStatusUpdateMs < 300L) return
        lastStatusUpdateMs = now

        val required = listOf("left_shoulder", "right_shoulder", "left_elbow", "right_elbow", "left_wrist", "right_wrist", "left_hip", "right_hip", "left_knee", "right_knee", "left_ankle", "right_ankle")
        val visibleCount = required.count { name -> frame.joints.firstOrNull { it.name == name }?.let { it.visibility > 0.25f } == true }

        runOnUiThread {
            val isComplete = visibleCount >= required.size / 2
            binding.statusText.text = if (isComplete) {
                getString(R.string.status_streaming, currentConfig.ip, currentConfig.port)
            } else {
                getString(R.string.status_partial_body, visibleCount, required.size)
            }
        }
    }

    private fun startMjpegServer() {
        appScope.launch(Dispatchers.IO) {
            try {
                mjpegServer = MjpegServer(8080).apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
            } catch (_: Exception) {}
        }
    }

    private fun stopMjpegServer() {
        try { mjpegServer?.stop(); mjpegServer = null } catch (_: Exception) {}
    }

    private fun updateButtonState() {
        binding.streamButton.isEnabled = currentConfig.ip.isNotEmpty()
    }

    private fun setUiControlsEnabled(enabled: Boolean) {
        binding.calibrateButton.isEnabled = enabled
        binding.resetButton.isEnabled = enabled
        // IP/port must stay locked while streaming, regardless of other controls' state.
        val allowEndpointEdit = enabled && !trackingController.streamEnabled
        binding.ipEditText.isEnabled = allowEndpointEdit
        binding.portEditText.isEnabled = allowEndpointEdit
        binding.heightSeekBar.isEnabled = enabled
        binding.fpsSeekBar.isEnabled = enabled
        binding.invertCameraSwitch.isEnabled = enabled
        binding.smoothingSeekBar.isEnabled = enabled
        binding.modelSpinner.isEnabled = enabled
        binding.cameraSpinner.isEnabled = enabled
        binding.adjustJointsButton.isEnabled = enabled
        binding.streamButton.isEnabled = if (enabled) currentConfig.ip.isNotEmpty() else false
    }

    private fun applyInsets() {
        val initialPanelBottomPadding = binding.controlPanel.paddingBottom
        val initialPanelLeftPadding = binding.controlPanel.paddingLeft
        val initialPanelRightPadding = binding.controlPanel.paddingRight
        val initialToggleBottomMargin = (binding.toggleUiButton.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.controlPanel.setPadding(
                initialPanelLeftPadding + bars.left,
                binding.controlPanel.paddingTop,
                initialPanelRightPadding + bars.right,
                initialPanelBottomPadding + bars.bottom
            )
            (binding.toggleUiButton.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                params.bottomMargin = initialToggleBottomMargin + bars.bottom
                binding.toggleUiButton.layoutParams = params
            }
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun updateLastBuildTimestamp() {
        val formatted = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(BuildConfig.BUILD_TIMESTAMP))
        binding.lastBuildText.text = getString(R.string.last_build_label, formatted)
    }

    private inline fun createSeekBarListener(crossinline onProgress: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onProgress(progress)
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) {}
        override fun onStopTrackingTouch(seekBar: SeekBar?) {}
    }
}