package com.example.navisense

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log

class DistanceEstimator(context: Context? = null) {

    private var focalRatio: Float? = null

    init {
        if (context != null) {
            try {
                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                if (cameraManager != null) {
                    for (cameraId in cameraManager.cameraIdList) {
                        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                        val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                        if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                            val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                            val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                            if (focalLengths != null && focalLengths.isNotEmpty() && sensorSize != null && sensorSize.height > 0f) {
                                val focalLengthMm = focalLengths[0]
                                val sensorHeightMm = sensorSize.height
                                focalRatio = focalLengthMm / sensorHeightMm
                                Log.d(TAG, "Hardware focal ratio calculated: $focalRatio (focal: ${focalLengthMm}mm, sensorHeight: ${sensorHeightMm}mm)")
                            }
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Unable to read CameraCharacteristics focal length, will use default constant.", e)
            }
        }
    }

    var lastEstimatedDistance: Float? = null
        private set

    var lastEstimatedLabel: String? = null
        private set

    fun getFocalLengthPx(frameHeightPx: Int): Float {
        focalRatio?.let { ratio ->
            return ratio * frameHeightPx
        }
        return FOCAL_LENGTH_PX
    }

    /**
     * Estimates distance in meters: distance = (realHeight * focalLengthPx) / boxHeightPx
     */
    fun estimateDistance(label: String, boxHeightPx: Float, frameHeightPx: Int): Float {
        if (boxHeightPx <= 0f) return 0f
        val realHeight = REAL_HEIGHTS[label.lowercase()] ?: DEFAULT_REAL_HEIGHT_METERS
        val focalLengthPx = getFocalLengthPx(frameHeightPx)
        val calculated = (realHeight * focalLengthPx) / boxHeightPx
        lastEstimatedDistance = calculated
        lastEstimatedLabel = label
        return calculated
    }

    companion object {
        private const val TAG = "DistanceEstimator"

        // Easy-to-calibrate fallback constant for focal length in pixels
        const val FOCAL_LENGTH_PX: Float = 600f

        const val DEFAULT_REAL_HEIGHT_METERS: Float = 0.8f

        // Approximate real heights in meters for common classes
        val REAL_HEIGHTS: Map<String, Float> = mapOf(
            "person" to 1.7f,
            "chair" to 0.9f,
            "couch" to 0.9f,
            "sofa" to 0.9f,
            "bed" to 0.6f,
            "dining table" to 0.75f,
            "table" to 0.75f,
            "tv" to 0.5f,
            "television" to 0.5f,
            "bicycle" to 1.0f,
            "motorcycle" to 1.2f,
            "car" to 1.5f,
            "bus" to 3.0f,
            "truck" to 3.0f,
            "dog" to 0.5f,
            "cat" to 0.3f,
            "cow" to 1.5f,
            "horse" to 1.6f,
            "bench" to 0.9f,
            "bottle" to 0.25f,
            "suitcase" to 0.6f,
            "backpack" to 0.5f,
            "fire hydrant" to 0.75f,
            "stop sign" to 0.9f,
            "traffic light" to 1.0f,
            "door" to 2.0f,
            "potted plant" to 0.6f,
            "plant" to 0.6f
        )
    }
}
