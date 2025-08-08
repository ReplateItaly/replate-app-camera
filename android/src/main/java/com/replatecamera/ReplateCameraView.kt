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
import android.os.PowerManager
import android.os.SystemClock
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
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.ar.core.Anchor
import com.google.ar.core.Session
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
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.events.RCTEventEmitter
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
  android.hardware.SensorEventListener, DefaultLifecycleObserver {

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

    // Overheating prevention constants
    const val MAX_CONTINUOUS_SESSION_TIME = 30 * 60 * 1000L // 30 minutes
    const val THERMAL_BREAK_DURATION = 5 * 60 * 1000L // 5 minutes
  }

  // AR Sceneform stuff
  private lateinit var arFragment: ArFragment
  private var anchorNode: AnchorNode? = null
  private var isSessionPaused = false
  private var isViewAttached = false
  private var sensorManager: android.hardware.SensorManager? = null
  private var gravitySensor: android.hardware.Sensor? = null
  private var powerManager: PowerManager? = null
  private var lastSessionStartTime = 0L
  private var sessionTimeoutHandler: Handler? = null
  private var sessionTimeoutRunnable: Runnable? = null

  // Spheres and a "focus node"
  private val spheresModels = mutableListOf<TransformableNode>()
  private var focusNode: FocusNode? = null

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

  // Callback registration
  private var wasOutOfRange = false

  // Gesture detectors for custom pan/pinch (replicating Swift approach)
  private var gestureDetector: GestureDetector
  private var scaleDetector: ScaleGestureDetector

  // Controller
  private lateinit var cameraController: ReplateCameraController

  // ------------------------------------------------------------------------
  // Init & Setup
  // ------------------------------------------------------------------------
  init {
    requestCameraPermission()
    setupArFragment()

    gestureDetector = GestureDetector(context, GestureListener())
    scaleDetector = ScaleGestureDetector(context, ScaleListener())

    // Register a gravity sensor to replicate iOS's CMMotionManager
    sensorManager = context.getSystemService(Context.SENSOR_SERVICE)
      as? android.hardware.SensorManager
    gravitySensor = sensorManager?.getDefaultSensor(android.hardware.Sensor.TYPE_GRAVITY)
    
    // Register for lifecycle events
    ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    
    // Initialize power manager for thermal monitoring
    powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    
    // Initialize session timeout handler
    sessionTimeoutHandler = Handler(Looper.getMainLooper())
    
    // Register sensor when view is ready
    registerSensorListener()
    isViewAttached = true
    
    // Record session start time
    lastSessionStartTime = SystemClock.elapsedRealtime()
    
    // Start session timeout monitoring
    startSessionTimeoutMonitoring()
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

    // Initialize the controller
    cameraController = ReplateCameraController(context, arFragment.arSceneView, this)

    // Listen for plane taps
    arFragment.arSceneView.planeRenderer.isEnabled = true
    sendEvent("onOpenedTutorial")

    focusNode = FocusNode(context, arFragment)
    arFragment.arSceneView.scene.addChild(focusNode)

    arFragment.setOnTapArPlaneListener { hitResult: HitResult, plane: Plane, motionEvent: MotionEvent ->
      if (anchorNode == null) {
        anchorNode = createAnchorNode(hitResult)
        createSpheresAtY(spheresHeight) // Lower circle
        createSpheresAtY(distanceBetweenCircles + spheresHeight) // Upper circle
        sendEvent("onAnchorSet")
        arFragment.arSceneView.planeRenderer.isEnabled = false
        sendEvent("onCompletedTutorial")
        focusNode?.isEnabled = false
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

    // Check thermal state periodically to prevent overheating
    checkThermalState()

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


  // ------------------------------------------------------------------------
  // Gestures (Mirror Swift's tap, pan, pinch)
  // ------------------------------------------------------------------------
  override fun onTouchEvent(event: MotionEvent): Boolean {
    scaleDetector.onTouchEvent(event)
    gestureDetector.onTouchEvent(event)
    return true
  }

  private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
    private var lastTapTime: Long = 0

    override fun onSingleTapUp(e: MotionEvent): Boolean {
      val currentTime = System.currentTimeMillis()
      if (currentTime - lastTapTime < 500) { // 500ms debounce
        return true
      }
      lastTapTime = currentTime

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
      val currentAnchor = anchorNode ?: return false
      val scaleFactor = detector.scaleFactor
      val newScale = Vector3.add(currentAnchor.localScale, Vector3(scaleFactor - 1, scaleFactor - 1, scaleFactor - 1))
      currentAnchor.localScale = newScale
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
  fun takePhoto(onPhotoAvailable: (Bitmap?) -> Unit) {
    val sceneView = arFragment.arSceneView
    val bitmap = Bitmap.createBitmap(sceneView.width, sceneView.height, Bitmap.Config.ARGB_8888)
    val handlerThread = Handler()

    PixelCopy.request(sceneView, bitmap, { copyResult ->
      if (copyResult == PixelCopy.SUCCESS) {
        onPhotoAvailable(bitmap)
      } else {
        Log.e(TAG, "Failed to copy bitmap: $copyResult")
        onPhotoAvailable(null)
      }
    }, handlerThread)
  }

  fun getAnchorNode(): AnchorNode? {
    return anchorNode
  }

  fun getCameraController(): ReplateCameraController {
    return cameraController
  }

  fun getGravityVector(): Map<String, Double> {
    return gravityVector
  }

  fun updateCircleFocus(targetIndex: Int) {
    if (targetIndex != circleInFocus) {
        setOpacityToCircle(circleInFocus, 0.5f)
        setOpacityToCircle(targetIndex, 1.0f)
        circleInFocus = targetIndex
        performHapticFeedback()
    }
  }

  private fun performHapticFeedback() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
        vibrator.vibrate(android.os.VibrationEffect.createOneShot(50, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
    }
  }

  private fun setOpacityToCircle(circleId: Int, opacity: Float) {
    val start = if (circleId == 0) 0 else TOTAL_SPHERES_PER_CIRCLE
    val end = start + TOTAL_SPHERES_PER_CIRCLE
    for (i in start until end) {
        if (i < spheresModels.size) {
            val node = spheresModels[i]
            val material = node.renderable?.material?.makeCopy()
            if (material != null) {
                val color = Color(1f, 1f, 1f)
                material.setFloat4("color", color.r, color.g, color.b, opacity)
                node.renderable?.material = material
            }
        }
    }
  }

  fun checkCameraDistance(deviceTargetInfo: DeviceTargetInfo): Boolean {
    val distance = isCameraWithinRange(deviceTargetInfo.transform, anchorNode!!)

    when (distance) {
        1 -> {
            wasOutOfRange = true
            sendEvent("onTooFar")
            return false
        }
        -1 -> {
            wasOutOfRange = true
            sendEvent("onTooClose")
            return false
        }
        else -> {
            if (wasOutOfRange) {
                wasOutOfRange = false
                sendEvent("onBackInRange")
            }
            return true
        }
    }
  }

  private fun isCameraWithinRange(cameraTransform: com.google.ar.core.Pose, anchorNode: AnchorNode): Int {
    val cameraPosition = Vector3(cameraTransform.tx(), cameraTransform.ty(), cameraTransform.tz())
    val anchorPosition = anchorNode.worldPosition
    val distance = Vector3.subtract(cameraPosition, anchorPosition).length()

    return when {
        distance <= MIN_DISTANCE -> -1
        distance >= MAX_DISTANCE -> 1
        else -> 0
    }
  }

  private fun sendEvent(eventName: String, params: WritableMap? = null) {
    val reactContext = context as ThemedReactContext
    val event = Arguments.createMap().apply {
        params?.let { putMap("data", it) }
    }
    reactContext.getJSModule(RCTEventEmitter::class.java)?.receiveEvent(id, eventName, event)
  }

  fun updateSpheres(deviceTargetInfo: DeviceTargetInfo, camera: com.google.ar.core.Camera, callback: (Boolean) -> Unit) {
    val angleDegrees = angleBetweenAnchorXAndCamera(anchorNode!!, camera.pose)
    val sphereIndex = (round(angleDegrees / 5.0f) % 72).toInt()

    var newAngle = false
    if (deviceTargetInfo.targetIndex == 1) { // Upper circle
        if (!upperSpheresSet[sphereIndex]) {
            upperSpheresSet[sphereIndex] = true
            photosFromDifferentAnglesTaken++
            newAngle = true
            updateSphereColor(72 + sphereIndex, Color(0f, 1f, 0f)) // Green
            if (upperSpheresSet.all { it }) {
                sendEvent("onCompletedUpperSpheres")
            }
        }
    } else { // Lower circle
        if (!lowerSpheresSet[sphereIndex]) {
            lowerSpheresSet[sphereIndex] = true
            photosFromDifferentAnglesTaken++
            newAngle = true
            updateSphereColor(sphereIndex, Color(0f, 1f, 0f)) // Green
            if (lowerSpheresSet.all { it }) {
                sendEvent("onCompletedLowerSpheres")
            }
        }
    }

    if (newAngle) {
        performHapticFeedback()
    }
    callback(newAngle)
  }

  private fun updateSphereColor(index: Int, color: Color) {
    if (index < spheresModels.size) {
        val node = spheresModels[index]
        val material = node.renderable?.material?.makeCopy()
        material?.setFloat4("color", color)
        node.renderable?.material = material
    }
  }

  private fun angleBetweenAnchorXAndCamera(anchor: AnchorNode, cameraTransform: com.google.ar.core.Pose): Float {
    val anchorTransform = anchor.anchor?.pose ?: return 0f
    val anchorPositionXZ = Vector3(anchorTransform.tx(), 0f, anchorTransform.tz())
    val cameraPositionXZ = Vector3(cameraTransform.tx(), 0f, cameraTransform.tz())

    val directionXZ = Vector3.subtract(cameraPositionXZ, anchorPositionXZ)
    val anchorXAxisXZ = anchor.right.let { Vector3(it.x, 0f, it.z) }

    var angle = atan2(directionXZ.z, directionXZ.x) - atan2(anchorXAxisXZ.z, anchorXAxisXZ.x)
    angle = Math.toDegrees(angle.toDouble()).toFloat()
    if (angle < 0) {
        angle += 360
    }
    return angle
  }

  fun saveBitmap(bitmap: Bitmap): File {
    val photoDir = File(context.getExternalFilesDir(null), "photos")
    if (!photoDir.exists()) {
      photoDir.mkdirs()
    }
    val photoFile = File(photoDir, "replate_${System.currentTimeMillis()}.jpg")
    FileOutputStream(photoFile).use { out ->
      bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
    }
    return photoFile
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
  // Lifecycle Management
  // ------------------------------------------------------------------------
  
  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    isViewAttached = true
    if (isSessionPaused) {
      resumeSession()
    }
  }
  
  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    isViewAttached = false
    pauseSession()
    cleanupResources()
  }
  
  override fun onStart(owner: LifecycleOwner) {
    if (isViewAttached) {
      resumeSession()
    }
  }
  
  override fun onStop(owner: LifecycleOwner) {
    pauseSession()
  }
  
  override fun onDestroy(owner: LifecycleOwner) {
    cleanupResources()
    ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
  }
  
  /**
   * Pauses the AR session and unregisters sensors to prevent overheating
   */
  private fun pauseSession() {
    if (isSessionPaused) return
    
    try {
      // Pause AR session
      arFragment.arSceneView.session?.pause()
      
      // Unregister sensor listener
      unregisterSensorListener()
      
      // Remove scene update listener
      arFragment.arSceneView.scene.removeOnUpdateListener(this)
      
      isSessionPaused = true
      Log.d(TAG, "AR session paused")
    } catch (e: Exception) {
      Log.e(TAG, "Error pausing AR session: ${e.message}")
    }
  }
  
  /**
   * Resumes the AR session and re-registers sensors
   */
  private fun resumeSession() {
    if (!isSessionPaused) return
    
    try {
      // Resume AR session
      arFragment.arSceneView.session?.resume()
      
      // Re-register sensor listener
      registerSensorListener()
      
      // Re-add scene update listener
      arFragment.arSceneView.scene.addOnUpdateListener(this)
      
      isSessionPaused = false
      Log.d(TAG, "AR session resumed")
    } catch (e: Exception) {
      Log.e(TAG, "Error resuming AR session: ${e.message}")
    }
  }
  
  /**
   * Registers the gravity sensor listener
   */
  private fun registerSensorListener() {
    try {
      gravitySensor?.let { sensor ->
        sensorManager?.registerListener(
          this, 
          sensor, 
          android.hardware.SensorManager.SENSOR_DELAY_GAME
        )
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error registering sensor listener: ${e.message}")
    }
  }
  
  /**
   * Unregisters the gravity sensor listener
   */
  private fun unregisterSensorListener() {
    try {
      sensorManager?.unregisterListener(this)
    } catch (e: Exception) {
      Log.e(TAG, "Error unregistering sensor listener: ${e.message}")
    }
  }
  
  /**
   * Cleans up all resources including AR session, sensors, and nodes
   */
  private fun cleanupResources() {
    try {
      // Stop session timeout monitoring
      stopSessionTimeoutMonitoring()
      
      // Clean up AR session
      arFragment.arSceneView.session?.close()
      
      // Unregister sensor listener
      unregisterSensorListener()
      
      // Remove scene listener
      arFragment.arSceneView.scene.removeOnUpdateListener(this)
      
      // Clean up nodes
      anchorNode?.let { node ->
        node.children.forEach { child ->
          node.removeChild(child)
        }
        arFragment.arSceneView.scene.removeChild(node)
      }
      
      // Clear references
      anchorNode = null
      spheresModels.clear()
      focusNode = null
      
      Log.d(TAG, "Resources cleaned up")
    } catch (e: Exception) {
      Log.e(TAG, "Error cleaning up resources: ${e.message}")
    }
  }
  
  /**
   * Starts monitoring session timeout to prevent overheating
   */
  private fun startSessionTimeoutMonitoring() {
    sessionTimeoutRunnable = Runnable {
      Log.w(TAG, "Session timeout reached, pausing to prevent overheating")
      pauseSession()
      
      // Schedule resume after thermal break
      sessionTimeoutHandler?.postDelayed({
        Log.i(TAG, "Thermal break complete, resuming session")
        lastSessionStartTime = SystemClock.elapsedRealtime()
        resumeSession()
        startSessionTimeoutMonitoring()
      }, THERMAL_BREAK_DURATION)
    }
    
    sessionTimeoutHandler?.postDelayed(
      sessionTimeoutRunnable!!, 
      MAX_CONTINUOUS_SESSION_TIME
    )
  }
  
  /**
   * Stops session timeout monitoring
   */
  private fun stopSessionTimeoutMonitoring() {
    sessionTimeoutRunnable?.let { runnable ->
      sessionTimeoutHandler?.removeCallbacks(runnable)
    }
    sessionTimeoutRunnable = null
  }
  
  /**
   * Checks if device is in thermal state and pauses session if needed
   */
  private fun checkThermalState() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      powerManager?.let { pm ->
        val thermalStatus = pm.currentThermalStatus
        when (thermalStatus) {
          PowerManager.THERMAL_STATUS_MODERATE,
          PowerManager.THERMAL_STATUS_SEVERE,
          PowerManager.THERMAL_STATUS_CRITICAL,
          PowerManager.THERMAL_STATUS_EMERGENCY,
          PowerManager.THERMAL_STATUS_SHUTDOWN -> {
            Log.w(TAG, "Device thermal status: $thermalStatus, pausing AR session")
            pauseSession()
          }
          else -> {
            // Device is cool, safe to continue
            if (isSessionPaused && isViewAttached) {
              resumeSession()
            }
          }
        }
      }
    }
  }
  
  // ------------------------------------------------------------------------
  // Reset Logic
  // ------------------------------------------------------------------------
  /**
   * Resets the AR session, removing anchors and restoring default geometry.
   */
  fun resetSession() {
    try {
      // Clean up existing nodes
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

      // Properly reset AR session
      arFragment.arSceneView.session?.close()
      setupArFragment()
      
      // Restart session timeout monitoring after reset
      lastSessionStartTime = SystemClock.elapsedRealtime()
      startSessionTimeoutMonitoring()
      
      Log.d(TAG, "AR session reset successfully")
    } catch (e: Exception) {
      Log.e(TAG, "Error resetting AR session: ${e.message}")
    }
  }
  
  // Public wrapper methods for module access
  fun pauseSessionPublic() {
    pauseSession()
  }
  
  fun resumeSessionPublic() {
    resumeSession()
  }
  
  fun cleanupResourcesPublic() {
    cleanupResources()
  }
}
