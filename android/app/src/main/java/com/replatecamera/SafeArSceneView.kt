package com.replatecamera

import android.content.Context
import android.media.Image
import android.util.AttributeSet
import android.util.Log
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.Trackable
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.DeadlineExceededException
import com.google.ar.core.exceptions.FatalException
import com.google.ar.core.exceptions.MissingGlContextException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.sceneform.ArSceneView
import com.google.ar.sceneform.rendering.CameraStream
import com.google.ar.sceneform.rendering.DepthTexture
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Workaround for Sceneform/Filament depth occlusion crashes:
 * - Clamps the depth plane ByteBuffer to tightly-packed size (w*h*pixelStride).
 * - Forces Sceneform to recreate DepthTexture when depth image dimensions change.
 *
 * This mirrors ArSceneView.onBeginFrame(...) closely, with added safety hooks.
 */
class SafeArSceneView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null
) : ArSceneView(context, attrs) {

  companion object {
    private const val TAG = "SafeArSceneView"

    private val fieldSession by lazy {
      ArSceneView::class.java.getDeclaredField("session").apply { isAccessible = true }
    }
    private val fieldPauseResumeTask by lazy {
      ArSceneView::class.java.getDeclaredField("pauseResumeTask").apply { isAccessible = true }
    }
    private val fieldHasSetTextureNames by lazy {
      ArSceneView::class.java.getDeclaredField("hasSetTextureNames").apply { isAccessible = true }
    }
    private val fieldIsProcessingFrame by lazy {
      ArSceneView::class.java.getDeclaredField("isProcessingFrame").apply { isAccessible = true }
    }
    private val fieldCurrentFrame by lazy {
      ArSceneView::class.java.getDeclaredField("currentFrame").apply { isAccessible = true }
    }
    private val fieldCurrentFrameTimestamp by lazy {
      ArSceneView::class.java.getDeclaredField("currentFrameTimestamp").apply { isAccessible = true }
    }
    private val fieldAllTrackables by lazy {
      ArSceneView::class.java.getDeclaredField("allTrackables").apply { isAccessible = true }
    }
    private val fieldUpdatedTrackables by lazy {
      ArSceneView::class.java.getDeclaredField("updatedTrackables").apply { isAccessible = true }
    }

    private val fieldCameraStreamDepthTexture by lazy {
      CameraStream::class.java.getDeclaredField("depthTexture").apply { isAccessible = true }
    }
  }

  override fun onBeginFrame(frameTimeNanos: Long): Boolean {
    val processingFlag = (fieldIsProcessingFrame.get(this) as? AtomicBoolean) ?: return false
    if (processingFlag.get()) return false
    processingFlag.set(true)

    try {
      val session = (fieldSession.get(this) as? Session) ?: return false
      val pauseResumeTask = fieldPauseResumeTask.get(this) ?: return false
      if (!isPauseResumeTaskDone(pauseResumeTask)) return false

      var shouldUpdate = true

      if (!fieldHasSetTextureNames.getBoolean(this)) {
        // Texture name 0 is invalid for ARCore → avoid IllegalArgumentException
        if (cameraTextureId == 0) return false
        try {
          session.setCameraTextureName(cameraTextureId)
          fieldHasSetTextureNames.setBoolean(this, true)
        } catch (e: IllegalArgumentException) {
          Log.w(TAG, "Illegal argument while setting ARCore texture name", e)
          return false
        } catch (e: IllegalStateException) {
          Log.w(TAG, "Illegal state while setting ARCore texture name", e)
          return false
        } catch (t: Throwable) {
          Log.w(TAG, "Unexpected error while setting ARCore texture name", t)
          return false
        }
      }

      val frame: Frame = try {
        session.update()
      } catch (e: CameraNotAvailableException) {
        Log.w(TAG, "Exception updating ARCore session", e)
        return false
      } catch (e: MissingGlContextException) {
        Log.w(TAG, "Missing GL context while updating ARCore session", e)
        return false
      } catch (e: DeadlineExceededException) {
        Log.w(TAG, "Exception updating ARCore session", e)
        return false
      } catch (e: FatalException) {
        Log.w(TAG, "Exception updating ARCore session", e)
        return false
      } catch (e: IllegalArgumentException) {
        Log.w(TAG, "Illegal argument while updating ARCore session", e)
        return false
      } catch (e: IllegalStateException) {
        Log.w(TAG, "Illegal state while updating ARCore session", e)
        return false
      } catch (t: Throwable) {
        Log.w(TAG, "Unexpected error updating ARCore session", t)
        return false
      }

      val lastTimestamp = (fieldCurrentFrameTimestamp.get(this) as? Long) ?: 0L
      if (lastTimestamp == frame.timestamp) {
        shouldUpdate = false
      }

      fieldCurrentFrame.set(this, frame)
      fieldCurrentFrameTimestamp.set(this, frame.timestamp)

      val camera = frame.camera

      val cameraStream = cameraStream
      if (!cameraStream.isTextureInitialized) {
        cameraStream.initializeTexture(frame)
      }
      if (frame.hasDisplayGeometryChanged()) {
        cameraStream.recalculateCameraUvs(frame)
      }

      if (shouldUpdate) {
        fieldAllTrackables.set(this, session.getAllTrackables(Trackable::class.java))
        fieldUpdatedTrackables.set(this, frame.getUpdatedTrackables(Trackable::class.java))

        scene.camera.updateTrackedPose(camera)

        if (
          cameraStream.depthOcclusionMode ==
            CameraStream.DepthOcclusionMode.DEPTH_OCCLUSION_ENABLED
        ) {
          when (cameraStream.depthMode) {
            CameraStream.DepthMode.DEPTH -> {
              try {
                val depthImage = frame.acquireDepthImage()
                try {
                  ensureDepthTextureSizeMatchesImage(cameraStream, depthImage)
                  withClampedDepthBuffer(depthImage) {
                    cameraStream.recalculateOcclusion(depthImage)
                  }
                } finally {
                  depthImage.close()
                }
              } catch (_: NotYetAvailableException) {
                // ignore
              } catch (_: DeadlineExceededException) {
                // ignore
              }
            }

            CameraStream.DepthMode.RAW_DEPTH -> {
              try {
                val depthImage = frame.acquireRawDepthImage()
                try {
                  ensureDepthTextureSizeMatchesImage(cameraStream, depthImage)
                  withClampedDepthBuffer(depthImage) {
                    cameraStream.recalculateOcclusion(depthImage)
                  }
                } finally {
                  depthImage.close()
                }
              } catch (_: NotYetAvailableException) {
                // ignore
              } catch (_: DeadlineExceededException) {
                // ignore
              }
            }

            else -> {
              // NO_DEPTH
            }
          }
        }

        try {
          val planeRenderer = planeRenderer
          if (planeRenderer != null && planeRenderer.isEnabled) {
            planeRenderer.update(frame, updatedPlanes, width, height)
          }
        } catch (_: DeadlineExceededException) {
          // ignore
        } catch (t: Throwable) {
          Log.w(TAG, "Plane renderer update failed", t)
        }
      }

      return shouldUpdate
    } finally {
      processingFlag.set(false)
    }
  }

  private fun isPauseResumeTaskDone(task: Any): Boolean {
    return try {
      val m = task.javaClass.getMethod("isDone")
      (m.invoke(task) as? Boolean) == true
    } catch (_: Throwable) {
      false
    }
  }

  private fun clampDepthPlaneBuffer(depthImage: Image) {
    val plane = depthImage.planes.firstOrNull() ?: return
    val buffer: ByteBuffer = plane.buffer ?: return
    val expectedLong = depthImage.width.toLong() * depthImage.height.toLong() * plane.pixelStride.toLong()
    if (expectedLong <= 0L || expectedLong > Int.MAX_VALUE.toLong()) return
    val expected = expectedLong.toInt()
    if (expected in 1..buffer.capacity()) {
      if (buffer.limit() > expected) buffer.limit(expected)
      buffer.rewind()
    }
  }

  private inline fun <T> withClampedDepthBuffer(image: Image, block: () -> T): T {
    val buffer = image.planes.firstOrNull()?.buffer
    val oldPos = buffer?.position()
    val oldLimit = buffer?.limit()
    try {
      clampDepthPlaneBuffer(image)
      return block()
    } finally {
      if (buffer != null && oldLimit != null) buffer.limit(oldLimit)
      if (buffer != null && oldPos != null) buffer.position(oldPos)
    }
  }

  private fun getDepthTexture(cameraStream: CameraStream): DepthTexture? {
    return try {
      fieldCameraStreamDepthTexture.get(cameraStream) as? DepthTexture
    } catch (_: Throwable) {
      null
    }
  }

  private fun clearDepthTexture(cameraStream: CameraStream) {
    try {
      fieldCameraStreamDepthTexture.set(cameraStream, null)
    } catch (_: Throwable) {
      // ignore
    }
  }

  private fun ensureDepthTextureSizeMatchesImage(cameraStream: CameraStream, depthImage: Image) {
    val dt = getDepthTexture(cameraStream) ?: return
    val tex = try { dt.filamentTexture } catch (_: Throwable) { null } ?: return
    val w = try { tex.getWidth(0) } catch (_: Throwable) { return }
    val h = try { tex.getHeight(0) } catch (_: Throwable) { return }
    if (w != depthImage.width || h != depthImage.height) {
      clearDepthTexture(cameraStream)
    }
  }
}
