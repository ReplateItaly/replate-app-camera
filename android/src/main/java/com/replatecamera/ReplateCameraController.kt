package com.replatecamera

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.facebook.react.bridge.Promise
import com.google.ar.core.Camera
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import com.google.ar.sceneform.AnchorNode
import com.google.ar.sceneform.ArSceneView
import com.google.ar.sceneform.math.Vector3
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.Executors

/**
 * Error types for AR operations, mirroring Swift's ARError enum.
 */
sealed class ARError(val code: String, val description: String) {
    object NoAnchor : ARError("NO_ANCHOR", "No anchor set yet")
    object InvalidAnchor : ARError("INVALID_ANCHOR", "AnchorNode is not valid")
    object NotInFocus : ARError("NOT_IN_FOCUS", "Object not in focus")
    object CaptureError : ARError("CAPTURE_ERROR", "Error capturing image")
    object TooManyImages : ARError("TOO_MANY_IMAGES", "Too many images and the last one's not from a new angle")
    object ProcessingError : ARError("PROCESSING_ERROR", "Error processing image")
    object SavingError : ARError("SAVING_ERROR", "Error saving photo")
    object TransformError : ARError("TRANSFORM_ERROR", "Camera transform data not available")
    object LightingError : ARError("LIGHTING_ERROR", "Image too dark")
    object NotInRange : ARError("NOT_IN_RANGE", "Camera not in range")
    object Unknown : ARError("UNKNOWN_ERROR", "Unknown error occurred")
}

/**
 * Main controller for AR interactions, mirroring Swift's `ReplateCameraController`.
 * This class orchestrates photo validation, capture, and state management.
 */
