package com.vrproject.bodytracker

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat

class CameraManager(
    private val activity: AppCompatActivity,
    private val onCameraReady: () -> Unit,
    private val onPermissionDenied: () -> Unit
) {
    val availableCameras = mutableListOf<CameraItem>()
    var selectedCameraItem: CameraItem? = null
        private set

    private val permissionLauncher: ActivityResultLauncher<String> =
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                onPermissionDenied()
            }
        }

    fun checkAndStartPermissions() {
        if (hasCameraPermission()) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(activity)
        providerFuture.addListener({
            onCameraReady()
        }, ContextCompat.getMainExecutor(activity))
    }

    fun queryAvailableCameras(provider: ProcessCameraProvider, savedCameraId: String): List<CameraItem> {
        availableCameras.clear()
        val cameraInfos = provider.availableCameraInfos
        for (info in cameraInfos) {
            val cam2Info = Camera2CameraInfo.from(info)
            val camId = cam2Info.getCameraId()
            val isFront = info.lensFacing == CameraSelector.LENS_FACING_FRONT
            val label = if (isFront) "Front Cam ($camId)" else "Back Cam ($camId)"
            availableCameras.add(CameraItem(camId, label, isFront, info))
        }

        val matchIndex = availableCameras.indexOfFirst { it.id == savedCameraId }
        selectedCameraItem = if (matchIndex >= 0) {
            availableCameras[matchIndex]
        } else {
            availableCameras.firstOrNull()
        }
        return availableCameras
    }

    fun selectCamera(item: CameraItem) {
        selectedCameraItem = item
    }
}