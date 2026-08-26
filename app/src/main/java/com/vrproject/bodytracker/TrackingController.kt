package com.vrproject.bodytracker

import android.graphics.Bitmap
import android.util.Size
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class TrackingController(
    private val activity: AppCompatActivity,
    private val appScope: CoroutineScope,
    private val configProvider: () -> AppConfig,
    private val cameraInfoProvider: () -> CameraItem?,
    private val onFrameProcessed: (PoseFrame) -> Unit,
    private val onWebFrameReady: (Bitmap, PoseFrame, Int) -> Unit,
    private val onCameraFpsUpdated: (Float, String) -> Unit,
    private val onStatusUpdateNeeded: (PoseFrame) -> Unit
) {
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val poseProcessor = PoseProcessor()
    private val oscSender = OscSender()

    var cameraProvider: ProcessCameraProvider? = null
        private set

    lateinit var poseTracker: PoseTracker
        private set

    @Volatile var streamEnabled = false
    @Volatile var pendingCalibration = false

    private var cameraFrameCount = 0
    private var lastCameraFpsTimeMs = 0L
    private var currentCameraFps = 0f
    private var activeCameraLevelName = "Checking..."
    private var lastSentAtMs = 0L

    fun initTracker(hasWebClientsProvider: () -> Boolean) {
        poseTracker = PoseTracker(
            context = activity,
            modelTypeProvider = { configProvider().modelType },
            targetFpsProvider = { configProvider().fps },
            onCheckShouldCaptureBitmap = hasWebClientsProvider
        ) { rawFrame, rawBitmap, rotationDegrees ->
            updateCameraCaptureFps(rawFrame.timestampMs)

            val processedFrame = processFrame(rawFrame)
            onFrameProcessed(processedFrame)

            if (rawBitmap != null && hasWebClientsProvider()) {
                onWebFrameReady(rawBitmap, processedFrame, rotationDegrees)
            } else {
                rawBitmap?.recycle()
            }

            if (!streamEnabled) return@PoseTracker

            val config = configProvider()
            if (config.ip.isEmpty()) return@PoseTracker

            if (!canSendNow(processedFrame.timestampMs, config.fps)) {
                return@PoseTracker
            }

            val isFront = cameraInfoProvider()?.isFront ?: false
            val messages = PoseOscMapper.toMessages(
                frame = processedFrame,
                estimatedHeightMeters = config.heightMeters,
                isFrontCamera = isFront,
                config = config
            )

            appScope.launch(Dispatchers.IO) {
                oscSender.send(config.ip, config.port, messages, bundle = true)
            }

            onStatusUpdateNeeded(processedFrame)
        }
    }

    fun bindUseCases(
        surfaceProvider: Preview.SurfaceProvider,
        selectedCamera: CameraItem?
    ) {
        val providerFuture = ProcessCameraProvider.getInstance(activity)
        providerFuture.addListener({
            val provider = providerFuture.get()
            cameraProvider = provider
            val currentCamera = selectedCamera ?: return@addListener

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = surfaceProvider
            }

            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(480, 480),
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

            val selector = CameraSelector.Builder()
                .addCameraFilter { cameras ->
                    cameras.filter { Camera2CameraInfo.from(it).getCameraId() == currentCamera.id }
                }
                .build()

            val camera = provider.bindToLifecycle(activity, selector, preview, analysis)
            val camera2Info = Camera2CameraInfo.from(camera.cameraInfo)
            val hardwareLevel = camera2Info.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)

            activeCameraLevelName = when (hardwareLevel) {
                android.hardware.camera2.CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                android.hardware.camera2.CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                android.hardware.camera2.CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                android.hardware.camera2.CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                else -> "UNKNOWN ($hardwareLevel)"
            }

            onCameraFpsUpdated(currentCameraFps, activeCameraLevelName)
        }, ContextCompat.getMainExecutor(activity))
    }

    private fun processFrame(frame: PoseFrame): PoseFrame {
        if (pendingCalibration) {
            PoseOscMapper.calibrateRoot(frame)
            pendingCalibration = false
        }
        val config = configProvider()
        val alpha = (config.smoothing / 100f).coerceIn(0f, 0.95f)
        return poseProcessor.process(frame, StreamConfig(smoothingAlpha = alpha))
    }

    private fun canSendNow(nowMs: Long, fps: Int): Boolean {
        val minInterval = 1000L / fps.coerceIn(10, 60)
        if (nowMs - lastSentAtMs < minInterval) return false
        lastSentAtMs = nowMs
        return true
    }

    private fun updateCameraCaptureFps(nowMs: Long) {
        if (lastCameraFpsTimeMs == 0L) lastCameraFpsTimeMs = nowMs
        cameraFrameCount++
        val elapsed = nowMs - lastCameraFpsTimeMs
        if (elapsed >= 1000L) {
            currentCameraFps = (cameraFrameCount * 1000f) / elapsed.toFloat()
            cameraFrameCount = 0
            lastCameraFpsTimeMs = nowMs
            activity.runOnUiThread {
                onCameraFpsUpdated(currentCameraFps, activeCameraLevelName)
            }
        }
    }

    fun destroy() {
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
        if (::poseTracker.isInitialized) poseTracker.close()
        oscSender.close()
        poseProcessor.clear()
    }
}