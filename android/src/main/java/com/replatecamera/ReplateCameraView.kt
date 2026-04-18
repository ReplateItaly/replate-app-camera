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
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.TrackingState
import com.google.ar.sceneform.*
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.rendering.Color
import com.google.ar.sceneform.rendering.Material
import com.google.ar.sceneform.rendering.MaterialFactory
import com.google.ar.sceneform.rendering.ShapeFactory
import com.google.ar.sceneform.ux.ArFragment
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableMap
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.events.RCTEventEmitter
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
    private const val DEFAULT_SPHERE_RADIUS = 0.0015f
    private const val DEFAULT_LINE_HEIGHT = 0.02f   // vertical length
    private const val DEFAULT_LINE_LENGTH = 0.003f  // thin width
    private const val GREEN_HEIGHT_SCALE = 0.7f
    val COLOR_WHITE = Color(1f, 1f, 1f)
    val COLOR_GRAY = Color(0.35f, 0.35f, 0.35f)
    val COLOR_GREEN_BRIGHT = Color(0f, 1f, 0f)
    val COLOR_GREEN_DIM = Color(0f, 0.4f, 0f)
    private const val DEFAULT_SPHERES_RADIUS = 0.13f
    private const val DEFAULT_SPHERES_HEIGHT = 0.40f
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
  private val spheresModels = mutableListOf<Node>()
  private val sphereMaterials = arrayOfNulls<Material>(TOTAL_SPHERES_PER_CIRCLE * 2)
  private val circleMaterials = arrayOfNulls<Material>(2)
  private val pendingColorUpdates = java.util.concurrent.ConcurrentLinkedQueue<Pair<Int, Color>>()
  private val bgThread = java.util.concurrent.Executors.newSingleThreadExecutor()
  private var focusNode: FocusNode? = null

  // Scene geometry
  private var sphereRadius = DEFAULT_SPHERE_RADIUS
  private var lineHeight = DEFAULT_LINE_HEIGHT
  private var lineLength = DEFAULT_LINE_LENGTH
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
        val t0 = System.currentTimeMillis()
        anchorNode = createAnchorNode(hitResult)
        Log.d(TAG, "PERF anchor createAnchorNode: ${System.currentTimeMillis() - t0}ms")
        circleInFocus = 0
        val t1 = System.currentTimeMillis()
        createSpheresAtY(spheresHeight, 0)
        createSpheresAtY(distanceBetweenCircles + spheresHeight, 1)
        Log.d(TAG, "PERF anchor createSpheres: ${System.currentTimeMillis() - t1}ms")
        val t2 = System.currentTimeMillis()
        disablePlaneDetection()
        Log.d(TAG, "PERF anchor disablePlaneDetection: ${System.currentTimeMillis() - t2}ms")
        Log.d(TAG, "PERF anchor TOTAL: ${System.currentTimeMillis() - t0}ms")
        sendEvent("onAnchorSet")
        sendEvent("onCompletedTutorial")
        focusNode?.isEnabled = false
      }
    }
  }

  /**
   * Creates and returns an AnchorNode from a tap HitResult.
   */
  private fun createAnchorNode(hitResult: HitResult): AnchorNode {
    val session = arFragment.arSceneView.session!!
    val anchor: Anchor = session.createAnchor(hitResult.hitPose)
    return AnchorNode(anchor).also { node ->
      node.setParent(arFragment.arSceneView.scene)
    }
  }

  private fun disablePlaneDetection() {
    val session = arFragment.arSceneView.session ?: return
    val config = session.config
    config.planeFindingMode = Config.PlaneFindingMode.DISABLED
    session.configure(config)
    arFragment.arSceneView.planeRenderer.isEnabled = false
  }

  // ------------------------------------------------------------------------
  // Scene OnUpdateListener => replicates Swift ARSessionDelegate
  // ------------------------------------------------------------------------
  private var frameCount = 0

  override fun onUpdate(frameTime: FrameTime?) {
    val frame = arFragment.arSceneView.arFrame ?: return
    if (frame.camera.trackingState != TrackingState.TRACKING) return

    if (++frameCount % 60 == 0) checkThermalState()

    val anchor = anchorNode ?: return
    val anchorPose = anchor.anchor?.pose ?: return
    val relativeY = frame.camera.pose.ty() - anchorPose.ty()
    val threshold = DEFAULT_SPHERES_HEIGHT + DEFAULT_DISTANCE_BETWEEN_CIRCLES + DEFAULT_DISTANCE_BETWEEN_CIRCLES / 5f
    val hysteresis = 0.04f
    val newIndex = when {
      circleInFocus == 0 && relativeY >= threshold + hysteresis -> 1
      circleInFocus == 1 && relativeY < threshold - hysteresis -> 0
      else -> circleInFocus
    }
    updateCircleFocus(newIndex)

    if (pendingColorUpdates.isNotEmpty()) {
      val t = System.currentTimeMillis()
      repeat(3) {
        val update = pendingColorUpdates.poll() ?: return@repeat
        setSphereColor(update.first, update.second)
      }
      val elapsed = System.currentTimeMillis() - t
      if (elapsed > 1) Log.d(TAG, "PERF onUpdate pendingColors batch: ${elapsed}ms, remaining=${pendingColorUpdates.size}")
    }
  }

  // ------------------------------------------------------------------------
  // Spheres Logic
  // ------------------------------------------------------------------------
  @RequiresApi(Build.VERSION_CODES.N)
  private fun createSpheresAtY(y: Float, circleIndex: Int) {
    val initialColor = if (circleIndex == 0) COLOR_WHITE else COLOR_GRAY
    MaterialFactory.makeOpaqueWithColor(context, initialColor)
      .thenAccept { material: Material ->
        circleMaterials[circleIndex] = material
        for (i in 0 until TOTAL_SPHERES_PER_CIRCLE) {
          val angleRad = Math.toRadians((i * ANGLE_INCREMENT_DEGREES).toDouble()).toFloat()
          val x = spheresRadius * cos(angleRad.toDouble()).toFloat()
          val z = spheresRadius * sin(angleRad.toDouble()).toFloat()
          createSphere(Vector3(x, y, z), material)
        }
      }
  }

  @RequiresApi(Build.VERSION_CODES.N)
  private fun createSphere(position: Vector3, material: Material) {
    val center = Vector3.zero()
    val thickness = sphereRadius * 2
    val lineRenderable = ShapeFactory.makeCube(Vector3(lineLength, lineHeight, thickness), center, material)
    val lineNode = Node()
    lineNode.renderable = lineRenderable
    lineNode.worldPosition = position
    val angleRad = Math.atan2(position.z.toDouble(), position.x.toDouble())
    val angleDeg = Math.toDegrees(angleRad).toFloat() + 90f
    lineNode.localRotation = Quaternion.axisAngle(Vector3(0f, 1f, 0f), angleDeg)
    lineNode.setParent(anchorNode)
    spheresModels.add(lineNode)
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
        val t0 = System.currentTimeMillis()
        val old = circleInFocus
        circleInFocus = targetIndex

        val t1 = System.currentTimeMillis()
        performHapticFeedback()
        Log.d(TAG, "PERF circle haptic: ${System.currentTimeMillis() - t1}ms")

        val t2 = System.currentTimeMillis()
        circleMaterials[old]?.setFloat4("color", COLOR_GRAY)
        circleMaterials[targetIndex]?.setFloat4("color", COLOR_WHITE)
        Log.d(TAG, "PERF circle sharedMaterial setFloat4: ${System.currentTimeMillis() - t2}ms")

        val t3 = System.currentTimeMillis()
        bgThread.execute {
            val snapshot = buildColorUpdates(old, active = false) +
                           buildColorUpdates(targetIndex, active = true)
            Log.d(TAG, "PERF circle buildColorUpdates (bg): ${System.currentTimeMillis() - t3}ms, items=${snapshot.size}")
            pendingColorUpdates.addAll(snapshot)
        }

        Log.d(TAG, "PERF circle TOTAL (main thread): ${System.currentTimeMillis() - t0}ms")
    }
  }

  private fun buildColorUpdates(circleId: Int, active: Boolean): List<Pair<Int, Color>> {
    val spheresSet = if (circleId == 1) upperSpheresSet else lowerSpheresSet
    val offset = if (circleId == 1) TOTAL_SPHERES_PER_CIRCLE else 0
    val color = if (active) COLOR_GREEN_BRIGHT else COLOR_GREEN_DIM
    return spheresSet.indices.filter { spheresSet[it] }.map { Pair(offset + it, color) }
  }

  private fun setSphereColor(index: Int, color: Color) {
    if (index >= spheresModels.size) return
    val node = spheresModels[index]
    val material = sphereMaterials[index] ?: run {
      val copy = node.renderable?.material?.makeCopy() ?: return
      node.renderable?.material = copy
      sphereMaterials[index] = copy
      copy
    }
    material.setFloat4("color", color)
  }

  private fun performHapticFeedback() {
    performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
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
            val green = if (circleInFocus == 1) COLOR_GREEN_BRIGHT else COLOR_GREEN_DIM
            setSphereColor(TOTAL_SPHERES_PER_CIRCLE + sphereIndex, green)
            if (upperSpheresSet.all { it }) sendEvent("onCompletedUpperSpheres")
        }
    } else { // Lower circle
        if (!lowerSpheresSet[sphereIndex]) {
            lowerSpheresSet[sphereIndex] = true
            photosFromDifferentAnglesTaken++
            newAngle = true
            val green = if (circleInFocus == 0) COLOR_GREEN_BRIGHT else COLOR_GREEN_DIM
            setSphereColor(sphereIndex, green)
            if (lowerSpheresSet.all { it }) sendEvent("onCompletedLowerSpheres")
        }
    }

    if (newAngle) {
        performHapticFeedback()
    }
    callback(newAngle)
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
      circleMaterials[0] = null
      circleMaterials[1] = null
      sphereMaterials.fill(null)
      pendingColorUpdates.clear()
      focusNode = null
      
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
      circleMaterials[0] = null
      circleMaterials[1] = null
      sphereMaterials.fill(null)
      pendingColorUpdates.clear()
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
      lineHeight = DEFAULT_LINE_HEIGHT
      lineLength = DEFAULT_LINE_LENGTH
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
