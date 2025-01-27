package com.replatecamera

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.ScaleGestureDetector
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.FragmentActivity
import com.google.ar.core.Anchor
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import com.google.ar.sceneform.*
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.Color
import com.google.ar.sceneform.rendering.Material
import com.google.ar.sceneform.rendering.MaterialFactory
import com.google.ar.sceneform.rendering.ShapeFactory
import com.google.ar.sceneform.ux.ArFragment
import com.google.ar.sceneform.ux.TransformableNode
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Kotlin version of an ARCore/Sceneform view, mimicking much of the Swift+RealityKit approach.
 * - Creates spheres in two circles around an anchor.
 * - Allows pan/pinch to move/scale.
 * - Captures screenshots with metadata placeholders.
 * - Tracks gravity vector via SensorManager (TYPE_GRAVITY).
 *
 * Usage:
 * 1) In your Activity/Fragment layout, add a <FrameLayout> that hosts this view.
 * 2) Instantiate ReplateCameraView in code or via XML. The ARFragment is attached dynamically.
 * 3) Call takePhoto() to save a screenshot to external files.
 */
@RequiresApi(Build.VERSION_CODES.N)
class ReplateCameraView @JvmOverloads constructor(
  context: Context,
  attrs: AttributeSet? = null,
  defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr), Scene.OnUpdateListener,
  android.hardware.SensorEventListener {

  companion object {
    private const val TAG = "ReplateCameraView"

    // Number of spheres per circle (mirrors Swift code's 72)
    private const val TOTAL_SPHERES_PER_CIRCLE = 72
    // Each sphere is placed 5 degrees apart => 360/5 = 72
    private const val ANGLE_INCREMENT_DEGREES = 5f

    // Initial defaults that match the Swift code
    private const val DEFAULT_SPHERE_RADIUS = 0.004f
    private const val DEFAULT_SPHERES_RADIUS = 0.13f
    private const val DEFAULT_SPHERES_HEIGHT = 0.10f
    private const val DEFAULT_DISTANCE_BETWEEN_CIRCLES = 0.10f
    private const val DEFAULT_DRAG_SPEED = 7000f
    private const val ANGLE_THRESHOLD = 0.6f  // Radians
    private const val MIN_DISTANCE = 0.15f
    private const val MAX_DISTANCE = 0.65f

    // Track whether each sphere index is "set" (mirroring Swift arrays)
    private val upperSpheresSet = BooleanArray(TOTAL_SPHERES_PER_CIRCLE) { false }
    private val lowerSpheresSet = BooleanArray(TOTAL_SPHERES_PER_CIRCLE) { false }

    // Stats
    var totalPhotosTaken = 0
    var photosFromDifferentAnglesTaken = 0
  }

  // AR Sceneform stuff
  private lateinit var arFragment: ArFragment
  private var anchorNode: AnchorNode? = null

  // Spheres and a "focus node"
  private val spheresModels = mutableListOf<TransformableNode>()
  private var focusNode: Node? = null

  // Scene geometry
  private var sphereRadius = DEFAULT_SPHERE_RADIUS
  private var spheresRadius = DEFAULT_SPHERES_RADIUS
  private var sphereAngle = ANGLE_INCREMENT_DEGREES  // If you want to “scale angle”
  private var spheresHeight = DEFAULT_SPHERES_HEIGHT
  private var distanceBetweenCircles = DEFAULT_DISTANCE_BETWEEN_CIRCLES
  private var circleInFocus = 0 // 0 => lower circle, 1 => upper circle
  private var dragSpeed = DEFAULT_DRAG_SPEED

  // Gravity vector
  private var gravityVector = mutableMapOf<String, Double>()

  // Gesture detectors for custom pan/pinch (replicating Swift approach)
  private var gestureDetector: GestureDetector
  private var scaleDetector: ScaleGestureDetector

  // ------------------------------------------------------------------------
  // Init & Setup
  // ------------------------------------------------------------------------
  init {
    requestCameraPermission()
    setupArFragment()

    gestureDetector = GestureDetector(context, GestureListener())
    scaleDetector = ScaleGestureDetector(context, ScaleListener())

    // Register a gravity sensor to replicate iOS's CMMotionManager
    val sensorManager = context.getSystemService(Context.SENSOR_SERVICE)
      as? android.hardware.SensorManager
    sensorManager?.getDefaultSensor(android.hardware.Sensor.TYPE_GRAVITY)?.also { gravitySensor ->
      sensorManager.registerListener(this, gravitySensor, android.hardware.SensorManager.SENSOR_DELAY_GAME)
    }
  }

  /**
   * If camera permission not granted, request at runtime (for AR use).
   */
  private fun requestCameraPermission() {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
      != PackageManager.PERMISSION_GRANTED) {
      ActivityCompat.requestPermissions(
        context as Activity,
        arrayOf(Manifest.permission.CAMERA),
        0
      )
    }
  }

  /**
   * Programmatically attach an ArFragment into this FrameLayout.
   * We also add ourselves as a Scene update listener.
   */
  @RequiresApi(Build.VERSION_CODES.N)
  private fun setupArFragment() {
    arFragment = ArFragment()
    val fm = (context as FragmentActivity).supportFragmentManager

    fm.beginTransaction().replace(this.id, arFragment).commitAllowingStateLoss()
    // Add an update listener
    arFragment.arSceneView.scene.addOnUpdateListener(this)

    // Listen for plane taps
    arFragment.setOnTapArPlaneListener { hitResult: HitResult, plane: Plane, motionEvent: MotionEvent ->
      if (anchorNode == null) {
        anchorNode = createAnchorNode(hitResult)
        createSpheresAtY(spheresHeight) // Lower circle
        createSpheresAtY(distanceBetweenCircles + spheresHeight) // Upper circle
        createFocusSphere()
      }
    }
  }

  /**
   * Creates and returns an AnchorNode from a tap HitResult.
   */
  private fun createAnchorNode(hitResult: HitResult): AnchorNode {
    val anchor: Anchor = hitResult.createAnchor()
    return AnchorNode(anchor).also { node ->
      node.setParent(arFragment.arSceneView.scene)
    }
  }

  // ------------------------------------------------------------------------
  // Scene OnUpdateListener => replicates Swift ARSessionDelegate
  // ------------------------------------------------------------------------
  override fun onUpdate(frameTime: FrameTime?) {
    val frame = arFragment.arSceneView.arFrame ?: return
    if (frame.camera.trackingState != TrackingState.TRACKING) return

    // If you need per-frame logic, do it here.
    // Example: check lighting, or do real-time distance checks, etc.
  }

  // ------------------------------------------------------------------------
  // Spheres Logic
  // ------------------------------------------------------------------------
  @RequiresApi(Build.VERSION_CODES.N)
  private fun createSpheresAtY(y: Float) {
    for (i in 0 until TOTAL_SPHERES_PER_CIRCLE) {
      val angleRad = Math.toRadians((i * ANGLE_INCREMENT_DEGREES).toDouble()).toFloat()
      val x = spheresRadius * cos(angleRad.toDouble()).toFloat()
      val z = spheresRadius * sin(angleRad.toDouble()).toFloat()
      createSphere(Vector3(x, y, z))
    }
  }

  @RequiresApi(Build.VERSION_CODES.N)
  private fun createSphere(position: Vector3) {
    MaterialFactory.makeOpaqueWithColor(context, Color(android.graphics.Color.WHITE))
      .thenAccept { material: Material ->
        val sphereRenderable = ShapeFactory.makeSphere(sphereRadius, Vector3.zero(), material)
        val sphereNode = TransformableNode(arFragment.transformationSystem)
        sphereNode.renderable = sphereRenderable
        sphereNode.worldPosition = position
        sphereNode.setParent(anchorNode)
        spheresModels.add(sphereNode)
      }
  }

  @RequiresApi(Build.VERSION_CODES.N)
  private fun createFocusSphere() {
    // Parent node for the two green spheres + optional overlay
    val parentNode = Node().apply { setParent(anchorNode) }
    focusNode = parentNode

    // Lower focus sphere
    MaterialFactory.makeOpaqueWithColor(context, Color(0f, 1f, 0f))
      .thenAccept { mat ->
        val r = sphereRadius * 1.5f
        val renderable = ShapeFactory.makeSphere(r, Vector3.zero(), mat)
        val sphereN = Node()
        sphereN.worldPosition = Vector3(0f, spheresHeight, 0f)
        sphereN.renderable = renderable
        parentNode.addChild(sphereN)
      }

    // Upper focus sphere
    MaterialFactory.makeOpaqueWithColor(context, Color(0f, 1f, 0f))
      .thenAccept { mat ->
        val r = sphereRadius * 1.5f
        val renderable = ShapeFactory.makeSphere(r, Vector3.zero(), mat)
        val sphereN = Node()
        sphereN.worldPosition = Vector3(0f, spheresHeight + distanceBetweenCircles, 0f)
        sphereN.renderable = renderable
        parentNode.addChild(sphereN)
      }

    // If you have a custom "center.obj", load via ModelRenderable builder or Filament loader
    // parentNode.addChild(loadedCustomOverlayNode)
  }

  // ------------------------------------------------------------------------
  // Gestures (Mirror Swift's tap, pan, pinch)
  // ------------------------------------------------------------------------
  override fun onTouchEvent(event: MotionEvent): Boolean {
    scaleDetector.onTouchEvent(event)
    gestureDetector.onTouchEvent(event)
    return true
  }

  private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {

    override fun onSingleTapUp(e: MotionEvent): Boolean {
      Log.d(TAG, "Single tap up at x=${e.x}, y=${e.y}")
      // If you want custom logic (like Swift's "viewTapped"), put it here
      return true
    }

    override fun onScroll(
      e1: MotionEvent?,
      e2: MotionEvent,
      distanceX: Float,
      distanceY: Float
    ): Boolean {
      val sceneView = arFragment.arSceneView
      val currentAnchor = anchorNode ?: return true

      // Replicate Swift handlePan => anchorNode position changes based on camera orientation
      val camera = sceneView.scene.camera
      if (e2.action == MotionEvent.ACTION_MOVE && camera != null) {
        val translationX = -distanceX
        val translationY = -distanceY

        // Sceneform camera: forward is -Z, right is +X
        val cameraForward = Vector3(camera.forward.x, 0f, camera.forward.z)
        val cameraRight = Vector3(camera.right.x, 0f, camera.right.z)

        val forwardNorm = normalize(cameraForward)
        val rightNorm = normalize(cameraRight)

        // Similar to Swift: anchorEntity.position = initialPosition + adjustedMovement / dragSpeed
        val adjustedMovement = Vector3(
          translationX * rightNorm.x + translationY * forwardNorm.x,
          0f,
          translationX * rightNorm.z + translationY * forwardNorm.z
        ).scaled(1f / dragSpeed)

        val initialPos = currentAnchor.worldPosition
        currentAnchor.worldPosition = Vector3.add(initialPos, adjustedMovement)
      }
      return true
    }

    private fun normalize(v: Vector3): Vector3 {
      val length = sqrt(v.x * v.x + v.y * v.y + v.z * v.z)
      return if (length > 0.0001f) {
        Vector3(v.x / length, v.y / length, v.z / length)
      } else {
        Vector3.zero()
      }
    }
  }

  private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
    override fun onScale(detector: ScaleGestureDetector): Boolean {
      // Like Swift handlePinch => remove child spheres, scale, recreate
      val currentAnchor = anchorNode ?: return false
      val scale = detector.scaleFactor

      // Remove all existing spheres
      spheresModels.forEach { node -> currentAnchor.removeChild(node) }
      spheresModels.clear()

      // Remove focus
      focusNode?.let { currentAnchor.removeChild(it) }
      focusNode = null

      // Scale the radii
      sphereRadius *= scale
      spheresRadius *= scale
      sphereAngle *= scale  // if also scaling the angle
      // Rebuild
      createSpheresAtY(spheresHeight)
      createSpheresAtY(distanceBetweenCircles + spheresHeight)
      createFocusSphere()

      return true
    }
  }

  // ------------------------------------------------------------------------
  // Photo Capture Implementation (PixelCopy)
  // ------------------------------------------------------------------------
  /**
   * Takes a screenshot of the ArSceneView and embeds the current gravityVector
   * as a JSON string in the EXIF “UserComment” field.
   *
   * @param onPhotoSaved Callback that gives you the Uri of the saved file (or null on failure).
   */
  fun takePhoto(onPhotoSaved: (Uri?) -> Unit = {}) {
    val sceneView: ArSceneView = arFragment.arSceneView
    val bitmap = Bitmap.createBitmap(sceneView.width, sceneView.height, Bitmap.Config.ARGB_8888)

    PixelCopy.request(sceneView, bitmap, { copyResult ->
      if (copyResult == PixelCopy.SUCCESS) {
        // Save + embed EXIF
        val savedUri = saveBitmapWithExif(bitmap, gravityVector)
        if (savedUri != null) {
          totalPhotosTaken += 1
          Log.d(TAG, "Photo saved to: $savedUri")
          onPhotoSaved(savedUri)
        } else {
          Log.e(TAG, "Failed to save photo.")
          onPhotoSaved(null)
        }
      } else {
        Log.e(TAG, "PixelCopy failed with code $copyResult")
        onPhotoSaved(null)
      }
    }, Handler(Looper.getMainLooper()))
  }

  /**
   * Save the [bitmap] into JPEG. Then embed an EXIF “UserComment” that includes the gravity vector.
   */
  private fun saveBitmapWithExif(
    bitmap: Bitmap,
    gravity: Map<String, Double>
  ): Uri? {
    val picturesDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: return null
    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
    val file = File(picturesDir, "AR_Capture_$timeStamp.jpg")

    return try {
      // Write JPEG
      FileOutputStream(file).use { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
        out.flush()
      }

      // Now embed EXIF metadata
      val exif = ExifInterface(file.absolutePath)
      // Convert gravity vector to JSON-like string
      val gx = gravity["x"] ?: 0.0
      val gy = gravity["y"] ?: 0.0
      val gz = gravity["z"] ?: 0.0

      // Example JSON
      // { "gravity": { "x":0.123, "y":-0.456, "z":9.81 } }
      val gravityJson = """{ "gravity": { "x": $gx, "y": $gy, "z": $gz } }"""

      exif.setAttribute(ExifInterface.TAG_USER_COMMENT, gravityJson)
      // Save EXIF
      exif.saveAttributes()

      Uri.fromFile(file)
    } catch (e: Exception) {
      Log.e(TAG, "Error saving or updating EXIF: ${e.message}")
      null
    }
  }

  // ------------------------------------------------------------------------
  // Gravity Sensor => Replicates CMMotionManager
  // ------------------------------------------------------------------------
  override fun onSensorChanged(event: android.hardware.SensorEvent?) {
    if (event?.sensor?.type == android.hardware.Sensor.TYPE_GRAVITY) {
      gravityVector["x"] = event.values[0].toDouble()
      gravityVector["y"] = event.values[1].toDouble()
      gravityVector["z"] = event.values[2].toDouble()
      Log.d(TAG, "Gravity vector => x=${gravityVector["x"]}, y=${gravityVector["y"]}, z=${gravityVector["z"]}")
    }
  }

  override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {
    // Not used
  }

  // ------------------------------------------------------------------------
  // Reset Logic
  // ------------------------------------------------------------------------
  /**
   * Resets the AR session, removing anchors and restoring default geometry.
   */
  fun resetSession() {
    anchorNode?.let { node ->
      node.children.forEach { child ->
        node.removeChild(child)
      }
      arFragment.arSceneView.scene.removeChild(node)
    }
    anchorNode = null
    spheresModels.clear()
    focusNode = null

    // Reset booleans
    for (i in upperSpheresSet.indices) {
      upperSpheresSet[i] = false
      lowerSpheresSet[i] = false
    }
    totalPhotosTaken = 0
    photosFromDifferentAnglesTaken = 0

    // Reset geometry
    sphereRadius = DEFAULT_SPHERE_RADIUS
    spheresRadius = DEFAULT_SPHERES_RADIUS
    sphereAngle = ANGLE_INCREMENT_DEGREES
    spheresHeight = DEFAULT_SPHERES_HEIGHT
    distanceBetweenCircles = DEFAULT_DISTANCE_BETWEEN_CIRCLES
    circleInFocus = 0
    dragSpeed = DEFAULT_DRAG_SPEED

    // If you want to fully re-init the AR session, you could do:
    // arFragment.arSceneView.session?.close()
    // setupArFragment()
    // But that may require re-attaching the fragment, so it depends on your usage.
  }
}
