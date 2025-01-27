package com.replatecamera

import com.facebook.react.bridge.*
import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.uimanager.UIManagerHelper
import com.facebook.react.uimanager.common.UIManagerType

@ReactModule(name = ReplateCameraModule.NAME)
class ReplateCameraModule(private val reactContext: ReactApplicationContext) :
  ReactContextBaseJavaModule(reactContext) {

  companion object {
    const val NAME = "ReplateCameraModule"
  }

  override fun getName(): String = NAME

  /**
   * Example: takePhoto(...) from JS, returning a promise that resolves with the file URI or rejects on error.
   *
   * @param viewTag The React tag (integer) of the ReplateCameraView. From JS, you can get this via findNodeHandle(ref).
   */
  @ReactMethod
  fun takePhoto(viewTag: Int, promise: Promise) {
    val cameraView = findCameraViewByTag(viewTag)
    if (cameraView == null) {
      promise.reject("VIEW_NOT_FOUND", "No ReplateCameraView found with tag $viewTag")
      return
    }

    // Call the Kotlin AR method:
    cameraView.takePhoto { uri ->
      if (uri != null) {
        // resolve the promise with the URI string
        promise.resolve(uri.toString())
      } else {
        promise.reject("PHOTO_ERROR", "Failed to capture photo.")
      }
    }
  }

  /**
   * Example: resetSession(...) from JS, no return value needed.
   */
  @ReactMethod
  fun resetSession(viewTag: Int) {
    val cameraView = findCameraViewByTag(viewTag)
    cameraView?.resetSession()
  }

  /**
   * Helper function that looks up the ReplateCameraView instance by its React tag.
   */
  private fun findCameraViewByTag(viewTag: Int): ReplateCameraView? {
    // Use UIManagerHelper to look up the native view associated with 'viewTag'.
    val uiManager = UIManagerHelper.getUIManager(reactContext, UIManagerType.DEFAULT /* 1 => default type for Fabric or not */)
    val nativeView = uiManager?.resolveView(viewTag)
    return nativeView as? ReplateCameraView
  }
}
