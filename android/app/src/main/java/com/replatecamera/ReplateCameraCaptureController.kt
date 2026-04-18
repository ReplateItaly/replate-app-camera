package com.replatecamera

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import com.facebook.react.bridge.Promise
import com.google.ar.core.Frame
import com.google.ar.sceneform.AnchorNode
import com.google.ar.sceneform.ArSceneView
import com.google.ar.sceneform.math.Vector3
import java.util.concurrent.Executors
import kotlin.math.acos

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
    object NotInRange : ARError("NOT_IN_RANGE", "Camera not in range")
    object Unknown : ARError("UNKNOWN_ERROR", "Unknown error occurred")
}

/**
 * Main controller for AR interactions, mirroring Swift's `ReplateCameraController`.
 * This class orchestrates photo validation, capture, and state management.
 *
 * Photo capture backend: Camera2 TEMPLATE_STILL_CAPTURE via HighQualityPhotoCapture.
 * The saved JPEG is a pure sensor image — no AR overlays are ever written into it.
 */
class ReplateCameraCaptureController(
    private val context: Context,
    private val arSceneView: ArSceneView,
    private val cameraView: ReplateCameraView
) {

    companion object {
        private const val TAG = "ReplateCameraController"

        private const val ANGLE_THRESHOLD = 0.6f
        private const val HIGH_MEMORY_THRESHOLD_BYTES = 500_000_000L

        // Use a dedicated background thread for EXIF and metadata work
        private val arQueue = Executors.newSingleThreadExecutor()
    }

    // Auto-capture state
    @Volatile private var isAutoCaptureInProgress = false
    private var lastAutoCaptureAttemptMs = 0L

    private fun logD(message: String) = Log.d(TAG, message)
    private fun logI(message: String) = Log.i(TAG, message)
    private fun logW(message: String) = Log.w(TAG, message)
    private fun logE(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(TAG, message, throwable)
        } else {
            Log.e(TAG, message)
        }
    }

    private fun rejectWithLog(callback: SafeCallbackHandler, error: ARError, detail: String) {
        logW("Rejecting photo request error=${error.code} detail=$detail")
        callback.reject(error)
    }

    // -------------------------------------------------------------------------
    // Auto-capture (called every frame from onUpdate)
    // -------------------------------------------------------------------------

    /**
     * Called from onUpdate (main thread) every frame while the anchor is placed.
     * Debounces to 250 ms, then fires a full Camera2 still capture if the camera
     * is pointing at a sphere angle that hasn't been captured yet.
     */
    fun triggerAutoCaptureIfNewAngle(frame: Frame) {
        if (isAutoCaptureInProgress) return
        val now = System.currentTimeMillis()
        if (now - lastAutoCaptureAttemptMs < 250) return
        lastAutoCaptureAttemptMs = now

        val deviceTargetInfo = getDeviceTargetInfo(frame)
        if (!deviceTargetInfo.isInFocus) return

        isAutoCaptureInProgress = true

        cameraView.updateCircleFocus(deviceTargetInfo.targetIndex)

        if (!cameraView.checkCameraDistance(deviceTargetInfo)) {
            isAutoCaptureInProgress = false
            return
        }

        cameraView.updateSpheres(deviceTargetInfo, frame.camera) { isNew ->
            if (!isNew) {
                isAutoCaptureInProgress = false
                return@updateSpheres
            }

            logI("TRIGGER: auto-capture new angle targetIndex=${deviceTargetInfo.targetIndex} totalAngles=${ReplateCameraView.photosFromDifferentAnglesTaken}")
            cameraView.performPhotoTakenHaptic()

            val json = getTransformJSON(frame, cameraView.getTransformRootNode() ?: cameraView.getAnchorNode()!!)

            // ARCore frame capture — sensor JPEG, no AR overlays, session never paused
            cameraView.captureFromFrame(frame) { file ->
                if (file != null) {
                    arQueue.execute {
                        try {
                            val uri = Uri.fromFile(file)
                            saveExif(uri, json)
                            val totalAngles = ReplateCameraView.photosFromDifferentAnglesTaken
                            logI("SAVED: auto-capture JPEG uri=$uri totalAngles=$totalAngles")
                            Handler(Looper.getMainLooper()).post {
                                cameraView.sendPhotoTakenEvent(totalAngles)
                            }
                        } catch (e: Exception) {
                            logE("Auto-capture EXIF/save error", e)
                        } finally {
                            isAutoCaptureInProgress = false
                        }
                    }
                } else {
                    logW("Auto-capture: frame capture returned null")
                    isAutoCaptureInProgress = false
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Manual takePhoto (called from JS bridge)
    // -------------------------------------------------------------------------

    /**
     * The main photo-taking orchestrator, mirroring the Swift implementation.
     * It validates conditions (anchor, focus, distance) before capturing.
     */
    fun takePhoto(unlimited: Boolean, promise: Promise) {
        logI("takePhoto requested unlimited=$unlimited")
        if (shouldDelayForMemoryPressure()) {
            logW("High memory pressure detected, delaying photo capture briefly")
            Handler(Looper.getMainLooper()).postDelayed({
                takePhoto(unlimited, promise)
            }, 100)
            return
        }
        arQueue.execute {
            try {
                validateAndProcessPhoto(unlimited, promise)
            } catch (e: Exception) {
                logE("Unexpected error in takePhoto", e)
            }
        }
    }

    private fun validateAndProcessPhoto(unlimited: Boolean, promise: Promise) {
        val anchorNode = cameraView.getAnchorNode() ?: return
        if (!isAnchorNodeValid(anchorNode)) return

        val frame = arSceneView.arFrame ?: return

        val deviceTargetInfo = getDeviceTargetInfo(frame)
        if (!deviceTargetInfo.isInFocus) return

        processTargetedDevice(deviceTargetInfo, unlimited, SafeCallbackHandler(promise))
    }

    private fun processTargetedDevice(
        deviceTargetInfo: DeviceTargetInfo,
        unlimited: Boolean,
        callback: SafeCallbackHandler
    ) {
        val frame = arSceneView.arFrame ?: return

        Handler(Looper.getMainLooper()).post {
            cameraView.updateCircleFocus(deviceTargetInfo.targetIndex)

            if (!cameraView.checkCameraDistance(deviceTargetInfo)) return@post

            cameraView.updateSpheres(deviceTargetInfo, frame.camera) { success ->
                if (!unlimited && !success) return@updateSpheres

                cameraView.performPhotoTakenHaptic()

                val json = getTransformJSON(frame, cameraView.getTransformRootNode() ?: cameraView.getAnchorNode()!!)
                processAndSaveImage(json, frame, callback)
            }
        }
    }

    private fun processAndSaveImage(json: String, frame: Frame, callback: SafeCallbackHandler) {
        cameraView.captureFromFrame(frame) { file ->
            if (file != null) {
                arQueue.execute {
                    try {
                        val uri = Uri.fromFile(file)
                        saveExif(uri, json)
                        logI("SAVED: manual takePhoto JPEG uri=$uri")
                        callback.resolve(uri.toString())
                    } catch (e: Exception) {
                        logE("Error writing EXIF / resolving URI", e)
                        rejectWithLog(callback, ARError.ProcessingError, "exception during EXIF save")
                    }
                }
            } else {
                rejectWithLog(callback, ARError.CaptureError, "Frame capture failed")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun shouldDelayForMemoryPressure(): Boolean {
        return try {
            val activityManager = context.getSystemService(ActivityManager::class.java) ?: return false
            val memoryInfo = activityManager.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid()))
            val residentBytes = memoryInfo.firstOrNull()?.totalPss?.times(1024L) ?: return false
            residentBytes > HIGH_MEMORY_THRESHOLD_BYTES
        } catch (e: Exception) {
            logW("Unable to inspect memory pressure: ${e.message}")
            false
        }
    }

    private fun saveExif(uri: Uri, json: String) {
        try {
            val exif = ExifInterface(uri.path!!)
            exif.setAttribute(ExifInterface.TAG_USER_COMMENT, json)
            exif.saveAttributes()
        } catch (e: Exception) {
            logE("Error saving EXIF data", e)
        }
    }

    private fun getDeviceTargetInfo(frame: Frame): DeviceTargetInfo {
        val cameraPose = frame.camera.pose
        val anchorPose = cameraView.getAnchorPose(cameraView.getTransformRootNode())
          ?: return DeviceTargetInfo(false, -1, cameraPose, Vector3.zero(), Vector3.zero())

        val cameraPosition = Vector3(cameraPose.tx(), cameraPose.ty(), cameraPose.tz())
        val anchorPosition = Vector3(anchorPose.tx(), anchorPose.ty(), anchorPose.tz())

        val directionToAnchor = Vector3.subtract(anchorPosition, cameraPosition).normalized()
        val cameraZAxis = cameraPose.zAxis
        val deviceDirection = Vector3(-cameraZAxis[0], -cameraZAxis[1], -cameraZAxis[2]).normalized()
        val dotValue = Vector3.dot(deviceDirection, directionToAnchor).coerceIn(-1f, 1f)
        val angleToAnchor = acos(dotValue)
        val isInFocus = angleToAnchor < ANGLE_THRESHOLD

        val relativeCameraPose = anchorPose.inverse().compose(cameraPose)
        val anchorScale = cameraView.getTransformRootNode()?.worldScale?.y?.takeIf { it > 0.001f } ?: 1f
        val spheresHeight = 0.15f * anchorScale
        val distanceBetweenCircles = 0.10f * anchorScale
        val twoThirdsDistance = spheresHeight + distanceBetweenCircles + (distanceBetweenCircles / 5f)
        val targetIndex = if (isInFocus) {
            if (relativeCameraPose.ty() < twoThirdsDistance) 1 else 0
        } else {
            -1
        }

        return DeviceTargetInfo(
            isInFocus = isInFocus,
            targetIndex = targetIndex,
            transform = cameraPose,
            cameraPosition = cameraPosition,
            deviceDirection = deviceDirection
        )
    }

    private fun isAnchorNodeValid(anchorNode: AnchorNode): Boolean {
        val scale = anchorNode.worldScale
        val position = anchorNode.worldPosition
        val rotation = anchorNode.worldRotation
        return scale.x > 0.001f &&
            scale.y > 0.001f &&
            scale.z > 0.001f &&
            !position.x.isNaN() &&
            !position.y.isNaN() &&
            !position.z.isNaN() &&
            !rotation.x.isNaN() &&
            !rotation.y.isNaN() &&
            !rotation.z.isNaN() &&
            !rotation.w.isNaN()
    }

    private fun getTransformJSON(frame: Frame, anchorNode: com.google.ar.sceneform.Node): String {
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
            "gravityVector": { "x": ${gravity["x"] ?: 0.0}, "y": ${gravity["y"] ?: 0.0}, "z": ${gravity["z"] ?: 0.0} }
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
