package com.replatecamera

import android.content.ContentValues
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Captures a high-quality JPEG from the raw camera sensor via Camera2
 * TEMPLATE_STILL_CAPTURE, completely independent of ARCore rendering.
 *
 * The resulting JPEG contains ONLY the native sensor image — no AR overlays,
 * no 3D nodes, no anchor, no spheres.
 *
 * Usage:
 *   1. call start() once (e.g. in initializeSceneView)
 *   2. call capture() each time a still is needed
 *   3. call stop() on destroy
 */
class HighQualityPhotoCapture(private val context: Context) {

    companion object {
        private const val TAG = "HighQualityCapture"

        /**
         * When true, every saved JPEG is also inserted into the device gallery
         * via MediaStore so it is visible in the Photos app.
         * Flip to false for production builds.
         */
        var debugSaveToGallery: Boolean = false
    }

    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    fun start() {
        backgroundThread = HandlerThread("HQCaptureThread").also { it.start() }
        backgroundHandler = Handler(backgroundThread!!.looper)
        Log.i(TAG, "HighQualityPhotoCapture started")
    }

    fun stop() {
        backgroundThread?.quitSafely()
        try { backgroundThread?.join() } catch (_: InterruptedException) { }
        backgroundThread = null
        backgroundHandler = null
        Log.i(TAG, "HighQualityPhotoCapture stopped")
    }

    // -------------------------------------------------------------------------
    // Public capture API
    // -------------------------------------------------------------------------

    /**
     * Pause [arSession] (releases the camera to the system), take a Camera2
     * JPEG still capture, then resume [arSession].
     *
     * [onResume] is called on the MAIN THREAD once the ARCore session has been
     * resumed — the caller is responsible for restoring gorisse / ArSceneView
     * state (see ReplateCameraView.captureHighQuality).
     *
     * [onResult] is called with the saved File (or null + exception on failure).
     * It is called AFTER the ARCore session has been resumed.
     */
    fun capture(
        arSceneView: com.google.ar.sceneform.ArSceneView,
        onResume: () -> Unit,
        onResult: (File?, Exception?) -> Unit
    ) {
        val handler = backgroundHandler
        if (handler == null) {
            Log.e(TAG, "capture() called but HighQualityPhotoCapture was not started")
            onResult(null, IllegalStateException("HighQualityPhotoCapture not started"))
            return
        }

        // arSceneView.pause() must run on the main thread.
        // It stops the Choreographer frame callback AND pauses the ARCore session
        // atomically — preventing SessionPausedException from onBeginFrame.
        Handler(Looper.getMainLooper()).post {
            try {
                Log.i(TAG, "TRIGGER: pausing ArSceneView (Choreographer + ARCore) for Camera2 capture")
                arSceneView.pause()
                Log.i(TAG, "ArSceneView paused — camera released to system")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to pause ArSceneView before Camera2 capture", e)
                onResult(null, e)
                return@post
            }

            // Give the camera driver a moment to fully release
            handler.postDelayed({
                takeSensorJpeg { file, error ->
                    // session.resume() must also run on the main thread
                    Handler(Looper.getMainLooper()).post {
                        onResume()          // caller resumes gorisse / ArSceneView
                        onResult(file, error)
                    }
                }
            }, 150L)
        }
    }

    // -------------------------------------------------------------------------
    // Camera2 still capture
    // -------------------------------------------------------------------------

