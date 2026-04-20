package com.replatecamera

import android.app.ActivityManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Callback
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod

class ReplateCameraController(private val reactContext: ReactApplicationContext) :
  ReactContextBaseJavaModule(reactContext) {

  companion object {
    private const val TAG = "ReplateCameraControllerRN"
    private val lock = Any()

    private var completedTutorialCallback: Callback? = null
    private var anchorSetCallback: Callback? = null
    private var completedUpperSpheresCallback: Callback? = null
    private var completedLowerSpheresCallback: Callback? = null
    private var openedTutorialCallback: Callback? = null
    private var tooCloseCallback: Callback? = null
    private var tooFarCallback: Callback? = null
    private var backInRangeCallback: Callback? = null
    private var photoTakenCallback: Callback? = null

    @Synchronized
    fun consumeCompletedTutorialCallback(): Callback? {
      val callback = completedTutorialCallback
      completedTutorialCallback = null
      return callback
    }

    @Synchronized
    fun consumeAnchorSetCallback(): Callback? {
      val callback = anchorSetCallback
      anchorSetCallback = null
      return callback
    }

    @Synchronized
    fun consumeCompletedUpperSpheresCallback(): Callback? {
      val callback = completedUpperSpheresCallback
      completedUpperSpheresCallback = null
      return callback
    }

    @Synchronized
    fun consumeCompletedLowerSpheresCallback(): Callback? {
      val callback = completedLowerSpheresCallback
      completedLowerSpheresCallback = null
      return callback
    }

    @Synchronized
    fun consumeOpenedTutorialCallback(): Callback? {
      val callback = openedTutorialCallback
      openedTutorialCallback = null
      return callback
    }

    @Synchronized
    fun consumeTooCloseCallback(): Callback? {
      val callback = tooCloseCallback
      tooCloseCallback = null
      return callback
    }

    @Synchronized
    fun consumeTooFarCallback(): Callback? {
      val callback = tooFarCallback
      tooFarCallback = null
      return callback
    }

    @Synchronized
    fun consumeBackInRangeCallback(): Callback? {
      val callback = backInRangeCallback
      backInRangeCallback = null
      return callback
    }

    @Synchronized
    fun consumePhotoTakenCallback(): Callback? {
      val callback = photoTakenCallback
      photoTakenCallback = null
      return callback
    }
  }

  override fun getName(): String = "ReplateCameraController"

  @ReactMethod
  fun registerOpenedTutorialCallback(callback: Callback) {
    Log.d(TAG, "registerOpenedTutorialCallback")
    synchronized(lock) {
      openedTutorialCallback = callback
    }
  }

  @ReactMethod
  fun registerCompletedTutorialCallback(callback: Callback) {
    Log.d(TAG, "registerCompletedTutorialCallback")
    synchronized(lock) {
      completedTutorialCallback = callback
    }
  }

  @ReactMethod
  fun registerAnchorSetCallback(callback: Callback) {
    Log.d(TAG, "registerAnchorSetCallback")
    synchronized(lock) {
      anchorSetCallback = callback
    }
  }

  @ReactMethod
  fun registerCompletedUpperSpheresCallback(callback: Callback) {
    Log.d(TAG, "registerCompletedUpperSpheresCallback")
    synchronized(lock) {
      completedUpperSpheresCallback = callback
    }
  }

  @ReactMethod
  fun registerCompletedLowerSpheresCallback(callback: Callback) {
    Log.d(TAG, "registerCompletedLowerSpheresCallback")
    synchronized(lock) {
      completedLowerSpheresCallback = callback
    }
  }

  @ReactMethod
  fun registerTooCloseCallback(callback: Callback) {
    Log.d(TAG, "registerTooCloseCallback")
    synchronized(lock) {
      tooCloseCallback = callback
    }
  }

  @ReactMethod
  fun registerTooFarCallback(callback: Callback) {
    Log.d(TAG, "registerTooFarCallback")
    synchronized(lock) {
      tooFarCallback = callback
    }
  }

  @ReactMethod
  fun registerBackInRangeCallback(callback: Callback) {
    Log.d(TAG, "registerBackInRangeCallback")
    synchronized(lock) {
      backInRangeCallback = callback
    }
  }

  @ReactMethod
  fun registerPhotoTakenCallback(callback: Callback) {
    Log.d(TAG, "registerPhotoTakenCallback")
    synchronized(lock) {
      photoTakenCallback = callback
    }
  }

  @ReactMethod
  fun takePhoto(unlimited: Boolean, promise: Promise) {
    Log.i(TAG, "takePhoto called unlimited=$unlimited")
    val view = ReplateCameraView.getCurrentInstance()
    if (view == null) {
      Log.e(TAG, "takePhoto failed: ReplateCameraView is null")
      promise.reject("NO_VIEW", "ReplateCameraView is not initialized")
      return
    }

    val controller = view.getCameraController()
    if (controller == null) {
      Log.e(TAG, "takePhoto failed: camera controller is null")
      promise.reject("NO_CONTROLLER", "Camera controller is not initialized")
      return
    }

    controller.takePhoto(unlimited, promise)
  }

  @ReactMethod
  fun getPhotosCount(promise: Promise) {
    Log.d(TAG, "getPhotosCount")
    promise.resolve(ReplateCameraView.totalPhotosTaken)
  }

  @ReactMethod
  fun isScanComplete(promise: Promise) {
    Log.d(TAG, "isScanComplete")
    promise.resolve(ReplateCameraView.photosFromDifferentAnglesTaken >= 144)
  }

  @ReactMethod
  fun getRemainingAnglesToScan(promise: Promise) {
    Log.d(TAG, "getRemainingAnglesToScan")
    promise.resolve(144 - ReplateCameraView.photosFromDifferentAnglesTaken)
  }

  @ReactMethod
  fun getMemoryUsage(promise: Promise) {
    Log.d(TAG, "getMemoryUsage")
    try {
      val activityManager = reactContext.getSystemService(ActivityManager::class.java)
      if (activityManager == null) {
        promise.resolve(mapOf("memoryUsageMB" to -1.0))
        return
      }

      val processMemInfo = activityManager.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid()))
      if (processMemInfo.isEmpty()) {
        promise.resolve(mapOf("memoryUsageMB" to -1.0))
        return
      }
      val memoryUsageMB = processMemInfo[0].totalPss / 1024.0
      promise.resolve(mapOf("memoryUsageMB" to memoryUsageMB))
    } catch (e: Exception) {
      Log.e(TAG, "getMemoryUsage failed", e)
      promise.resolve(mapOf("memoryUsageMB" to -1.0))
    }
  }

  @ReactMethod
  fun getCapturedPhotoPaths(promise: Promise) {
    val paths = ReplateCameraView.capturedPhotoPaths.toList()
    val array = Arguments.createArray()
    paths.forEach { array.pushString(it) }
    promise.resolve(array)
  }

  @ReactMethod
  fun reset() {
    Log.i(TAG, "reset requested")
    Handler(Looper.getMainLooper()).post {
      ReplateCameraView.getCurrentInstance()?.resetSession()
    }
  }

  @ReactMethod
  fun pauseSession() {
    Log.i(TAG, "pauseSession requested")
    Handler(Looper.getMainLooper()).post {
      ReplateCameraView.getCurrentInstance()?.pauseSessionPublic()
    }
  }

  @ReactMethod
  fun resumeSession() {
    Log.i(TAG, "resumeSession requested")
    Handler(Looper.getMainLooper()).post {
      ReplateCameraView.getCurrentInstance()?.resumeSessionPublic()
    }
  }

  @ReactMethod
  fun stopSession() {
    Log.i(TAG, "stopSession requested")
    Handler(Looper.getMainLooper()).post {
      ReplateCameraView.getCurrentInstance()?.cleanupResourcesPublic()
    }
  }

  @ReactMethod
  fun cancelUploadsForSender(sender: String, promise: Promise) {
    Log.i(TAG, "cancelUploadsForSender sender=$sender")
    promise.resolve(mapOf("cancelled" to true, "sender" to sender))
  }

  @ReactMethod
  fun finalizeUpload(
    sender: String,
    token: String,
    isGaussian: Boolean,
    hasBackground: Boolean,
    promise: Promise
  ) {
    Log.i(TAG, "finalizeUpload sender=$sender token=$token isGaussian=$isGaussian hasBackground=$hasBackground")
    promise.resolve(
      mapOf(
        "sender" to sender,
        "token" to token,
        "isGaussian" to isGaussian,
        "hasBackground" to hasBackground,
        "finalized" to true
      )
    )
  }
}
