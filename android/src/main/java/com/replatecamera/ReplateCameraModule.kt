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

  @ReactMethod
  fun takePhoto(viewTag: Int, unlimited: Boolean, promise: Promise) {
    val cameraView = findCameraViewByTag(viewTag)
    if (cameraView == null) {
      promise.reject("VIEW_NOT_FOUND", "No ReplateCameraView found with tag $viewTag")
      return
    }
    val controller = cameraView.getCameraController()
    controller.takePhoto(unlimited, promise)
  }

  @ReactMethod
  fun getPhotosCount(viewTag: Int, promise: Promise) {
    val cameraView = findCameraViewByTag(viewTag)
    if (cameraView == null) {
      promise.reject("VIEW_NOT_FOUND", "No ReplateCameraView found with tag $viewTag")
      return
    }
    val controller = cameraView.getCameraController()
    controller.getPhotosCount(promise)
  }

  @ReactMethod
  fun isScanComplete(viewTag: Int, promise: Promise) {
    val cameraView = findCameraViewByTag(viewTag)
    if (cameraView == null) {
      promise.reject("VIEW_NOT_FOUND", "No ReplateCameraView found with tag $viewTag")
      return
    }
    val controller = cameraView.getCameraController()
    controller.isScanComplete(promise)
  }

  @ReactMethod
  fun getRemainingAnglesToScan(viewTag: Int, promise: Promise) {
    val cameraView = findCameraViewByTag(viewTag)
    if (cameraView == null) {
      promise.reject("VIEW_NOT_FOUND", "No ReplateCameraView found with tag $viewTag")
      return
    }
    val controller = cameraView.getCameraController()
    controller.getRemainingAnglesToScan(promise)
  }

  @ReactMethod
  fun getMemoryUsage(viewTag: Int, promise: Promise) {
    val cameraView = findCameraViewByTag(viewTag)
    if (cameraView == null) {
      promise.reject("VIEW_NOT_FOUND", "No ReplateCameraView found with tag $viewTag")
      return
    }
    val controller = cameraView.getCameraController()
    controller.getMemoryUsage(promise)
  }

  @ReactMethod
  fun resetSession(viewTag: Int) {
    val cameraView = findCameraViewByTag(viewTag)
    cameraView?.resetSession()
  }

  @ReactMethod
  fun pauseSession(viewTag: Int) {
    val cameraView = findCameraViewByTag(viewTag)
    cameraView?.pauseSession()
  }

  @ReactMethod
  fun resumeSession(viewTag: Int) {
    val cameraView = findCameraViewByTag(viewTag)
    cameraView?.resumeSession()
  }

  @ReactMethod
  fun stopSession(viewTag: Int) {
    val cameraView = findCameraViewByTag(viewTag)
    cameraView?.cleanupResources()
  }

  private fun findCameraViewByTag(viewTag: Int): ReplateCameraView? {
    val uiManager = UIManagerHelper.getUIManager(reactContext, UIManagerType.DEFAULT)
    val nativeView = uiManager?.resolveView(viewTag)
    return nativeView as? ReplateCameraView
  }

  override fun getExportedCustomDirectEventTypeConstants(): MutableMap<String, Any> {
    return mutableMapOf(
      "onAnchorSet" to mutableMapOf("registrationName" to "onAnchorSet"),
      "onTooClose" to mutableMapOf("registrationName" to "onTooClose"),
      "onTooFar" to mutableMapOf("registrationName" to "onTooFar"),
      "onBackInRange" to mutableMapOf("registrationName" to "onBackInRange"),
      "onOpenedTutorial" to mutableMapOf("registrationName" to "onOpenedTutorial"),
      "onCompletedTutorial" to mutableMapOf("registrationName" to "onCompletedTutorial"),
      "onCompletedUpperSpheres" to mutableMapOf("registrationName" to "onCompletedUpperSpheres"),
      "onCompletedLowerSpheres" to mutableMapOf("registrationName" to "onCompletedLowerSpheres")
    )
  }
}
