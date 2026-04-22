package com.replatecamera

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.google.ar.sceneform.ArSceneView
import com.google.ar.sceneform.ux.ArFragment
import com.google.ar.sceneform.ux.BaseArFragment
import com.google.ar.sceneform.ux.R

class ReplateArFragment : ArFragment() {

  companion object {
    private const val TAG = "ReplateArFragment"
  }

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?
  ): View {
    val root = requireNotNull(super.onCreateView(inflater, container, savedInstanceState))
    trySwapArSceneView(root)
    return root
  }

  private fun trySwapArSceneView(root: View) {
    try {
      val oldView = root.findViewById<ArSceneView>(R.id.sceneform_ar_scene_view) ?: return
      val parent = oldView.parent as? ViewGroup ?: return
      val index = parent.indexOfChild(oldView)

      val newView = SafeArSceneView(requireContext()).apply {
        id = oldView.id
        layoutParams = oldView.layoutParams
      }

      // Move BaseArFragment listeners from old Scene to new Scene
      try {
        oldView.scene.removeOnPeekTouchListener(this)
        oldView.scene.removeOnUpdateListener(this)
      } catch (_: Throwable) {
        // ignore
      }

      parent.removeView(oldView)
      parent.addView(newView, index)

      // Update BaseArFragment's private arSceneView field so all future calls use SafeArSceneView
      try {
        val f = BaseArFragment::class.java.getDeclaredField("arSceneView").apply { isAccessible = true }
        f.set(this, newView)
      } catch (t: Throwable) {
        Log.w(TAG, "Failed to patch BaseArFragment.arSceneView", t)
      }

      // Re-register the session config change hook (was attached to oldView in BaseArFragment.onCreateView)
      try {
        newView.setOnSessionConfigChangeListener { config ->
          onSessionConfigChanged(config)
        }
      } catch (t: Throwable) {
        Log.w(TAG, "Failed to set OnSessionConfigChangeListener", t)
      }

      // Move the window focus listener so BaseArFragment keeps receiving focus changes
      try {
        val f = BaseArFragment::class.java.getDeclaredField("onFocusListener").apply { isAccessible = true }
        val focusListener = f.get(this) as? ViewTreeObserver.OnWindowFocusChangeListener
        if (focusListener != null) {
          try {
            oldView.viewTreeObserver.removeOnWindowFocusChangeListener(focusListener)
          } catch (_: Throwable) {
            // ignore
          }
          newView.viewTreeObserver.addOnWindowFocusChangeListener(focusListener)
        }
      } catch (t: Throwable) {
        Log.w(TAG, "Failed to move onFocusListener", t)
      }

      // Re-attach BaseArFragment touch/update listeners to the new scene.
      try {
        newView.scene.addOnPeekTouchListener(this)
        newView.scene.addOnUpdateListener(this)
      } catch (t: Throwable) {
        Log.w(TAG, "Failed to reattach scene listeners", t)
      }
    } catch (t: Throwable) {
      Log.w(TAG, "Failed to swap ArSceneView with SafeArSceneView", t)
    }
  }
}
