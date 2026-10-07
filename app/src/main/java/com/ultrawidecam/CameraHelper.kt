package com.ultrawidecam

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import android.util.SizeF

data class CameraInfo(
    val cameraId: String,
    val focalLength: Float,
    val equivalent35mmFocalLength: Float,
    val label: String,
    val zoomRatio: String,
    val lensFacing: Int,
    val sensorSize: SizeF?
)

object CameraHelper {

    private const val TAG = "CameraHelper"

    /**
     * Scans all available cameras and categorizes them by focal length and sensor size.
     * On Samsung Galaxy S23 Ultra:
     *   ~2.2mm (equiv ~13mm)  → 0.6x Ultrawide (Target camera for this app)
     *   ~6.3mm (equiv ~23mm)  → 1x Main
     *   ~7.9mm (equiv ~69mm)  → 3x Telephoto
     *   ~27.2mm (equiv ~230mm)→ 10x Periscope
     */
    fun getAllCameras(context: Context): List<CameraInfo> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return emptyList()
        val cameras = mutableListOf<CameraInfo>()

        for (cameraId in manager.cameraIdList) {
            try {
                val chars = manager.getCameraCharacteristics(cameraId)
                val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: continue
                val focalLengths = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val sensorSize = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)

                if (focalLengths != null && focalLengths.isNotEmpty()) {
                    val focal = focalLengths[0]
                    val sensorWidth = sensorSize?.width ?: 0f
                    val equiv35mm = if (sensorWidth > 0f) focal * (36.0f / sensorWidth) else focal
                    val label = classifyCamera(equiv35mm, facing)
                    val zoomRatio = estimateZoomRatio(equiv35mm, facing)

                    val info = CameraInfo(
                        cameraId = cameraId,
                        focalLength = focal,
                        equivalent35mmFocalLength = equiv35mm,
                        label = label,
                        zoomRatio = zoomRatio,
                        lensFacing = facing,
                        sensorSize = sensorSize
                    )
                    cameras.add(info)
                    Log.d(TAG, "Camera $cameraId: facing=$facing focal=${focal}mm (35mm equiv: ${equiv35mm}mm) label=$label")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reading camera $cameraId: ${e.message}")
            }
        }

        return cameras.sortedBy { it.focalLength }
    }

    /**
     * Returns back-facing cameras only, sorted by focal length ascending
     */
    fun getBackCameras(context: Context): List<CameraInfo> {
        return getAllCameras(context).filter {
            it.lensFacing == CameraCharacteristics.LENS_FACING_BACK
        }
    }

    /**
     * Find the 0.6x ultrawide camera ID - back camera with shortest focal length / widest FOV.
     * On Samsung S23 Ultra, this resolves to the functional ultrawide lens.
     */
    fun getUltraWideCameraId(context: Context): String? {
        val backCameras = getBackCameras(context)
        if (backCameras.isEmpty()) {
            val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            return manager?.cameraIdList?.firstOrNull()
        }

        val ultrawide = backCameras.minByOrNull { it.focalLength }
        Log.i(TAG, "Selected Ultrawide Camera ID: ${ultrawide?.cameraId} (${ultrawide?.focalLength}mm)")
        return ultrawide?.cameraId
    }

    /**
     * Returns front-facing cameras
     */
    fun getFrontCameras(context: Context): List<CameraInfo> {
        return getAllCameras(context).filter {
            it.lensFacing == CameraCharacteristics.LENS_FACING_FRONT
        }
    }

    /**
     * Find the front selfie camera ID
     */
    fun getFrontCameraId(context: Context): String? {
        val frontCameras = getFrontCameras(context)
        val front = frontCameras.firstOrNull()?.cameraId
        Log.i(TAG, "Selected Front Camera ID: $front")
        return front
    }

    private fun classifyCamera(equiv35mm: Float, facing: Int): String {
        if (facing == CameraCharacteristics.LENS_FACING_FRONT) return "Front Camera"

        return when {
            equiv35mm < 20f  -> "Ultrawide (0.6x)"
            equiv35mm < 40f  -> "Main (1x)"
            equiv35mm < 120f -> "Telephoto (3x)"
            else             -> "Periscope (10x)"
        }
    }

    private fun estimateZoomRatio(equiv35mm: Float, facing: Int): String {
        if (facing == CameraCharacteristics.LENS_FACING_FRONT) return "1x"
        return when {
            equiv35mm < 20f  -> "0.6x"
            equiv35mm < 40f  -> "1x"
            equiv35mm < 120f -> "3x"
            else             -> "10x"
        }
    }
}


// SENSOR_INFO_PHYSICAL_SIZE & LENS_INFO_AVAILABLE_FOCAL_LENGTHS query pipeline

// Sensor selection fallback to back-facing camera if ultrawide is unlisted
