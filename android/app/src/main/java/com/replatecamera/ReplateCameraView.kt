package com.replatecamera

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.util.Size
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.google.ar.core.Anchor
import com.google.ar.core.CameraConfig
import com.google.ar.core.CameraConfigFilter
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import com.google.ar.core.Frame
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.UnavailableException
import com.google.ar.sceneform.*
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.rendering.CameraStream
import com.google.ar.sceneform.rendering.Color
import com.google.ar.sceneform.rendering.Material
import com.google.ar.sceneform.rendering.MaterialFactory
import com.google.ar.sceneform.rendering.ShapeFactory
import com.google.ar.sceneform.rendering.PlaneRenderer
import com.google.ar.sceneform.ux.ArFragment
import com.facebook.react.bridge.LifecycleEventListener
import com.facebook.react.uimanager.ThemedReactContext
import com.google.ar.sceneform.ux.TransformableNode
import com.gorisse.thomas.sceneform.light.LightEstimationConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.LinkedList
import java.util.EnumSet
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
  android.hardware.SensorEventListener, LifecycleEventListener {

  companion object {
    private const val TAG = "ReplateCameraView"
    @Volatile
    private var instance: ReplateCameraView? = null

    // Number of spheres per circle (mirrors Swift code's 72)
    private const val TOTAL_SPHERES_PER_CIRCLE = 72
    // Each sphere is placed 5 degrees apart => 360/5 = 72
    private const val ANGLE_INCREMENT_DEGREES = 5f

    // Initial defaults that match the Swift code
    private const val DEFAULT_SPHERE_RADIUS = 0.0015f
    private const val DEFAULT_LINE_HEIGHT = 0.02f   // vertical length
    private const val DEFAULT_LINE_LENGTH = 0.003f  // thin width
    private const val GREEN_HEIGHT_SCALE = 0.3f
    private const val DEFAULT_SPHERES_RADIUS = 0.13f
    private const val DEFAULT_SPHERES_HEIGHT = 0.15f
    private const val DEFAULT_DISTANCE_BETWEEN_CIRCLES = 0.10f
    private const val DEFAULT_DRAG_SPEED = 7000f
    private const val ANGLE_THRESHOLD = 0.6f  // Radians
    // Track whether each sphere index is "set" (mirroring Swift arrays)
    private val upperSpheresSet = BooleanArray(TOTAL_SPHERES_PER_CIRCLE) { false }
    private val lowerSpheresSet = BooleanArray(TOTAL_SPHERES_PER_CIRCLE) { false }

    // Stats
    var totalPhotosTaken = 0
    var photosFromDifferentAnglesTaken = 0

    fun getCurrentInstance(): ReplateCameraView? = instance
  }

  // AR Sceneform stuff
  private lateinit var arFragment: ArFragment
  private var anchorNode: AnchorNode? = null
  private var anchorContentNode: Node? = null
  private var isSessionPaused = true
  @Volatile var captureActive = false
  private var isViewAttached = false
  private var sensorManager: android.hardware.SensorManager? = null
  private var gravitySensor: android.hardware.Sensor? = null

  // Spheres and a "focus node" — fixed-size array so index i always = sphere at angle i*5°
  // (lower circle 0..71, upper circle 72..143). Using array avoids async insertion ordering.
  private val spheresModels = arrayOfNulls<Node>(TOTAL_SPHERES_PER_CIRCLE * 2)
  // Per-sphere material copies — pre-cloned from baseMaterial so setFloat4 updates are sync/instant
  private val sphereMaterialInstances = arrayOfNulls<Material>(TOTAL_SPHERES_PER_CIRCLE * 2)
  // Single compiled Material, cloned for each sphere via makeCopy() — avoids 144× shader compiles
  private var baseMaterial: Material? = null
  private var focusNode: FocusNode? = null

  // Scene geometry
  private var sphereRadius = DEFAULT_SPHERE_RADIUS
  private var lineHeight = DEFAULT_LINE_HEIGHT
  private var lineLength = DEFAULT_LINE_LENGTH
  private var spheresRadius = DEFAULT_SPHERES_RADIUS
  private var sphereAngle = ANGLE_INCREMENT_DEGREES  // If you want to "scale angle"
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

  // Photo capture controllers
  private var cameraController: ReplateCameraCaptureController? = null
  private val highQualityCapture = HighQualityPhotoCapture(context)
  private val captureQueue = java.util.concurrent.Executors.newSingleThreadExecutor()

  // Torch (flash) state
  private var torchCameraId: String? = null
  private var arInitialized = false
  private var lastTapTime: Long = 0
  private var arCoreInstallRequested = false
  private var arCoreUnsupported = false
  private var setupRetryScheduled = false
  private var resumeRetryScheduled = false
  private var anchorLocked = false
  private var planeTrackingEnabled = true
  private var anchorDetachedForDrag = false
  private var anchorDragInProgress = false
  private var frameCounter = 0L
  @Volatile private var expectingFirstFrame = false
  private var lastGravityLogTimeMs = 0L
  private var selectedCameraImageSize: Size? = null
  private var selectedCameraTextureSize: Size? = null
  private var selectedCameraSessionHash: Int? = null
  private var setupLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
  private var gestureRoutingInstalled = false
  private val debugLogLines = LinkedList<String>()
  private var debugOverlayTextView: TextView? = null

  private fun getArSceneViewOrNull(): ArSceneView? {
    if (!::arFragment.isInitialized) return null
    return try {
      arFragment.arSceneView
    } catch (_: Throwable) {
      null
    }
  }

  private fun stateSummary(): String {
    val sceneView = getArSceneViewOrNull()
    val hasSession = try {
      sceneView?.session != null
    } catch (_: Throwable) {
      false
    }
    return "viewId=$id attached=$isViewAttached initialized=$arInitialized paused=$isSessionPaused locked=$anchorLocked planeTracking=$planeTrackingEnabled dragDetached=$anchorDetachedForDrag hasSceneView=${sceneView != null} hasSession=$hasSession"
  }

  fun getTransformRootNode(): Node? {
    return anchorContentNode ?: anchorNode
  }

  private fun routeGestureTouchEvent(event: MotionEvent) {
    if (getTransformRootNode() == null) return
    scaleDetector.onTouchEvent(event)
    gestureDetector.onTouchEvent(event)
    when (event.actionMasked) {
      MotionEvent.ACTION_UP,
      MotionEvent.ACTION_CANCEL -> finalizeAnchorDragIfNeeded()
    }
  }

  private fun isDebugOverlayEnabled(): Boolean {
    return (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
  }

  private fun ensureDebugOverlay() {
    if (!isDebugOverlayEnabled() || debugOverlayTextView != null) return
    val overlay = TextView(context).apply {
      layoutParams = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.WRAP_CONTENT,
        Gravity.BOTTOM
      )
      setBackgroundColor(android.graphics.Color.argb(180, 0, 0, 0))
      setTextColor(android.graphics.Color.WHITE)
      setTypeface(android.graphics.Typeface.MONOSPACE)
      textSize = 11f
      maxLines = 12
      setPadding(24, 16, 24, 16)
      isClickable = false
      isFocusable = false
      importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
      setOnTouchListener { _, _ -> false }
      text = "debug overlay ready"
    }
    debugOverlayTextView = overlay
    addView(overlay)
  }

  private fun appendDebugOverlayLine(level: String, message: String) {
    if (!isDebugOverlayEnabled()) return
    val line = "$level $message"
    post {
      val overlay = debugOverlayTextView ?: return@post
      synchronized(debugLogLines) {
        debugLogLines.add(line)
        while (debugLogLines.size > 12) {
          debugLogLines.removeFirst()
        }
        overlay.text = debugLogLines.joinToString("\n")
      }
    }
  }

  private fun logD(message: String) {
    Log.d(TAG, "[${stateSummary()}] $message")
    appendDebugOverlayLine("D", message)
  }

  private fun logI(message: String) {
    Log.i(TAG, "[${stateSummary()}] $message")
    appendDebugOverlayLine("I", message)
  }

  private fun logW(message: String) {
    Log.w(TAG, "[${stateSummary()}] $message")
    appendDebugOverlayLine("W", message)
  }

  private fun logE(message: String, throwable: Throwable? = null) {
    if (throwable != null) {
      Log.e(TAG, "[${stateSummary()}] $message", throwable)
    } else {
      Log.e(TAG, "[${stateSummary()}] $message")
    }
    appendDebugOverlayLine("E", if (throwable != null) "$message | ${throwable.message ?: throwable::class.java.simpleName}" else message)
  }

  // ------------------------------------------------------------------------
  // Init & Setup
  // ------------------------------------------------------------------------
  init {
    setBackgroundColor(android.graphics.Color.TRANSPARENT)
    ensureDebugOverlay()
    logI("Initializing ReplateCameraView")

    gestureDetector = GestureDetector(context, GestureListener())
    scaleDetector = ScaleGestureDetector(context, ScaleListener())

    // Register a gravity sensor to replicate iOS's CMMotionManager
    sensorManager = context.getSystemService(Context.SENSOR_SERVICE)
      as? android.hardware.SensorManager
    gravitySensor = sensorManager?.getDefaultSensor(android.hardware.Sensor.TYPE_GRAVITY)
    
    isViewAttached = false
    instance = this

    if (id == NO_ID) {
      id = generateViewId()
    }
    
    logI("ReplateCameraView init complete")
  }

  /**
   * If camera permission not granted, request at runtime (for AR use).
   */
  private fun requestCameraPermission() {
    val reactContext = context as? ThemedReactContext
    val activity = reactContext?.currentActivity
    if (
      activity != null &&
      ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
    ) {
      ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.CAMERA), 1001)
      logI("Requested camera permission from activity=${activity::class.java.simpleName}")
    } else {
      logD("Camera permission already granted or activity unavailable (activityNull=${activity == null})")
    }
  }

  /**
   * Programmatically attach an ArFragment into this FrameLayout.
   * We also add ourselves as a Scene update listener.
   */
  @RequiresApi(Build.VERSION_CODES.N)
  private fun setupArFragment() {
    if (arInitialized) return
    logI("setupArFragment invoked")
    val reactContext = context as? ThemedReactContext
    val baseActivity = reactContext?.currentActivity
    if (baseActivity == null) {
      logW("Cannot initialize AR fragment: current activity is null")
      scheduleSetupRetry()
      return
    }
    if (!hasCameraPermission(baseActivity)) {
      ActivityCompat.requestPermissions(baseActivity, arrayOf(Manifest.permission.CAMERA), 1001)
      logI("Camera permission missing, requested and postponing AR init")
      scheduleSetupRetry()
      return
    }
    if (!ensureArCoreReady(baseActivity)) {
      logW("ARCore not ready yet, postponing AR fragment setup")
      if (!arCoreUnsupported) {
        scheduleSetupRetry()
      }
      return
    }

    val activity = baseActivity as? FragmentActivity
    if (activity == null) {
      logW("Cannot initialize AR fragment: activity is not FragmentActivity (${baseActivity::class.java.name})")
      scheduleSetupRetry()
      return
    }

    try {
      arFragment = ArFragment()
      arFragment.setOnSessionConfigurationListener { session: Session, config: Config ->
        applySessionConfiguration(session, config)
      }
      arFragment.setOnViewCreatedListener { sceneView ->
        initializeSceneView(sceneView)
      }

      val fm = activity.supportFragmentManager
      logD("Created ArFragment, replacing container with fragment tag=replate_camera_$id")
      fm.beginTransaction().replace(this.id, arFragment, "replate_camera_$id").commitNowAllowingStateLoss()
      logD("Fragment transaction committed (commitNowAllowingStateLoss)")
      // React Native uses Yoga and never lays out views added via FragmentManager.
      // Force-layout the ArFragment view with the parent's exact dimensions so the
      // GLSurface is created at the correct size without needing an orientation change.
      post {
        applyFragmentLayout()
        debugOverlayTextView?.bringToFront()
      }

      val existingSceneView = getArSceneViewOrNull()
      if (!arInitialized && existingSceneView != null) {
        logW("ArSceneView already available before onViewCreated callback, using fallback initialization")
        initializeSceneView(existingSceneView)
      }
    } catch (t: Throwable) {
      logE("Failed to initialize AR fragment", t)
      scheduleSetupRetry()
    }
  }

  private fun setupArFragmentWhenReady() {
    if (arInitialized || !isViewAttached) return
    if (width > 0 && height > 0) {
      setupArFragment()
      return
    }
    if (setupLayoutListener != null) return
    logI("Deferring AR setup until ReplateCameraView layout is ready")
    setupLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
      if (!isViewAttached || arInitialized) {
        removeSetupLayoutListener()
        return@OnGlobalLayoutListener
      }
      if (width > 0 && height > 0) {
        removeSetupLayoutListener()
        setupArFragment()
      }
    }
    viewTreeObserver.addOnGlobalLayoutListener(setupLayoutListener)
  }

  private fun applyFragmentLayout() {
    val w = this.width
    val h = this.height
    if (w <= 0 || h <= 0) return
    if (!::arFragment.isInitialized) return
    arFragment.view?.let { fragView ->
      if (fragView.width != w || fragView.height != h) {
        fragView.layout(0, 0, w, h)
        logD("applyFragmentLayout: fragment root → $w x $h")
      }
    }
    getArSceneViewOrNull()?.let { sv ->
      if (sv.width != w || sv.height != h) {
        sv.layout(0, 0, w, h)
        logD("applyFragmentLayout: ArSceneView → $w x $h")
      }
    }
  }

  /**
   * React Native applies Yoga layout by calling view.layout() directly, which triggers
   * onLayout. This is the authoritative source of final dimensions — propagate them
   * immediately to the ArFragment and ArSceneView so Filament always has the correct
   * viewport before/after the first surface creation.
   */
  override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
    super.onLayout(changed, left, top, right, bottom)
    applyFragmentLayout()
  }

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    applyFragmentLayout()
  }

  private fun installGestureRouting(sceneView: ArSceneView) {
    if (gestureRoutingInstalled) return
    gestureRoutingInstalled = true
    sceneView.scene.addOnPeekTouchListener { _, motionEvent ->
      routeGestureTouchEvent(motionEvent)
    }
  }

  private fun initializeSceneView(sceneView: ArSceneView) {
    if (arInitialized) {
      logD("initializeSceneView skipped: already initialized")
      return
    }
    try {
      logI("Initializing ArSceneView")
      // Keep AR surface transparent from the React Native container perspective.
      // This avoids showing the parent background color when Sceneform starts.
      this.setBackgroundColor(android.graphics.Color.TRANSPARENT)
      // Ensure the debug overlay stays on top of the ArSceneView
      debugOverlayTextView?.bringToFront()
      sceneView._lightEstimationConfig = LightEstimationConfig.DISABLED
      sceneView.cameraStream.depthOcclusionMode = CameraStream.DepthOcclusionMode.DEPTH_OCCLUSION_ENABLED

      // Initialize controllers only when scene view is ready.
      cameraController = ReplateCameraCaptureController(context, sceneView, this)
      highQualityCapture.start()

      // Listen for plane taps
      updatePlaneRendererState(sceneView, planeTrackingEnabled)
      sendEvent("onOpenedTutorial")
      logI("Plane renderer enabled, tutorial opened event sent")

      focusNode = FocusNode(context)
      sceneView.scene.addChild(focusNode)
      logD("Focus node attached to scene")
      installGestureRouting(sceneView)

      arFragment.setOnTapArPlaneListener { hitResult: HitResult, plane: Plane, _: MotionEvent ->
        val now = System.currentTimeMillis()
        if (
          anchorNode == null &&
          now - lastTapTime >= 500 &&
          (plane.type == Plane.Type.HORIZONTAL_UPWARD_FACING || plane.type == Plane.Type.HORIZONTAL_DOWNWARD_FACING)
        ) {
          lastTapTime = now
          logI("Plane tap accepted type=${plane.type} at x=${hitResult.hitPose.tx()} y=${hitResult.hitPose.ty()} z=${hitResult.hitPose.tz()}")
      val createdAnchorNode = createAnchorNode(hitResult, sceneView.session)
          if (createdAnchorNode == null) {
            logE("Failed to create anchor node from hit result")
            return@setOnTapArPlaneListener
          }
          anchorNode = createdAnchorNode
          anchorLocked = true
          circleInFocus = -1
          performPhotoTakenHaptic()
          sendEvent("onAnchorSet")
          setTorch(true)
          setPlaneTrackingEnabled(false, sceneView)
          sendEvent("onCompletedTutorial")
          focusNode?.isEnabled = false
          logI("Anchor created and tutorial completed")
          // Pre-build one base material (one shader compile), then clone it per sphere — all sync
          prebuildBaseMaterial { baseMat ->
            createSpheresAtY(spheresHeight, 0, baseMat)           // Lower: indices 0-71
            createSpheresAtY(distanceBetweenCircles + spheresHeight, TOTAL_SPHERES_PER_CIRCLE, baseMat) // Upper: 72-143
            setOpacityToCircle(0, 1.0f) // Lower circle active
            setOpacityToCircle(1, 0.5f) // Upper circle dim
            circleInFocus = 0
          }
        } else {
          logD("Plane tap ignored (anchorAlreadySet=${anchorNode != null}, debounceMs=${now - lastTapTime}, planeType=${plane.type})")
        }
      }

      arInitialized = true
      setupRetryScheduled = false
      logI("AR fragment setup completed")

      try {
        sceneView._lightEstimationConfig = LightEstimationConfig.DISABLED
        sceneView.session?.let { applySessionConfiguration(it) }
        updatePlaneRendererState(sceneView, planeTrackingEnabled)
        registerSensorListener()
        sceneView.scene.addOnUpdateListener(this)
        isSessionPaused = false
        logI("INIT [1/4] listeners activated isSessionPaused=false")
      } catch (e: Exception) {
        logE("INIT [1/4] FAILED to activate listeners", e)
        scheduleResumeRetry(200)
      }

      applyFragmentLayout()

      val surfaceValid = try { sceneView.holder.surface?.isValid == true } catch (_: Exception) { false }
      logI("INIT [2/4] surface check: surfaceValid=$surfaceValid viewSize=${width}x${height} sceneViewSize=${sceneView.width}x${sceneView.height}")

      logI("INIT [3/4] session already running via gorisse (surfaceValid=$surfaceValid), no reinit needed")

    } catch (t: Throwable) {
      logE("Failed during ArSceneView initialization", t)
      scheduleSetupRetry()
    }
  }

  private fun applySessionConfiguration(session: Session, existingConfig: Config? = null) {
    selectBestCameraConfig(session)
    val config = existingConfig ?: Config(session)
    config.focusMode = Config.FocusMode.FIXED
    if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
      config.depthMode = Config.DepthMode.AUTOMATIC
    }
    // After anchor placement disable plane finding so ARCore stops updating planes
    // (prevents world re-alignment from affecting the anchor). Light estimation stays
    // enabled so Sceneform has ambient light and materials render with correct colors.
    config.planeFindingMode = if (anchorLocked) {
      Config.PlaneFindingMode.DISABLED
    } else {
      Config.PlaneFindingMode.HORIZONTAL
    }
    // Before anchor: ENVIRONMENTAL_HDR gives proper IBL for sphere visibility.
    // After anchor: switch to AMBIENT_INTENSITY — the scene lighting is already established
    // and AMBIENT_INTENSITY causes far less ARCore feature-point scanning, reducing
    // the SLAM re-alignments that make the anchor appear to drift.
    config.lightEstimationMode = Config.LightEstimationMode.DISABLED
    session.configure(config)
    logI(
      "Session configuration applied " +
        "(sessionHash=${session.hashCode()}, anchorLocked=$anchorLocked, " +
        "focusMode=${config.focusMode}, lightMode=${config.lightEstimationMode}, " +
        "planeMode=${config.planeFindingMode})"
    )
  }

  private fun selectBestCameraConfig(session: Session) {
    try {
      val sessionHash = session.hashCode()
      if (
        selectedCameraSessionHash == sessionHash &&
        selectedCameraImageSize != null &&
        selectedCameraTextureSize != null
      ) {
        logD(
          "Reusing previously selected camera config " +
            "imageSize=${selectedCameraImageSize!!.width}x${selectedCameraImageSize!!.height} " +
            "textureSize=${selectedCameraTextureSize!!.width}x${selectedCameraTextureSize!!.height}"
        )
        return
      }
      val filter = CameraConfigFilter(session).apply {
        // Include both 30 and 60 fps — sort will pick the largest texture regardless
        targetFps = EnumSet.of(
          CameraConfig.TargetFps.TARGET_FPS_30,
          CameraConfig.TargetFps.TARGET_FPS_60
        )
        depthSensorUsage = EnumSet.of(
          CameraConfig.DepthSensorUsage.DO_NOT_USE,
          CameraConfig.DepthSensorUsage.REQUIRE_AND_USE
        )
      }
      val configs = session.getSupportedCameraConfigs(filter).ifEmpty {
        session.getSupportedCameraConfigs()
      }
      val bestConfig = configs.maxWithOrNull(
        // Primary: largest GL texture → sharpest camera preview
        compareBy<CameraConfig> { it.textureSize.width * it.textureSize.height }
          // Secondary: largest CPU image → best acquireCameraImage quality
          .thenBy { it.imageSize.width * it.imageSize.height }
      )
      if (bestConfig != null) {
        session.cameraConfig = bestConfig
        selectedCameraImageSize = bestConfig.imageSize
        selectedCameraTextureSize = bestConfig.textureSize
        selectedCameraSessionHash = sessionHash
        logI(
          "Selected max camera config " +
            "imageSize=${bestConfig.imageSize.width}x${bestConfig.imageSize.height} " +
            "textureSize=${bestConfig.textureSize.width}x${bestConfig.textureSize.height} " +
            "fpsRange=${bestConfig.fpsRange}"
        )
      } else {
        logW("No camera config selected")
      }
    } catch (e: Exception) {
      logE("Unable to select max camera config", e)
    }
  }

  private fun setPlaneTrackingEnabled(enabled: Boolean, sceneView: ArSceneView? = getArSceneViewOrNull()) {
    planeTrackingEnabled = enabled
    sceneView?.let { view ->
      updatePlaneRendererState(view, enabled)
      view.session?.let { applySessionConfiguration(it) }
    }
    logI("Plane tracking enabled=$enabled")
  }

  private fun updatePlaneRendererState(sceneView: ArSceneView, enabled: Boolean) {
    // Always disable the default plane renderer (dots) — FocusNode draws our custom square.
    sceneView.planeRenderer.isEnabled = false
    sceneView.planeRenderer.isVisible = false
    sceneView.planeRenderer.isShadowReceiver = false
  }

  private fun ensureArCoreReady(activity: Activity): Boolean {
    val availability = ArCoreApk.getInstance().checkAvailability(activity)
    logD("ARCore availability result=$availability")
    return when (availability) {
      ArCoreApk.Availability.SUPPORTED_INSTALLED -> {
        arCoreUnsupported = false
        true
      }
      ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD,
      ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> {
        try {
          when (ArCoreApk.getInstance().requestInstall(activity, !arCoreInstallRequested)) {
            ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
              arCoreInstallRequested = true
              logI("ARCore installation requested")
              false
            }
            ArCoreApk.InstallStatus.INSTALLED -> {
              arCoreUnsupported = false
              logI("ARCore install/update completed")
              true
            }
          }
        } catch (e: UnavailableException) {
          logE("ARCore install/update unavailable", e)
          false
        }
      }
      ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> {
        arCoreUnsupported = true
        logE("ARCore is not supported on this device")
        false
      }
      ArCoreApk.Availability.UNKNOWN_CHECKING -> {
        logW("ARCore availability still checking")
        if (arInitialized) {
          scheduleResumeRetry(100)
        } else {
          scheduleSetupRetry(100)
        }
        false
      }
      ArCoreApk.Availability.UNKNOWN_ERROR,
      ArCoreApk.Availability.UNKNOWN_TIMED_OUT -> {
        logW("ARCore availability check failed: $availability")
        false
      }
      else -> false
    }
  }

  private fun hasCameraPermission(activity: Activity): Boolean {
    return ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) ==
      PackageManager.PERMISSION_GRANTED
  }

  private fun scheduleSetupRetry(delayMs: Long = 150L) {
    if (setupRetryScheduled || arInitialized || !isViewAttached) {
      logD("Skipping setup retry (alreadyScheduled=$setupRetryScheduled, initialized=$arInitialized, attached=$isViewAttached)")
      return
    }
    logI("Scheduling setup retry in ${delayMs}ms")
    setupRetryScheduled = true
    postDelayed({
      setupRetryScheduled = false
      if (!arInitialized && isViewAttached) {
        setupArFragmentWhenReady()
      }
    }, delayMs)
  }

  private fun scheduleResumeRetry(delayMs: Long = 150L) {
    if (resumeRetryScheduled || !isViewAttached) {
      logD("RESUME_RETRY skipped alreadyScheduled=$resumeRetryScheduled attached=$isViewAttached")
      return
    }
    logI("RESUME_RETRY scheduled in ${delayMs}ms")
    resumeRetryScheduled = true
    postDelayed({
      resumeRetryScheduled = false
      logI("RESUME_RETRY firing isViewAttached=$isViewAttached isSessionPaused=$isSessionPaused")
      if (isViewAttached && isSessionPaused) {
        resumeSession()
      } else {
        logI("RESUME_RETRY no-op (attached=$isViewAttached paused=$isSessionPaused)")
      }
    }, delayMs)
  }

  private fun closeArSession() {
    try {
      logD("Closing AR session")
      getArSceneViewOrNull()?.session?.close()
    } catch (e: Exception) {
      logW("Error closing AR session: ${e.message}")
    }
    logD("AR session close requested")
  }

  /**
   * Creates and returns an AnchorNode from a tap HitResult.
   */
  private fun createAnchorNode(hitResult: HitResult, session: Session?): AnchorNode? {
    if (session == null) {
      logE("Cannot create anchor node: session is null")
      return null
    }
    return try {
      val pose = hitResult.hitPose
      // Keep the ARCore anchor attached. ARCore automatically compensates for world
      // re-alignment (the remapping of the coordinate system as the environment is
      // scanned), so the anchor stays at the correct physical-world position.
      // Detaching it would cause the node to drift/jerk whenever ARCore remaps.
      val anchor: Anchor = session.createAnchor(pose)
      AnchorNode(anchor).also { node ->
        getArSceneViewOrNull()?.scene?.let { scene ->
          node.setParent(scene)
        }
        // anchorContentNode is a child of anchorNode at local origin so it
        // inherits the anchor's world pose and floats freely with it.
        anchorContentNode = Node().also { contentNode ->
          contentNode.setParent(node)
          contentNode.localPosition = Vector3.zero()
          contentNode.localRotation = Quaternion.identity()
          contentNode.localScale = Vector3(0.8f, 0.8f, 0.8f)
        }
        logI("Anchor created (anchorHash=${anchor.hashCode()}, x=${pose.tx()}, y=${pose.ty()}, z=${pose.tz()})")
      }
    } catch (e: Exception) {
      logE("Error creating anchor node", e)
      null
    }
  }

  // ------------------------------------------------------------------------
  // Scene OnUpdateListener => replicates Swift ARSessionDelegate
  // ------------------------------------------------------------------------
  override fun onUpdate(frameTime: FrameTime?) {
    val sceneView = getArSceneViewOrNull() ?: return
    val frame = sceneView.arFrame ?: return

    if (anchorNode == null) {
      val wasFocusEnabled = focusNode?.isEnabled ?: false
      focusNode?.updateFromFrame(frame, sceneView)
      val isFocusEnabled = focusNode?.isEnabled ?: false
      if (isFocusEnabled != wasFocusEnabled) {
        logD("FocusNode visibility changed: $wasFocusEnabled → $isFocusEnabled (tracking=${frame.camera.trackingState})")
      }
    } else {
      focusNode?.isEnabled = false
      if (captureActive) {
        cameraController?.triggerAutoCaptureIfNewAngle(frame)
      }
    }

    frameCounter++
    if (expectingFirstFrame) {
      expectingFirstFrame = false
      logI("onUpdate: FIRST FRAME after resume frameCounter=$frameCounter tracking=${frame.camera.trackingState}")
    }
    if (frameCounter % 30L == 0L) {
      val pose = frame.camera.pose
      val trackingState = frame.camera.trackingState
      val trackingFailure = frame.camera.trackingFailureReason
      logD("Frame update frameCounter=$frameCounter tracking=$trackingState failure=$trackingFailure pose=(${pose.tx()}, ${pose.ty()}, ${pose.tz()})")
    }
  }

  // ------------------------------------------------------------------------
  // Spheres Logic
  // ------------------------------------------------------------------------

  /**
   * Pre-build one base Material from the .filamat asset (one async shader compile).
   * Subsequent calls return the cached instance immediately.
   */
  private fun prebuildBaseMaterial(onReady: (Material) -> Unit) {
    val cached = baseMaterial
    if (cached != null) {
      onReady(cached)
      return
    }
    makeUnlitMaterial(Color(0.98f, 0.98f, 0.98f), 1f) { material ->
      baseMaterial = material
      onReady(material)
    }
  }

  @RequiresApi(Build.VERSION_CODES.N)
  private fun createSpheresAtY(y: Float, circleOffset: Int, baseMat: Material) {
    for (i in 0 until TOTAL_SPHERES_PER_CIRCLE) {
      val angleRad = Math.toRadians((i * ANGLE_INCREMENT_DEGREES).toDouble()).toFloat()
      val x = spheresRadius * cos(angleRad.toDouble()).toFloat()
      val z = spheresRadius * sin(angleRad.toDouble()).toFloat()
      createSphere(Vector3(x, y, z), circleOffset + i, baseMat)
    }
  }

  @RequiresApi(Build.VERSION_CODES.N)
  private fun createSphere(position: Vector3, arrayIndex: Int, baseMat: Material) {
    // makeCopy() clones the compiled material instance — synchronous, no shader recompile
    val copy = baseMat.makeCopy()
    sphereMaterialInstances[arrayIndex] = copy
    val thickness = sphereRadius * 2
    val lineRenderable = ShapeFactory.makeCube(Vector3(lineLength, lineHeight, thickness), Vector3.zero(), copy)
    val lineNode = Node()
    lineNode.renderable = lineRenderable
    lineNode.localPosition = position
    val angleRad = Math.atan2(position.z.toDouble(), position.x.toDouble())
    val angleDeg = Math.toDegrees(angleRad).toFloat() + 90f
    lineNode.localRotation = Quaternion.axisAngle(Vector3(0f, 1f, 0f), angleDeg)
    lineNode.setParent(anchorContentNode ?: anchorNode ?: return)
    spheresModels[arrayIndex] = lineNode
  }

  fun getAnchorPose(anchor: Node? = getTransformRootNode()): Pose? {
    val currentAnchor = anchor ?: return null
    val position = currentAnchor.worldPosition
    val rotation = currentAnchor.worldRotation
    return Pose(
      floatArrayOf(position.x, position.y, position.z),
      floatArrayOf(rotation.x, rotation.y, rotation.z, rotation.w)
    )
  }


  // ------------------------------------------------------------------------
  // Gestures (Mirror Swift's tap, pan, pinch)
  // ------------------------------------------------------------------------
  override fun onTouchEvent(event: MotionEvent): Boolean {
    if (!::arFragment.isInitialized) {
      return super.onTouchEvent(event)
    }
    return true
  }

  private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
    private var lastTapTime: Long = 0

    override fun onDown(e: MotionEvent): Boolean {
      return true
    }

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
      if (!::arFragment.isInitialized) return false
      val sceneView = getArSceneViewOrNull() ?: return false
      // Always drag anchorNode so the ARCore anchor is the one that moves;
      // detach it first so ARCore doesn't override our position.
      val node = anchorNode ?: return true
      val camera = sceneView.scene.camera
      if (e2.action == MotionEvent.ACTION_MOVE && camera != null) {
        detachAnchorForDragIfNeeded(node)
        // distanceY > 0 when finger moves up; we want up → anchor moves away → positive forward
        val translationX = -distanceX
        val translationY = distanceY

        val cameraPos = camera.worldPosition
        val anchorPos = node.worldPosition

        // Forward = horizontal direction from camera toward anchor.
        val dirToAnchor = Vector3.subtract(anchorPos, cameraPos)
        val forwardNorm = normalize(Vector3(dirToAnchor.x, 0f, dirToAnchor.z))
        // Right = cross(forward, up) so that +X is camera-right when forward = -Z
        val rightNorm = Vector3(-forwardNorm.z, 0f, forwardNorm.x)

        // Scale movement by distance from camera to anchor so drag feels
        // consistent in screen space regardless of how far the camera is.
        val cameraDistance = Vector3.subtract(anchorPos, cameraPos).length().coerceAtLeast(0.1f)

        val adjustedMovement = Vector3(
          translationX * rightNorm.x + translationY * forwardNorm.x,
          0f,
          translationX * rightNorm.z + translationY * forwardNorm.z
        ).scaled(cameraDistance / dragSpeed)

        val initialPos = node.worldPosition
        node.worldPosition = Vector3.add(initialPos, adjustedMovement)
        anchorDragInProgress = true
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
      val currentAnchor = getTransformRootNode() ?: return false
      val scaleFactor = detector.scaleFactor
      val newScale = Vector3(
        currentAnchor.localScale.x * scaleFactor,
        currentAnchor.localScale.y * scaleFactor,
        currentAnchor.localScale.z * scaleFactor
      )
      currentAnchor.localScale = newScale
      anchorDragInProgress = true
      return true
    }
  }

  private fun detachAnchorForDragIfNeeded(currentAnchor: AnchorNode) {
    if (anchorDetachedForDrag) return
    try {
      currentAnchor.anchor?.detach()
      currentAnchor.anchor = null
      anchorDetachedForDrag = true
      logI("Anchor detached for drag")
    } catch (e: Exception) {
      logE("Failed to detach anchor for drag", e)
    }
  }

  private fun finalizeAnchorDragIfNeeded() {
    if (!anchorDetachedForDrag) {
      anchorDragInProgress = false
      return
    }
    val currentAnchor = anchorNode
    val sceneView = getArSceneViewOrNull()
    val session = sceneView?.session
    if (currentAnchor == null || session == null) {
      logW("Cannot finalize anchor drag: missing anchor node or AR session")
      return
    }
    try {
      val finalPos = currentAnchor.worldPosition
      val rotation = currentAnchor.worldRotation
      val pose = Pose(
        floatArrayOf(finalPos.x, finalPos.y, finalPos.z),
        floatArrayOf(rotation.x, rotation.y, rotation.z, rotation.w)
      )
      val newAnchor = session.createAnchor(pose)
      currentAnchor.anchor = newAnchor

      anchorDetachedForDrag = false
      anchorDragInProgress = false
      logI("Anchor reattached after drag (anchorHash=${newAnchor.hashCode()}, x=${finalPos.x}, y=${finalPos.y}, z=${finalPos.z})")
    } catch (e: Exception) {
      logE("Failed to finalize anchor drag", e)
    }
  }

  // ------------------------------------------------------------------------
  // Photo Capture Implementation (ARCore frame.acquireCameraImage)
  // ------------------------------------------------------------------------
  /**
   * Captures the raw camera image from the current ARCore frame.
   * ARCore is never paused — the image is extracted directly from the live session.
   * The saved JPEG contains only the sensor image — no AR overlays.
   *
   * [onResult] is called on the MAIN thread with the saved File, or null on failure.
   */
  fun captureFromFrame(frame: Frame, onResult: (File?) -> Unit) {
    try {
      val image = frame.acquireCameraImage()
      val sensorOrientation = getSensorOrientation()
      captureQueue.execute {
        try {
          val jpegBytes = yuvImageToJpeg(image, sensorOrientation, 95)
          image.close()
          val file = saveJpegToFile(jpegBytes)
          logI("CAPTURE: ARCore frame JPEG saved path=${file.absolutePath} size=${file.length()} bytes")
          Handler(Looper.getMainLooper()).post { onResult(file) }
        } catch (e: Exception) {
          try { image.close() } catch (_: Exception) {}
          logE("CAPTURE: YUV conversion failed", e)
          Handler(Looper.getMainLooper()).post { onResult(null) }
        }
      }
    } catch (e: NotYetAvailableException) {
      logW("CAPTURE: camera image not yet available for this frame")
      onResult(null)
    } catch (e: Exception) {
      logE("CAPTURE: acquireCameraImage failed", e)
      onResult(null)
    }
  }

  private fun getBackCameraId(): String? {
    if (torchCameraId != null) return torchCameraId
    return try {
      val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
      manager.cameraIdList.firstOrNull { id ->
        manager.getCameraCharacteristics(id)
          .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
      }.also { torchCameraId = it }
    } catch (e: Exception) { null }
  }

  private fun setTorch(enabled: Boolean) {
    try {
      val cameraId = getBackCameraId() ?: return
      val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
      manager.setTorchMode(cameraId, enabled)
      logI("Torch ${if (enabled) "ON" else "OFF"}")
    } catch (e: Exception) {
      logW("setTorch($enabled) failed: ${e.message}")
    }
  }

  private fun getSensorOrientation(): Int {
    return try {
      val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
      val backId = manager.cameraIdList.firstOrNull { id ->
        manager.getCameraCharacteristics(id)
          .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
      } ?: return 90
      manager.getCameraCharacteristics(backId)
        .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
    } catch (e: Exception) {
      90
    }
  }

  private fun yuvImageToJpeg(image: android.media.Image, sensorRotation: Int, quality: Int): ByteArray {
    val width = image.width
    val height = image.height
    val planes = image.planes
    val yBuffer = planes[0].buffer
    val uBuffer = planes[1].buffer
    val vBuffer = planes[2].buffer
    val yRowStride = planes[0].rowStride
    val uvRowStride = planes[1].rowStride
    val uvPixelStride = planes[1].pixelStride

    // Build NV21: Y plane + interleaved VU
    val nv21 = ByteArray(width * height + (width / 2) * (height / 2) * 2)
    var offset = 0
    for (row in 0 until height) {
      yBuffer.position(row * yRowStride)
      yBuffer.get(nv21, offset, width)
      offset += width
    }
    for (row in 0 until height / 2) {
      for (col in 0 until width / 2) {
        val uvIndex = row * uvRowStride + col * uvPixelStride
        vBuffer.position(uvIndex); nv21[offset++] = vBuffer.get()
        uBuffer.position(uvIndex); nv21[offset++] = uBuffer.get()
      }
    }

    val yuvImage = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, width, height, null)
    val raw = ByteArrayOutputStream()
    yuvImage.compressToJpeg(android.graphics.Rect(0, 0, width, height), quality, raw)
    val jpegBytes = raw.toByteArray()

    if (sensorRotation == 0) return jpegBytes

    // Rotate to upright orientation
    val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
    val matrix = Matrix().apply { postRotate(sensorRotation.toFloat()) }
    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    bitmap.recycle()
    val out = ByteArrayOutputStream()
    rotated.compress(Bitmap.CompressFormat.JPEG, quality, out)
    rotated.recycle()
    return out.toByteArray()
  }

  private fun saveJpegToFile(bytes: ByteArray): File {
    val name = "replate_${System.currentTimeMillis()}.jpg"
    // Save to private storage — needed so EXIF can be written via file path
    val dir = File(context.getExternalFilesDir(null), "photos")
    if (!dir.exists()) dir.mkdirs()
    val file = File(dir, name)
    FileOutputStream(file).use { it.write(bytes) }
    // Also persist to gallery for user visibility
    saveToGallery(bytes, name)
    return file
  }

  private fun saveToGallery(bytes: ByteArray, name: String) {
    try {
      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        val values = android.content.ContentValues().apply {
          put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name)
          put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
          put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,
            "${android.os.Environment.DIRECTORY_PICTURES}/Replate")
          put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
          ?: return
        resolver.openOutputStream(uri)?.use { it.write(bytes) }
        values.clear()
        values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
      } else {
        @Suppress("DEPRECATION")
        val dir = File(
          android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES),
          "Replate"
        )
        dir.mkdirs()
        val file = File(dir, name)
        FileOutputStream(file).use { it.write(bytes) }
        android.media.MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
      }
    } catch (e: Exception) {
      logW("saveToGallery failed: ${e.message}")
    }
  }

  fun getAnchorNode(): AnchorNode? {
    return anchorNode
  }

  fun getCameraController(): ReplateCameraCaptureController? {
    return cameraController
  }

  fun getGravityVector(): Map<String, Double> {
    return gravityVector
  }

  fun updateCircleFocus(targetIndex: Int) {
    // Invert: targetIndex 0 → upper circle (1), targetIndex 1 → lower circle (0)
    val visualCircle = 1 - targetIndex
    if (visualCircle != circleInFocus) {
        if (circleInFocus >= 0) {
            setOpacityToCircle(circleInFocus, 0.5f)
        } else {
            setOpacityToCircle(1 - visualCircle, 0.5f)
        }
        setOpacityToCircle(visualCircle, 1.0f)
        circleInFocus = visualCircle
        performSelectionHaptic()
    }
  }

  fun performSelectionHaptic() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
        vibrator.vibrate(android.os.VibrationEffect.createOneShot(6, 40))
    }
  }

  fun performPhotoTakenHaptic() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
      vibrator.vibrate(android.os.VibrationEffect.createOneShot(10, 50))
    }
  }

  private fun setOpacityToCircle(circleId: Int, opacity: Float) {
    val start = if (circleId == 0) 0 else TOTAL_SPHERES_PER_CIRCLE
    val end = start + TOTAL_SPHERES_PER_CIRCLE
    val dim = opacity < 0.9f
    for (i in start until end) {
      val mat = sphereMaterialInstances[i] ?: continue
      val localIndex = i - start
      val captured = if (circleId == 0) lowerSpheresSet[localIndex] else upperSpheresSet[localIndex]
      // Direct setFloat4 on cached MaterialInstance — no shader compile, pure JNI param update
      if (captured) {
        if (dim) trySet { mat.setFloat4("baseColor", 0f, 0.5f, 0f, 1f) }
        else trySet { mat.setFloat4("baseColor", 0f, 1f, 0f, 1f) }
      } else {
        if (dim) trySet { mat.setFloat4("baseColor", 0.49f, 0.49f, 0.49f, 1f) }
        else trySet { mat.setFloat4("baseColor", 0.98f, 0.98f, 0.98f, 1f) }
      }
    }
  }

  fun checkCameraDistance(deviceTargetInfo: DeviceTargetInfo): Boolean {
    val distance = isCameraWithinRange(deviceTargetInfo.transform, getTransformRootNode() ?: return false)

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

  private fun isCameraWithinRange(cameraTransform: com.google.ar.core.Pose, anchorNode: Node): Int {
    val cameraPosition = Vector3(cameraTransform.tx(), cameraTransform.ty(), cameraTransform.tz())
    val anchorPosition = anchorNode.worldPosition
    val distance = Vector3.subtract(cameraPosition, anchorPosition).length()

    // Scale thresholds by the anchor's current world scale so that if the user
    // pinches to resize the anchor, the valid range grows/shrinks proportionally.
    val scale = anchorNode.worldScale.x.takeIf { it > 0.001f } ?: 1f
    val minDistance = 0.35f * scale
    val maxDistance = 0.70f * scale

    return when {
      distance <= minDistance -> -1
      distance >= maxDistance -> 1
      else -> 0
    }
  }

  private fun sendEvent(eventName: String) {
    logD("Sending JS event=$eventName")
    when (eventName) {
      "onOpenedTutorial" -> ReplateCameraController.consumeOpenedTutorialCallback()?.invoke()
      "onCompletedTutorial" -> ReplateCameraController.consumeCompletedTutorialCallback()?.invoke()
      "onAnchorSet" -> ReplateCameraController.consumeAnchorSetCallback()?.invoke()
      "onCompletedUpperSpheres" -> ReplateCameraController.consumeCompletedUpperSpheresCallback()?.invoke()
      "onCompletedLowerSpheres" -> ReplateCameraController.consumeCompletedLowerSpheresCallback()?.invoke()
      "onTooClose" -> ReplateCameraController.consumeTooCloseCallback()?.invoke()
      "onTooFar" -> ReplateCameraController.consumeTooFarCallback()?.invoke()
      "onBackInRange" -> ReplateCameraController.consumeBackInRangeCallback()?.invoke()
    }
  }

  fun sendPhotoTakenEvent(totalAngles: Int) {
    logD("Sending JS event=onPhotoTaken totalAngles=$totalAngles")
    ReplateCameraController.consumePhotoTakenCallback()?.invoke(totalAngles)
  }

  fun updateSpheres(deviceTargetInfo: DeviceTargetInfo, camera: com.google.ar.core.Camera, callback: (Boolean) -> Unit) {
    val angleDegrees = angleBetweenAnchorXAndCamera(getTransformRootNode() ?: return, camera.pose)
    val sphereIndex = (round(angleDegrees / 5.0f) % 72).toInt()

    var newAngle = false
    // targetIndex is inverted from visual circle: 0→upper highlighted→upper captured, 1→lower highlighted→lower captured
    if (deviceTargetInfo.targetIndex == 0) { // upper circle
        if (!upperSpheresSet[sphereIndex]) {
            upperSpheresSet[sphereIndex] = true
            photosFromDifferentAnglesTaken++
            newAngle = true
            updateSphereColor(72 + sphereIndex, Color(0f, 1f, 0f))
            if (upperSpheresSet.all { it }) {
                sendEvent("onCompletedUpperSpheres")
                if (lowerSpheresSet.all { it }) setTorch(false)
            }
        }
    } else { // targetIndex 1 → lower circle
        if (!lowerSpheresSet[sphereIndex]) {
            lowerSpheresSet[sphereIndex] = true
            photosFromDifferentAnglesTaken++
            newAngle = true
            updateSphereColor(sphereIndex, Color(0f, 1f, 0f))
            if (lowerSpheresSet.all { it }) {
                sendEvent("onCompletedLowerSpheres")
                if (upperSpheresSet.all { it }) setTorch(false)
            }
        }
    }

    if (newAngle) {
      performSelectionHaptic()
    }
    callback(newAngle)
  }

  private fun updateSphereColor(index: Int, color: Color, retryCount: Int = 0) {
    val node = spheresModels.getOrNull(index)
    if (node == null) {
      if (retryCount < 10) postDelayed({ updateSphereColor(index, color, retryCount + 1) }, 150)
      return
    }
    val mat = sphereMaterialInstances[index]
    if (mat != null) {
      val circleId = if (index < TOTAL_SPHERES_PER_CIRCLE) 0 else 1
      val isActive = circleInFocus == circleId
      if (isActive) trySet { mat.setFloat4("baseColor", 0f, 1f, 0f, 1f) }
      else trySet { mat.setFloat4("baseColor", 0f, 0.5f, 0f, 1f) }
    } else {
      makeUnlitMaterial(color, 1f) { node.renderable?.material = it }
    }
    animateSphereHighlight(node)
  }

  /**
   * Loads the custom unlit material from assets and delivers it via [onReady].
   * The unlit shading model ignores all scene lighting — colour is always fully visible.
   * [opacity] modulates the alpha so dim/bright states still work (0.5 = dim, 1.0 = full).
   */
  private fun makeUnlitMaterial(color: Color, opacity: Float, onReady: (Material) -> Unit) {
    try {
      val bytes = context.assets.open("unlit_color.filamat").use { it.readBytes() }
      val buffer = java.nio.ByteBuffer.wrap(bytes)
      Material.builder()
        .setSource(buffer)
        .build()
        .thenAccept { material: Material ->
          trySet { material.setFloat4("baseColor", color.r * opacity, color.g * opacity, color.b * opacity, 1f) }
          trySet { material.setFloat(MaterialFactory.MATERIAL_METALLIC, 0f) }
          trySet { material.setFloat(MaterialFactory.MATERIAL_ROUGHNESS, 1f) }
          trySet { material.setFloat(MaterialFactory.MATERIAL_REFLECTANCE, 0f) }
          onReady(material)
        }
        .exceptionally { throwable ->
          logE("Failed to build unlit material, falling back to opaque", throwable)
          MaterialFactory.makeOpaqueWithColor(context, color).thenAccept { fallback: Material ->
            applyMarkerMaterialFallback(fallback, color, opacity)
            onReady(fallback)
          }
          null
        }
    } catch (e: Exception) {
      logE("Failed to open unlit_color.filamat, falling back to opaque", e)
      MaterialFactory.makeOpaqueWithColor(context, color).thenAccept { fallback: Material ->
        applyMarkerMaterialFallback(fallback, color, opacity)
        onReady(fallback)
      }
    }
  }

  /** PBR fallback used only when the unlit asset cannot be loaded. */
  private fun applyMarkerMaterialFallback(material: Material, color: Color, opacity: Float) {
    trySet { material.setFloat4(MaterialFactory.MATERIAL_COLOR, color.r, color.g, color.b, opacity) }
    trySet { material.setFloat(MaterialFactory.MATERIAL_METALLIC, 0f) }
    trySet { material.setFloat(MaterialFactory.MATERIAL_ROUGHNESS, 1f) }
  }

  @Deprecated("Use makeUnlitMaterial instead — kept for call-sites not yet migrated.")
  private fun applyMarkerMaterialTuning(material: Material, color: Color, opacity: Float) {
    applyMarkerMaterialFallback(material, color, opacity)
  }

  private inline fun trySet(block: () -> Unit) {
    try { block() } catch (e: Exception) { logW("Material property set failed: ${e.message}") }
  }

  private fun animateSphereHighlight(node: Node) {
    val startScale = node.localScale.y
    ValueAnimator.ofFloat(startScale, GREEN_HEIGHT_SCALE).apply {
      duration = 160L
      interpolator = DecelerateInterpolator()
      addUpdateListener { animator ->
        val scaleY = animator.animatedValue as Float
        node.localScale = Vector3(1f, scaleY, 1f)
      }
      start()
    }
  }

  private fun angleBetweenAnchorXAndCamera(anchor: Node, cameraTransform: com.google.ar.core.Pose): Float {
    val anchorTransform = getAnchorPose(anchor) ?: return 0f
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
      bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
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
      val now = SystemClock.elapsedRealtime()
      if (now - lastGravityLogTimeMs >= 1000L) {
        Log.d(TAG, "Gravity vector => x=${gravityVector["x"]}, y=${gravityVector["y"]}, z=${gravityVector["z"]}")
        lastGravityLogTimeMs = now
      }
    }
  }

  override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {
    // Not used
  }

  // ------------------------------------------------------------------------
  // Lifecycle Management
  // ------------------------------------------------------------------------
  
  private var savedOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

  private fun lockPortrait() {
    val activity = getActivity() ?: return
    savedOrientation = activity.requestedOrientation
    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    logI("Orientation locked to portrait")
  }

  private fun restoreOrientation() {
    val activity = getActivity() ?: return
    activity.requestedOrientation = savedOrientation
    logI("Orientation restored to $savedOrientation")
  }

  private fun getActivity(): Activity? {
    var ctx = context
    while (ctx is android.content.ContextWrapper) {
      if (ctx is Activity) return ctx
      ctx = ctx.baseContext
    }
    return null
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    isViewAttached = true
    logI("LIFECYCLE onAttachedToWindow viewSize=${width}x${height} arInitialized=$arInitialized")
    (context as? ThemedReactContext)?.addLifecycleEventListener(this)
    setupArFragmentWhenReady()
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    logI("onDetachedFromWindow")
    isViewAttached = false
    (context as? ThemedReactContext)?.removeLifecycleEventListener(this)
    removeSetupLayoutListener()
    pauseSession()
    cleanupResources()
    if (instance === this) {
      instance = null
    }
  }

  // LifecycleEventListener — mirrors iOS setupAppStateObservers / appDidEnterBackground / appWillEnterForeground
  override fun onHostResume() {
    logI("LIFECYCLE onHostResume arInitialized=$arInitialized isSessionPaused=$isSessionPaused isViewAttached=$isViewAttached")
    if (arInitialized) {
      resumeSession()
    }
  }

  override fun onHostPause() {
    logI("onHostPause")
    pauseSession()
  }

  override fun onHostDestroy() {
    logI("onHostDestroy")
    cleanupResources()
  }
  
  /**
   * Pauses the AR session and unregisters sensors to prevent overheating
   */
  private fun pauseSession() {
    if (isSessionPaused) {
      logD("pauseSession ignored: already paused")
      return
    }
    val sceneView = getArSceneViewOrNull()
    if (sceneView == null) {
      logD("pauseSession: ArSceneView not ready, forcing paused state")
      unregisterSensorListener()
      isSessionPaused = true
      return
    }
    
    try {
      finalizeAnchorDragIfNeeded()
      logI("Pausing AR session")
      sceneView.scene.removeOnUpdateListener(this)
      sceneView.pause()
      unregisterSensorListener()
      
      isSessionPaused = true
      captureActive = false
      logI("AR session paused captureActive=false")
    } catch (e: Exception) {
      logE("Error pausing AR session", e)
    }
  }
  
  /**
   * Resumes the AR session and re-registers sensors
   */
  private fun resumeSession() {
    if (!isViewAttached) {
      logD("resumeSession ignored: view not attached")
      return
    }
    if (!::arFragment.isInitialized) {
      logW("resumeSession requested but arFragment is not initialized, triggering setup")
      setupArFragmentWhenReady()
      scheduleResumeRetry(100)
      return
    }
    if (!arInitialized) {
      logW("resumeSession postponed: AR scene not initialized yet")
      scheduleResumeRetry(100)
      return
    }
    if (!isSessionPaused) {
      logD("resumeSession ignored: already resumed")
      return
    }
    logI("RESUME [start]")

    val reactContext = context as? ThemedReactContext
    val activity = reactContext?.currentActivity
    if (activity == null) {
      logW("RESUME blocked: currentActivity=null → retry")
      scheduleResumeRetry()
      return
    }
    if (!hasCameraPermission(activity)) {
      ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.CAMERA), 1001)
      logW("RESUME blocked: camera permission missing → retry")
      scheduleResumeRetry()
      return
    }
    if (!ensureArCoreReady(activity)) {
      logW("RESUME blocked: ARCore not ready → retry (scheduled inside ensureArCoreReady)")
      return
    }
    val sceneView = getArSceneViewOrNull()
    if (sceneView == null) {
      logW("RESUME blocked: sceneView=null → retry")
      scheduleResumeRetry(100)
      return
    }
    logI("RESUME [executing] sceneView=${sceneView.width}x${sceneView.height} surfaceValid=${try { sceneView.holder.surface?.isValid } catch (_: Exception) { "err" }}")
    try {
      sceneView.scene.removeOnUpdateListener(this)
      sceneView._lightEstimationConfig = LightEstimationConfig.DISABLED
      sceneView.session?.let { applySessionConfiguration(it) }
      sceneView.resume()
      updatePlaneRendererState(sceneView, planeTrackingEnabled)
      registerSensorListener()
      sceneView.scene.addOnUpdateListener(this)

      isSessionPaused = false
      captureActive = true
      expectingFirstFrame = true
      logI("RESUME [success] isSessionPaused=false captureActive=true")
    } catch (e: CameraNotAvailableException) {
      // If the anchor is already placed, don't close the session — that would lose
      // all node positions. Just wait and retry; tracking will resume when the camera
      // becomes available again (e.g. phone un-covered or brought back to foreground).
      if (anchorLocked) {
        logW("Camera not available but anchor is locked, keeping session alive and retrying")
      } else {
        logE("Camera not available while resuming AR session", e)
        closeArSession()
      }
      isSessionPaused = true
      scheduleResumeRetry()
    } catch (e: SecurityException) {
      logE("Camera permission denied while resuming AR session", e)
      isSessionPaused = true
    } catch (e: Exception) {
      logE("Error resuming AR session", e)
      isSessionPaused = true
    }
  }

  private fun removeSetupLayoutListener() {
    val listener = setupLayoutListener ?: return
    if (viewTreeObserver.isAlive) {
      viewTreeObserver.removeOnGlobalLayoutListener(listener)
    }
    setupLayoutListener = null
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
        logD("Gravity sensor listener registered")
      }
    } catch (e: Exception) {
      logE("Error registering sensor listener", e)
    }
  }
  
  /**
   * Unregisters the gravity sensor listener
   */
  private fun unregisterSensorListener() {
    try {
      sensorManager?.unregisterListener(this)
      logD("Gravity sensor listener unregistered")
    } catch (e: Exception) {
      logE("Error unregistering sensor listener", e)
    }
  }
  
  /**
   * Cleans up all resources including AR session, sensors, and nodes
   */
  private fun cleanupResources() {
    try {
      logI("cleanupResources started")
      setTorch(false)
      unregisterSensorListener()

      if (::arFragment.isInitialized) {
        val sceneView = getArSceneViewOrNull()
        sceneView?.scene?.removeOnUpdateListener(this)
        try {
          sceneView?.pause()
        } catch (e: Exception) {
          Log.w(TAG, "Error pausing ArSceneView during cleanup", e)
        }
      }

      closeArSession()
      
      // Clean up nodes
      if (::arFragment.isInitialized) {
        val scene = getArSceneViewOrNull()?.scene
        anchorNode?.let { node ->
          scene?.removeChild(node)
        }
        anchorContentNode?.let { node ->
          scene?.removeChild(node)
        }
      }
      
      // Clear references
      anchorNode = null
      anchorContentNode = null
      spheresModels.fill(null)
      sphereMaterialInstances.fill(null)
      baseMaterial = null
      focusNode = null
      anchorLocked = false
      planeTrackingEnabled = true
      anchorDetachedForDrag = false
      anchorDragInProgress = false
      arInitialized = false
      selectedCameraSessionHash = null
      gestureRoutingInstalled = false
      isSessionPaused = true
      
      highQualityCapture.stop()
      logI("Resources cleaned up")
    } catch (e: Exception) {
      logE("Error cleaning up resources", e)
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
      logI("resetSession requested")
      if (!::arFragment.isInitialized) {
        logW("resetSession called before fragment init, running setupArFragment")
        setupArFragmentWhenReady()
        return
      }
      val sceneView = getArSceneViewOrNull()
      if (sceneView == null) {
        logW("resetSession postponed: ArSceneView not ready")
        scheduleSetupRetry(200)
        return
      }
      // Clean up existing nodes
      anchorNode?.let { node ->
        sceneView.scene.removeChild(node)
      }
      anchorContentNode?.let { node ->
        sceneView.scene.removeChild(node)
      }
      anchorNode = null
      anchorContentNode = null
      spheresModels.fill(null)
      sphereMaterialInstances.fill(null)
      baseMaterial = null
      focusNode = null
      anchorLocked = false
      planeTrackingEnabled = true
      anchorDetachedForDrag = false
      anchorDragInProgress = false
      captureActive = false
      selectedCameraSessionHash = null
      gestureRoutingInstalled = false

      // Reset booleans
      for (i in upperSpheresSet.indices) {
        upperSpheresSet[i] = false
        lowerSpheresSet[i] = false
      }
      totalPhotosTaken = 0
      photosFromDifferentAnglesTaken = 0
      setTorch(false)

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
      closeArSession()
      isSessionPaused = true
      arInitialized = false
      setupArFragmentWhenReady()
      
      logI("AR session reset successfully")
    } catch (e: Exception) {
      logE("Error resetting AR session", e)
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
