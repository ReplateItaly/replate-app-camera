package com.replatecamera

import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.annotations.ReactProp

class ReplateCameraViewManager : SimpleViewManager<ReplateCameraView>() {
  override fun getName() = "ReplateCameraView"

  @RequiresApi(Build.VERSION_CODES.N)
  override fun createViewInstance(reactContext: ThemedReactContext): ReplateCameraView {
    return ReplateCameraView(reactContext)
  }

  @ReactProp(name = "color")
  fun setColor(view: View, color: String) {
    // Keep prop for JS compatibility, but do not tint the AR container.
    // iOS ignores this prop and applying it on Android can hide camera feed with a flat color.
    view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
  }

}
