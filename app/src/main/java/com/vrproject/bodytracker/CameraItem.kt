package com.vrproject.bodytracker

import androidx.camera.core.CameraInfo

data class CameraItem(
    val id: String,
    val name: String,
    val isFront: Boolean,
    val cameraInfo: CameraInfo
) {
    override fun toString(): String = name
}