class ReplateCameraController(
    private val context: Context,
    private val arSceneView: ArSceneView,
    private val cameraView: ReplateCameraView
) {

    companion object {
        private const val TAG = "ReplateCameraController"

        // Configuration constants from Swift
        private const val MIN_DISTANCE = 0.25f
        private const val MAX_DISTANCE = 0.55f
        private const val ANGLE_THRESHOLD = 0.6f
        private const val TARGET_IMAGE_WIDTH = 3072
        private const val TARGET_IMAGE_HEIGHT = 2304
        private const val MIN_AMBIENT_INTENSITY = 650f

        // Use a dedicated background thread for processing
        private val arQueue = Executors.newSingleThreadExecutor()
    }

    /**
     * Gets the current count of photos taken.
     */
    fun getPhotosCount(promise: Promise) {
        promise.resolve(ReplateCameraView.totalPhotosTaken)
    }

    /**
     * Checks if the 360-degree scan is complete.
     */
    fun isScanComplete(promise: Promise) {
        val isComplete = ReplateCameraView.photosFromDifferentAnglesTaken >= 144
        promise.resolve(isComplete)
    }

    /**
     * Gets the number of remaining angles to capture.
     */
    fun getRemainingAnglesToScan(promise: Promise) {
        val remaining = 144 - ReplateCameraView.photosFromDifferentAnglesTaken
        promise.resolve(remaining)
    }

    /**
     * Gets current memory usage in MB.
     */
    fun getMemoryUsage(promise: Promise) {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        val memoryUsageMB = (memoryInfo.totalMem - memoryInfo.availMem) / (1024.0 * 1024.0)
        promise.resolve(mapOf("memoryUsageMB" to memoryUsageMB))
    }

    /**
     * The main photo-taking orchestrator, mirroring the Swift implementation.
     * It validates conditions (anchor, focus, distance, lighting) before capturing.
     */
    fun takePhoto(unlimited: Boolean, promise: Promise) {
        arQueue.execute {
            try {
                validateAndProcessPhoto(unlimited, promise)
            } catch (e: Exception) {
                // Handle unexpected errors
                SafeCallbackHandler(promise).reject(ARError.Unknown)
            }
        }
    }

    private fun validateAndProcessPhoto(unlimited: Boolean, promise: Promise) {
        val callback = SafeCallbackHandler(promise)

        // 1. Get a valid anchor
        val anchorNode = cameraView.getAnchorNode()
        if (anchorNode == null) {
            callback.reject(ARError.NoAnchor)
            return
        }
        if (!isAnchorNodeValid(anchorNode)) {
            callback.reject(ARError.InvalidAnchor)
            return
        }

        // 2. Get device target info
        val frame = arSceneView.arFrame
        if (frame == null) {
            callback.reject(ARError.TransformError)
            return
        }

        val deviceTargetInfo = getDeviceTargetInfo(anchorNode, frame)
        if (!deviceTargetInfo.isInFocus) {
            callback.reject(ARError.NotInFocus)
            return
        }

        // 3. Process the target
        processTargetedDevice(deviceTargetInfo, unlimited, callback)
    }

    private fun processTargetedDevice(
        deviceTargetInfo: DeviceTargetInfo,
        unlimited: Boolean,
        callback: SafeCallbackHandler
    ) {
        val frame = arSceneView.arFrame ?: return

        // Check lighting
        if (frame.lightEstimate.pixelIntensity < MIN_AMBIENT_INTENSITY) {
            callback.reject(ARError.LightingError)
            return
        }

        // Run UI updates on the main thread
        Handler(Looper.getMainLooper()).post {
            // Update UI focus circle
            cameraView.updateCircleFocus(deviceTargetInfo.targetIndex)

            // Check distance
            if (!cameraView.checkCameraDistance(deviceTargetInfo)) {
                callback.reject(ARError.NotInRange)
                return@post
            }

            // Update spheres and get result
            cameraView.updateSpheres(deviceTargetInfo, frame.camera) { success ->
                if (!unlimited && !success) {
                    callback.reject(ARError.TooManyImages)
                    return@updateSpheres
                }

                // Capture image on background thread
                arQueue.execute {
                    processAndSaveImage(frame, callback)
                }
            }
        }
    }

    private fun processAndSaveImage(frame: Frame, callback: SafeCallbackHandler) {
        cameraView.takePhoto { bitmap ->
            if (bitmap != null) {
                try {
                    val rotatedBitmap = rotateBitmap(bitmap, 90f)
                    val resizedBitmap = resizeBitmap(rotatedBitmap, TARGET_IMAGE_WIDTH, TARGET_IMAGE_HEIGHT)
                    val photoFile = cameraView.saveBitmap(resizedBitmap)
                    val uri = Uri.fromFile(photoFile)
                    val json = getTransformJSON(frame, cameraView.getAnchorNode()!!)
                    saveExif(uri, json)
                    callback.resolve(uri.toString())
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing or saving image", e)
                    callback.reject(ARError.ProcessingError)
                }
            } else {
                callback.reject(ARError.CaptureError)
            }
        }
    }

    private fun saveExif(uri: Uri, json: String) {
        try {
            val exif = ExifInterface(uri.path!!)
            exif.setAttribute(ExifInterface.TAG_USER_COMMENT, json)
            exif.saveAttributes()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving EXIF data", e)
        }
    }

    private fun resizeBitmap(bitmap: Bitmap, width: Int, height: Int): Bitmap {
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = android.graphics.Matrix()
        matrix.postRotate(degrees)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun getDeviceTargetInfo(anchorNode: AnchorNode, frame: Frame): DeviceTargetInfo {
        val cameraTransform = frame.camera.pose
        val anchorTransform = anchorNode.anchor?.pose ?: return DeviceTargetInfo(false, 0, cameraTransform, Vector3.zero(), Vector3.zero())

        val cameraPosition = Vector3(cameraTransform.tx(), cameraTransform.ty(), cameraTransform.tz())
        val anchorPosition = Vector3(anchorTransform.tx(), anchorTransform.ty(), anchorTransform.tz())

        // Simplified focus and target index logic
        val directionToAnchor = Vector3.subtract(anchorPosition, cameraPosition).normalized()
        val cameraDirection = frame.camera.displayOrientedPose.zAxis.let { Vector3(it[0], it[1], it[2]) }
        val angleToAnchor = Vector3.dot(cameraDirection, directionToAnchor)

        val isInFocus = angleToAnchor < ANGLE_THRESHOLD
        val targetIndex = if (cameraPosition.y < anchorPosition.y + 0.10f) 0 else 1

        return DeviceTargetInfo(
            isInFocus = isInFocus,
            targetIndex = targetIndex,
            transform = cameraTransform,
            cameraPosition = cameraPosition,
            deviceDirection = cameraDirection
        )
    }

    private fun isAnchorNodeValid(anchorNode: AnchorNode): Boolean {
        val scale = anchorNode.worldScale
        return scale.x > 0.001f && scale.y > 0.001f && scale.z > 0.001f
    }

    private fun getTransformJSON(frame: Frame, anchorNode: AnchorNode): String {
        val transform = frame.camera.pose
        val translation = floatArrayOf(transform.tx(), transform.ty(), transform.tz())
        val rotation = floatArrayOf(transform.qx(), transform.qy(), transform.qz(), transform.qw())
        val scale = floatArrayOf(anchorNode.worldScale.x, anchorNode.worldScale.y, anchorNode.worldScale.z)
        val gravity = cameraView.getGravityVector()

        return """
        {
            "translation": { "x": ${translation[0]}, "y": ${translation[1]}, "z": ${translation[2]} },
            "rotation": { "x": ${rotation[0]}, "y": ${rotation[1]}, "z": ${rotation[2]}, "w": ${rotation[3]} },
            "scale": { "x": ${scale[0]}, "y": ${scale[1]}, "z": ${scale[2]} },
            "gravityVector": { "x": ${gravity["x"]}, "y": ${gravity["y"]}, "z": ${gravity["z"]} }
        }
        """.trimIndent()
    }
}

/**
 * A thread-safe wrapper for promises to prevent multiple callbacks.
 */
class SafeCallbackHandler(private val promise: Promise) {
    @Volatile
    private var hasCalledBack = false
    private val lock = Any()

    fun resolve(result: Any?) {
        synchronized(lock) {
            if (!hasCalledBack) {
                hasCalledBack = true
                promise.resolve(result)
            }
        }
    }

    fun reject(error: ARError) {
        synchronized(lock) {
            if (!hasCalledBack) {
                hasCalledBack = true
                promise.reject(error.code, error.description)
            }
        }
    }
}

/**
 * Data class to hold targeting information, mirroring Swift's `DeviceTargetInfo`.
 */
data class DeviceTargetInfo(
    val isInFocus: Boolean,
    val targetIndex: Int,
    val transform: com.google.ar.core.Pose,
    val cameraPosition: Vector3,
    val deviceDirection: Vector3
)
