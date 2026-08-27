package com.vrproject.bodytracker

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy

/**
 * Strategy interface implemented by each pose-detection backend (MediaPipe, ML Kit).
 * Each engine owns its own preprocessing, inference call, image lifecycle (closing the
 * [ImageProxy]) and result conversion, since these differ substantially between backends.
 */
interface PoseEngine {

    /**
     * Processes a single camera frame. Implementations MUST close [imageProxy] exactly once
     * and MUST invoke [onProcessingDone] exactly once when the frame has been fully handled
     * (successfully or not), so the caller can release its processing lock.
     */
    fun analyze(
        imageProxy: ImageProxy,
        rotationDegrees: Int,
        shouldCaptureBitmap: Boolean,
        timestampMs: Long,
        onProcessingDone: () -> Unit
    )

    /** Releases any native resources held by the underlying detector. */
    fun close()
}

/** Convenience alias used by engines to report a finished frame back to [PoseTracker]. */
typealias PoseFrameCallback = (PoseFrame, Bitmap?, Int) -> Unit