    private fun takeSensorJpeg(onResult: (File?, Exception?) -> Unit) {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        // Find back-facing camera
        val cameraId = try {
            cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Cannot enumerate cameras", e)
            onResult(null, e)
            return
        } ?: run {
            val e = IllegalStateException("No back-facing camera found")
            Log.e(TAG, e.message!!)
            onResult(null, e)
            return
        }

        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val streamMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val maxJpegSize = streamMap.getOutputSizes(ImageFormat.JPEG)
            .maxByOrNull { it.width * it.height }
            ?: run {
                onResult(null, IllegalStateException("No JPEG output sizes available"))
                return
            }

        Log.i(TAG, "Camera2 still: cameraId=$cameraId size=${maxJpegSize.width}x${maxJpegSize.height} sensorOrientation=$sensorOrientation")

        val imageReader = ImageReader.newInstance(
            maxJpegSize.width, maxJpegSize.height,
            ImageFormat.JPEG, 2
        )

        val openSemaphore = Semaphore(0)
        var cameraDevice: CameraDevice? = null
        var openError: Exception? = null

        val stateCallback = object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                Log.i(TAG, "CameraDevice opened — starting TEMPLATE_STILL_CAPTURE session")
                cameraDevice = device
                openSemaphore.release()
            }
            override fun onDisconnected(device: CameraDevice) {
                Log.w(TAG, "CameraDevice disconnected before capture")
                device.close()
                openError = IllegalStateException("Camera disconnected")
                openSemaphore.release()
            }
            override fun onError(device: CameraDevice, error: Int) {
                Log.e(TAG, "CameraDevice error=$error")
                device.close()
                openError = IllegalStateException("CameraDevice error: $error")
                openSemaphore.release()
            }
        }

        try {
            cameraManager.openCamera(cameraId, stateCallback, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "openCamera failed", e)
            imageReader.close()
            onResult(null, e)
            return
        }

        if (!openSemaphore.tryAcquire(4, TimeUnit.SECONDS)) {
            Log.e(TAG, "Camera open timed out after 4s")
            imageReader.close()
            onResult(null, Exception("Camera open timed out"))
            return
        }

        val device = cameraDevice
        if (device == null || openError != null) {
            imageReader.close()
            onResult(null, openError ?: Exception("Camera did not open"))
            return
        }

        // ---- Capture session -----------------------------------------------
        val captureSemaphore = Semaphore(0)
        var captureFile: File? = null
        var captureError: Exception? = null

        imageReader.setOnImageAvailableListener({ reader ->
            var image: android.media.Image? = null
            try {
                image = reader.acquireLatestImage()
                if (image == null) {
                    captureError = Exception("acquireLatestImage returned null")
                    captureSemaphore.release()
                    return@setOnImageAvailableListener
                }
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                captureFile = saveJpegBytes(bytes)
                Log.i(TAG, "JPEG saved — camera-only, zero AR overlays: ${captureFile?.absolutePath} (${bytes.size} bytes, ${maxJpegSize.width}x${maxJpegSize.height})")

                if (debugSaveToGallery) {
                    saveToGallery(bytes, captureFile!!.name)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error saving JPEG from ImageReader", e)
                captureError = e
            } finally {
                image?.close()
                captureSemaphore.release()
            }
        }, backgroundHandler)

        val sessionCallback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                try {
                    val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                        addTarget(imageReader.surface)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                        // Correct JPEG orientation so the image is upright without external rotation
                        set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
                        set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                    }.build()

                    session.capture(request, object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureFailed(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            failure: CaptureFailure
                        ) {
                            Log.e(TAG, "TEMPLATE_STILL_CAPTURE failed reason=${failure.reason}")
                            captureError = Exception("Capture failed: reason=${failure.reason}")
                            captureSemaphore.release()
                        }
                    }, backgroundHandler)

                    Log.i(TAG, "TEMPLATE_STILL_CAPTURE submitted (ImageReader target only — no AR surfaces)")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to submit capture request", e)
                    captureError = e
                    captureSemaphore.release()
                }
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e(TAG, "CameraCaptureSession configuration failed")
                captureError = Exception("CaptureSession configure failed")
                captureSemaphore.release()
            }
        }

        try {
            device.createCaptureSession(listOf(imageReader.surface), sessionCallback, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "createCaptureSession failed", e)
            imageReader.close()
            device.close()
            onResult(null, e)
            return
        }

        // Wait for image (max 10s)
        if (!captureSemaphore.tryAcquire(10, TimeUnit.SECONDS)) {
            captureError = Exception("Still capture timed out after 10s")
            Log.e(TAG, captureError!!.message!!)
        }

        // Always close Camera2 before handing camera back to ARCore
        try {
            imageReader.close()
            device.close()
            Log.i(TAG, "Camera2 device closed — camera available for ARCore resume")
        } catch (e: Exception) {
            Log.w(TAG, "Error closing Camera2 resources", e)
        }

        onResult(captureFile, captureError)
    }

    // -------------------------------------------------------------------------
    // Storage helpers
    // -------------------------------------------------------------------------

    private fun saveJpegBytes(bytes: ByteArray): File {
        val dir = File(context.getExternalFilesDir(null), "photos")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "replate_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { it.write(bytes) }
        return file
    }

    /**
     * DEBUG: copies the JPEG bytes into the device gallery (MediaStore).
     * The file will be visible in the Photos / Gallery app.
     * Only called when [debugSaveToGallery] == true.
     */
    private fun saveToGallery(bytes: ByteArray, displayName: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Replate")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { it.write(bytes) }
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    Log.i(TAG, "DEBUG: JPEG saved to gallery via MediaStore uri=$uri")
                }
            } else {
                @Suppress("DEPRECATION")
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "Replate"
                )
                dir.mkdirs()
                val file = File(dir, displayName)
                FileOutputStream(file).use { it.write(bytes) }
                // Notify media scanner
                android.media.MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
                Log.i(TAG, "DEBUG: JPEG saved to gallery (legacy) path=${file.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "DEBUG: Failed to save JPEG to gallery", e)
        }
    }
}
