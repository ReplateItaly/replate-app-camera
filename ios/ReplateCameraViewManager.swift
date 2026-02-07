import ARKit
import RealityKit
import SwiftUI
import UIKit
import AVFoundation
import ImageIO
import MobileCoreServices
import CoreMotion
import CoreImage
import Darwin.Mach

// Memory monitoring structure
struct mach_task_basic_info {
    var virtual_size: mach_vm_size_t = 0
    var resident_size: mach_vm_size_t = 0
    var resident_size_max: mach_vm_size_t = 0
    var user_time: time_value_t = time_value_t()
    var system_time: time_value_t = time_value_t()
    var policy: policy_t = 0
    var suspend_count: integer_t = 0
}

// MARK: - RCTViewManager
@objc(ReplateCameraViewManager)
class ReplateCameraViewManager: RCTViewManager {
    override func view() -> ReplateCameraView {
        return ReplateCameraView()
    }

    @objc override static func requiresMainQueueSetup() -> Bool {
        return false
    }
}

// MARK: - UIImage Extension
extension UIImage {
    func rotate(radians: Float) -> UIImage {
        var newSize = CGRect(origin: .zero, size: self.size)
            .applying(CGAffineTransform(rotationAngle: CGFloat(radians))).size
        newSize.width = floor(newSize.width)
        newSize.height = floor(newSize.height)

        UIGraphicsBeginImageContextWithOptions(newSize, false, self.scale)
        guard let context = UIGraphicsGetCurrentContext() else { return self }

        context.translateBy(x: newSize.width / 2, y: newSize.height / 2)
        context.rotate(by: CGFloat(radians))
        self.draw(in: CGRect(x: -self.size.width / 2,
                            y: -self.size.height / 2,
                            width: self.size.width,
                            height: self.size.height))

        let newImage = UIGraphicsGetImageFromCurrentImageContext() ?? self
        UIGraphicsEndImageContext()

        return newImage
    }
}

// MARK: - ReplateCameraView
class ReplateCameraView: UIView, ARSessionDelegate {
  // MARK: - Static Properties
  private static let lock = NSLock()
  public static let arQueue = DispatchQueue(label: "com.replate.ar", qos: .userInteractive)

  // AR View and Core Components
  static var arView: ARView!
  static var anchorEntity: AnchorEntity?
  // Lock plane detection as soon as anchor is placed
  static var anchorLocked: Bool = false
  static var model: Entity!
  static var sessionId: UUID!
  static var motionManager: CMMotionManager!
  static var gravityVector: [String : Double] = [:]
  
  // Track if view is currently attached to window
  private var isViewAttached = false

  // Tap handling state
  private static var isProcessingTap = false
  private static var lastTapTime: TimeInterval = 0
  private static let tapDebounceInterval: TimeInterval = 0.5
  /// Core Image context for rendering CIImages
  static let ciContext = CIContext()
  /// AR focus indicator
  static var focusEntity: FocusEntity?
  static var INSTANCE: ReplateCameraView!

  // Scene Configuration
  static var width = CGFloat(0)
  static var height = CGFloat(0)
  static var isPaused = false

  // Sphere Properties
  static var spheresModels: [ModelEntity] = []
  static var upperSpheresSet = [Bool](repeating: false, count: 72)
  static var lowerSpheresSet = [Bool](repeating: false, count: 72)
  static var sphereRadius = Float(0.004)
  static var spheresRadius = Float(0.13)
  static var sphereAngle = Float(5)
  static var spheresHeight = Float(0.10)
  static var distanceBetweenCircles = Float(0.10)
  /// Prototype entity for GPU instancing of spheres
  static var spherePrototype: ModelEntity?

  // Focus and Navigation
  static var focusModel: Entity!
  static var circleInFocus = 0
  static var dragSpeed = CGFloat(7000)

  // Statistics
  static var totalPhotosTaken = 0
  static var photosFromDifferentAnglesTaken = 0

  // Thread Safety
  private static var isResetting = false
  private static var isSessionActive = false

  // MARK: - Initialization
  override init(frame: CGRect) {
    super.init(frame: frame)
    setupInitialState()
  }

  required init?(coder: NSCoder) {
    super.init(coder: coder)
    setupInitialState()
  }

  private func setupInitialState() {
    requestCameraPermissions()
    ReplateCameraView.INSTANCE = self
    setupAppStateObservers()
    isViewAttached = true
  }

    // MARK: - Layout
    override func layoutSubviews() {
        super.layoutSubviews()
        Self.lock.lock()
        defer { Self.lock.unlock() }

        ReplateCameraView.width = frame.width
        ReplateCameraView.height = frame.height
    }

    override func removeFromSuperview() {
        print("[ReplateCameraView] View being removed from superview - cleaning up AR session")
        isViewAttached = false
        pauseSession()
        cleanupAppStateObservers()
        super.removeFromSuperview()
    }

    // MARK: - AR Setup and Configuration
    static func setupAR() {
        arQueue.async {
            guard let instance = INSTANCE else { return }

            let configuration = ARWorldTrackingConfiguration()
            configuration.isLightEstimationEnabled = true
            configuration.planeDetection = .horizontal

            DispatchQueue.main.async {
                configureARView(configuration)
                addRecognizers()
                isSessionActive = true
                print("[ReplateCameraView] AR session started")
            }
        }
    }

    private static func configureARView(_ configuration: ARWorldTrackingConfiguration) {
        configureRenderOptions()
        configureVideoFormat(configuration)

        arView.session.run(configuration)
        sessionId = arView.session.identifier
    }

    private static func configureRenderOptions() {
        let renderOptions: [ARView.RenderOptions] = [
            .disableMotionBlur,
            .disableCameraGrain,
            .disableAREnvironmentLighting,
            .disableHDR,
            .disableFaceMesh,
            .disableGroundingShadows,
            .disablePersonOcclusion
        ]

        renderOptions.forEach { arView.renderOptions.insert($0) }
    }

    private static func configureVideoFormat(_ configuration: ARWorldTrackingConfiguration) {
        if #available(iOS 16.0, *) {
            configuration.videoFormat = ARWorldTrackingConfiguration.recommendedVideoFormatForHighResolutionFrameCapturing
                ?? ARWorldTrackingConfiguration.recommendedVideoFormatFor4KResolution
                ?? highestResolutionFormat()
        } else {
            configuration.videoFormat = highestResolutionFormat()
        }
    }

    private static func highestResolutionFormat() -> ARConfiguration.VideoFormat {
        return ARWorldTrackingConfiguration.supportedVideoFormats.max(by: { format1, format2 in
            let resolution1 = format1.imageResolution.width * format1.imageResolution.height
            let resolution2 = format2.imageResolution.width * format2.imageResolution.height
            return resolution1 < resolution2
        })!
    }

    // MARK: - Gesture Recognition
    static func addRecognizers() {
        guard let instance = INSTANCE else { return }

        let recognizers = [
          UITapGestureRecognizer(target: instance, action: #selector(instance.viewTapped)),
            UIPanGestureRecognizer(target: instance, action: #selector(instance.handlePan)),
            UIPinchGestureRecognizer(target: instance, action: #selector(instance.handlePinch))
        ]

        recognizers.forEach { arView.addGestureRecognizer($0) }
    }

  @objc func viewTapped(_ recognizer: UITapGestureRecognizer) {
    print("VIEW TAPPED")

    // Thread-safe tap handling with debouncing
    Self.lock.lock()
    defer { Self.lock.unlock() }

    // Check if we're already processing a tap
    if ReplateCameraView.isProcessingTap {
      print("Tap ignored - already processing")
      return
    }

    // Debounce rapid taps
    let currentTime = Date().timeIntervalSince1970
    if currentTime - ReplateCameraView.lastTapTime < ReplateCameraView.tapDebounceInterval {
      print("Tap ignored - too soon after last tap")
      return
    }

    // Check if anchor already exists (simplified condition)
    if ReplateCameraView.anchorEntity != nil {
      print("Tap ignored - anchor already set")
      return
    }

    // Set processing flags
    ReplateCameraView.isProcessingTap = true
    ReplateCameraView.lastTapTime = currentTime

    // Place anchor at the focus entity's current transform
    guard let focus = ReplateCameraView.focusEntity else {
      ReplateCameraView.isProcessingTap = false
      return
    }

    let focusTransform = focus.transformMatrix(relativeTo: nil)
    // Freeze anchoring to the initial world transform so it stays put even if
    // the detected plane is temporarily lost.
    let anchor = AnchorEntity(world: focusTransform)
    var anchoring = AnchoringComponent(.world(transform: focusTransform))
    if #available(iOS 18.0, *) {
      anchoring.trackingMode = .once
    }
    anchor.anchoring = anchoring
    print("ANCHOR FOUND\n", anchor.transform)

    // Fire callback
    let callback = ReplateCameraController.anchorSetCallback
    if (callback != nil) {
      callback!([])
      ReplateCameraController.anchorSetCallback = nil
    }

    // Set the anchor
    ReplateCameraView.anchorEntity = anchor
    ReplateCameraView.anchorLocked = true
    createSpheres(y: ReplateCameraView.spheresHeight)
    createSpheres(y: ReplateCameraView.distanceBetweenCircles + ReplateCameraView.spheresHeight)
    createFocusSphere()
    guard let anchorEntity = ReplateCameraView.anchorEntity else {
      ReplateCameraView.isProcessingTap = false
      return
    }
    ReplateCameraView.arView.scene.anchors.append(anchorEntity)

    // --- STOP plane detection after placement ---

let newConfig = ARWorldTrackingConfiguration()
newConfig.isLightEstimationEnabled = true

// IMPORTANT: disable planes
newConfig.planeDetection = []

// Keep world tracking WITHOUT resetting anchor
ReplateCameraView.arView.session.run(
    newConfig,
    options: [] // keep existing anchors so the guide stays locked in world space
)


    // Hide the focus reticle once an anchor is set
    // Remove focus entity completely after placement
ReplateCameraView.focusEntity?.setAutoUpdate(to: false)
ReplateCameraView.focusEntity?.destroy()
ReplateCameraView.focusEntity = nil

    // Reset processing flag
    ReplateCameraView.isProcessingTap = false
  }

  @objc private func handlePan(_ gestureRecognizer: UIPanGestureRecognizer) {
    print("handle pan")
    guard let sceneView = gestureRecognizer.view as? ARView else {
      return
    }
    guard let anchorEntity = ReplateCameraView.anchorEntity else { return }
    let cameraTransform = sceneView.cameraTransform.matrix // Get the 4x4 matrix
    print("passed guard")

    if gestureRecognizer.state == .changed {
      print("triggered")
      let translation = gestureRecognizer.translation(in: sceneView)
      print(translation)

      // Extract forward and right vectors from the camera transform matrix
      let forward = SIMD3<Float>(-cameraTransform.columns.2.x, 0, -cameraTransform.columns.2.z) // Assuming Y is up
      let right = SIMD3<Float>(cameraTransform.columns.0.x, 0, cameraTransform.columns.0.z) // Assuming Y is up

      // Normalize the vectors
      let forwardNormalized = normalize(forward)
      let rightNormalized = normalize(right)

      // Calculate the adjusted movement based on user input and camera orientation
      // Invert the vertical translation (y-axis)
      let adjustedMovement = SIMD3<Float>(
        x: Float(translation.x) * rightNormalized.x + Float(translation.y) * forwardNormalized.x,
        y: 0, // Assuming you want to keep the movement in the horizontal plane
        z: -Float(translation.x) * rightNormalized.z - Float(translation.y) * forwardNormalized.z // Invert the z movement
      )

      let initialPosition = anchorEntity.position
      ReplateCameraView.anchorEntity?.position = initialPosition + adjustedMovement / Float(ReplateCameraView.dragSpeed)

      gestureRecognizer.setTranslation(.zero, in: sceneView)
    }
  }

  @objc func handlePinch(_ gestureRecognizer: UIPinchGestureRecognizer) {
    guard let sceneView = gestureRecognizer.view as? ARView else {
      return
    }
    switch gestureRecognizer.state {
    case .changed:
      DispatchQueue.main.async {
          guard let anchor = ReplateCameraView.anchorEntity else { return }
          // Apply uniform scale to the entire anchor entity instead of recreating spheres
          let scaleFactor = Float(gestureRecognizer.scale)
          anchor.scale *= SIMD3<Float>(repeating: scaleFactor)
          // Reset recognizer scale to avoid cumulative scaling
          gestureRecognizer.scale = 1.0
      }
    default:
      break
    }
  }

    // MARK: - Entity Management

  func createFocusSphere() {
    DispatchQueue.main.async {
      let sphereRadius = ReplateCameraView.sphereRadius * 1.5

      // Generate the first sphere mesh
      let sphereMesh1 = MeshResource.generateSphere(radius: sphereRadius)

      // Create the first sphere entity with initial material
      let sphereEntity1 = ModelEntity(mesh: sphereMesh1, materials: [SimpleMaterial(color: .green.withAlphaComponent(1), roughness: 1, isMetallic: false)])

      // Set the position for the first sphere entity
      sphereEntity1.position = SIMD3(x: 0, y: ReplateCameraView.spheresHeight, z: 0)

      // Generate the second sphere mesh
      let sphereMesh2 = MeshResource.generateSphere(radius: sphereRadius)

      // Create the second sphere entity with initial material
      let sphereEntity2 = ModelEntity(mesh: sphereMesh2, materials: [SimpleMaterial(color: .green.withAlphaComponent(1), roughness: 1, isMetallic: false)])

      // Set the position for the second sphere entity
      sphereEntity2.position = SIMD3(x: 0, y: ReplateCameraView.spheresHeight + ReplateCameraView.distanceBetweenCircles, z: 0)


      // Update the material of the sphere entities
      sphereEntity1.model?.materials = [SimpleMaterial(color: .green.withAlphaComponent(1), roughness: 1, isMetallic: false)]
      sphereEntity2.model?.materials = [SimpleMaterial(color: .green.withAlphaComponent(1), roughness: 1, isMetallic: false)]

      let baseOverlayEntity = self.loadModel(named: "center.obj")
      baseOverlayEntity.scale *= 12
      baseOverlayEntity.model?.materials = [SimpleMaterial(color: .white.withAlphaComponent(0.3), roughness: 1, isMetallic: false),
                                            SimpleMaterial(color: .white.withAlphaComponent(0.7), roughness: 1, isMetallic: false),
                                            SimpleMaterial(color: .white.withAlphaComponent(0.5), roughness: 1, isMetallic: false)]

      // Create a parent entity to hold both spheres
      let parentEntity = Entity()
      parentEntity.addChild(sphereEntity1)
      parentEntity.addChild(sphereEntity2)
      parentEntity.addChild(baseOverlayEntity)

      // Set the focus model for the global state
      ReplateCameraView.focusModel = parentEntity

      // Safely add the parent entity to the anchor entity
      ReplateCameraView.anchorEntity?.addChild(parentEntity)
    }
  }

    func createSpheres(y: Float) {
        DispatchQueue.main.async {
            guard let anchor = ReplateCameraView.anchorEntity else { return }
            // Lazily initialize a single prototype for instancing
            if ReplateCameraView.spherePrototype == nil {
                let mesh = MeshResource.generateSphere(radius: ReplateCameraView.sphereRadius)
                let material = SimpleMaterial(color: .white.withAlphaComponent(1), roughness: 1, isMetallic: false)
                ReplateCameraView.spherePrototype = ModelEntity(mesh: mesh, materials: [material])
            }
            guard let prototype = ReplateCameraView.spherePrototype else { return }
            let radius = ReplateCameraView.spheresRadius
            for i in 0..<72 {
                let angle = Float(i) * (Float.pi / 180) * 5
                let position = SIMD3(
                    radius * cos(angle),
                    y,
                    radius * sin(angle)
                )
                // Clone the prototype instead of regenerating mesh/material
                let sphereInstance = prototype.clone(recursive: false)
                sphereInstance.position = position
                ReplateCameraView.spheresModels.append(sphereInstance)
                anchor.addChild(sphereInstance)
            }
        }
    }

    func createSphere(position: SIMD3<Float>) -> ModelEntity {
        let sphereMesh = MeshResource.generateSphere(radius: ReplateCameraView.sphereRadius)
        let material = SimpleMaterial(color: .white.withAlphaComponent(1), roughness: 1, isMetallic: false)
        let sphere = ModelEntity(mesh: sphereMesh, materials: [material])
        sphere.position = position
        return sphere
    }





  func createDot(at position: SIMD3<Float>) -> ModelEntity {
    // Define the dimensions of the box
    let width: Float = 0.005  // 1 cm width
    let height: Float = 0.0001  // Very small height to make it almost flat
    let depth: Float = width // 1 cm depth
    let cornerRadius: Float = width/2.0  // Half of the width to make it look like a circle

    // Generate a box with rounded corners
    let cylinderMesh = MeshResource.generateBox(size: [width, height, depth], cornerRadius: 1)

    // Create the material
    let material = SimpleMaterial(color: .white, roughness: 1, isMetallic: false)

    // Create the entity
    let circleEntity = ModelEntity(mesh: cylinderMesh, materials: [material])
    circleEntity.position = position
    return circleEntity
  }

    // MARK: - Reset Functionality
    @objc static func reset() {
        arQueue.async {
            Self.lock.lock()
            defer { Self.lock.unlock() }

            if isResetting { return }
            isResetting = true

            DispatchQueue.main.async {
                tearDownARSession()
                resetProperties()
                setupNewARView()
                isResetting = false
            }
        }
    }

    static func destroy() {
    Self.lock.lock()
    defer { Self.lock.unlock() }

    print("[ReplateCameraView] HARD destroy start")

    // 1) Stop AR session and detach view
    tearDownARSession()

    // 2) Reset static state and arrays
    resetProperties()

    // 3) Release focus + entities
    focusEntity = nil
    focusModel = nil
    anchorEntity = nil
    model = nil

    // 4) Release heavy system objects
    arView?.session.delegate = nil
    arView = nil
    motionManager = nil

    // 5) Kill singleton reference
    INSTANCE = nil

    // Force autorelease drain
    autoreleasepool { }

    print("[ReplateCameraView] HARD destroy completed")
}


    static func tearDownARSession() {
        guard isSessionActive else { return }

        arView?.session.pause()
        arView?.session.delegate = nil
        arView?.scene.anchors.removeAll()
        arView?.removeFromSuperview()
        arView?.window?.resignKey()

        // Properly terminate the session
        if let session = arView?.session {
            session.pause()
            // Clear all session data
            arView?.session.delegate = nil
        }

        // Stop motion manager
        motionManager?.stopDeviceMotionUpdates()

        // Mark session as inactive
        isSessionActive = false

        print("[ReplateCameraView] AR session properly terminated")
    }

    private static func resetProperties() {
        anchorEntity = nil
        anchorLocked = false
        model = nil
        spheresModels.removeAll()
        upperSpheresSet = [Bool](repeating: false, count: 72)
        lowerSpheresSet = [Bool](repeating: false, count: 72)
        totalPhotosTaken = 0
        photosFromDifferentAnglesTaken = 0
        sphereRadius = 0.004
        spheresRadius = 0.13
        sphereAngle = 5
        spheresHeight = 0.10
        dragSpeed = 7000
        gravityVector = [:]
        ReplateCameraView.spherePrototype = nil
        ReplateCameraController.wasOutOfRange = false

        // Reset tap handling state
        isProcessingTap = false
        lastTapTime = 0

        // Reset session state
        isSessionActive = false

        // Clean up temporary files to free disk space
        cleanupTemporaryFiles()
    }

    private static func cleanupTemporaryFiles() {
        let temporaryDirectoryURL = URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
        do {
            let fileURLs = try FileManager.default.contentsOfDirectory(at: temporaryDirectoryURL,
                                                                      includingPropertiesForKeys: nil,
                                                                      options: [])
            for fileURL in fileURLs {
                if fileURL.pathExtension.lowercased() == "jpg" {
                    try? FileManager.default.removeItem(at: fileURL)
                }
            }
        } catch {
            print("Error cleaning temporary files: \(error)")
        }
    }

    private static func setupNewARView() {
        guard let instance = INSTANCE else { return }

        arView = ARView(frame: CGRect(x: 0, y: 0, width: width, height: height))
        arView.backgroundColor = instance.hexStringToUIColor(hexColor: "#32a852")
        instance.addSubview(arView)
        arView.session.delegate = instance
        // Show a light-colored circular focus reticle
        DispatchQueue.main.async {
          focusEntity = FocusEntity(
            on: arView,
            style: .classic(color: .white)
            )
        }

        motionManager = CMMotionManager()
        if motionManager.isDeviceMotionAvailable {
          ReplateCameraView.INSTANCE.startDeviceMotionUpdates()
        } else {
          print("Device motion is not available")
        }
        setupAR()
    }

  // MARK: - Utilities functions
  func requestCameraPermissions() {

    if AVCaptureDevice.authorizationStatus(for: .video) == .authorized {
      print("Camera permissions already granted")
    } else {
      AVCaptureDevice.requestAccess(for: .video, completionHandler: { (granted: Bool) in
        if granted {
          print("Camera permissions granted")
        } else {
          print("Camera permissions denied")
        }
      })
    }
  }

  func loadModel(named name: String) -> ModelEntity {
    do{
      return try ModelEntity.loadModel(named: name)
    }catch{
      print("Cannot load model \(name)")
      let baseOverlayMesh = MeshResource.generateBox(size: [ReplateCameraView.spheresRadius * 2, 0.01, ReplateCameraView.spheresRadius * 2], cornerRadius: 1)
      let baseOverlayEntity = ModelEntity(mesh: baseOverlayMesh, materials: [SimpleMaterial(color: .white.withAlphaComponent(0.5), roughness: 1, isMetallic: false)])

      baseOverlayEntity.position = SIMD3(x: 0, y: 0.01, z: 0)
      return baseOverlayEntity
    }
  }

  @objc var color: String = "" {
    didSet {
      self.backgroundColor = hexStringToUIColor(hexColor: color)
    }
  }

  func hexStringToUIColor(hexColor: String) -> UIColor {
    let stringScanner = Scanner(string: hexColor)

    if (hexColor.hasPrefix("#")) {
      stringScanner.scanLocation = 1
    }
    var color: UInt32 = 0
    stringScanner.scanHexInt32(&color)

    let r = CGFloat(Int(color >> 16) & 0x000000FF)
    let g = CGFloat(Int(color >> 8) & 0x000000FF)
    let b = CGFloat(Int(color) & 0x000000FF)

    return UIColor(red: r / 255.0, green: g / 255.0, blue: b / 255.0, alpha: 1)
  }

  internal func session(_ session: ARSession, didAdd anchors: [ARAnchor]) {
    // FocusEntity automatically highlights detected planes.
  }

  func session(_ session: ARSession, didUpdate frame: ARFrame) {
    // Handle AR frame updates
    // You can perform actions here, such as updating the AR content based on the camera frame
  }

  func sessionWasInterrupted(_ session: ARSession) {
    print("SESSION INTERRUPTED")
    ReplateCameraView.motionManager.stopDeviceMotionUpdates()

    // Properly pause the session to save resources
    session.pause()
    ReplateCameraView.isSessionActive = false
    print("[ReplateCameraView] AR session paused due to interruption")
  }

  func sessionInterruptionEnded(_ session: ARSession) {
    print("SESSION RESUMED")

    // Only resume if we should still be active and view is attached
    if !ReplateCameraView.isResetting && ReplateCameraView.INSTANCE != nil && ReplateCameraView.INSTANCE.isViewAttached {
      ReplateCameraView.INSTANCE.startDeviceMotionUpdates()
      ReplateCameraView.isSessionActive = true
      print("[ReplateCameraView] AR session resumed after interruption")
    } else {
      print("[ReplateCameraView] View not attached - skipping AR session resume after interruption")
    }
  }

  func generateImpactFeedback(strength: UIImpactFeedbackGenerator.FeedbackStyle) {
    do{
      let impactFeedbackGenerator = try UIImpactFeedbackGenerator(style: strength)
      impactFeedbackGenerator.prepare()
      impactFeedbackGenerator.impactOccurred()
    }catch{
      print("Error when sending feedback")
    }
  }

  func startDeviceMotionUpdates() {
    ReplateCameraView.motionManager.deviceMotionUpdateInterval = 0.1 // Update interval in seconds
    ReplateCameraView.motionManager.startDeviceMotionUpdates(to: .main) { (deviceMotion, error) in
      if let deviceMotion = deviceMotion {
        let gravity = deviceMotion.gravity
        ReplateCameraView.gravityVector = [
          "x": gravity.x,
          "y": gravity.y,
          "z": gravity.z
        ]
        print("Gravity vector: x = \(gravity.x), y = \(gravity.y), z = \(gravity.z)")
      }
    }
  }

  // MARK: - Lifecycle Management
  private func setupAppStateObservers() {
    NotificationCenter.default.addObserver(
      self,
      selector: #selector(appDidEnterBackground),
      name: UIApplication.didEnterBackgroundNotification,
      object: nil
    )

    NotificationCenter.default.addObserver(
      self,
      selector: #selector(appWillEnterForeground),
      name: UIApplication.willEnterForegroundNotification,
      object: nil
    )
  }

  @objc private func appDidEnterBackground() {
    print("[ReplateCameraView] App entered background - pausing AR session")
    pauseSession()
  }

  @objc private func appWillEnterForeground() {
    print("[ReplateCameraView] App entering foreground")
    // Only resume if the view is still attached and active
    if isViewAttached && ReplateCameraView.INSTANCE != nil {
      print("[ReplateCameraView] View is attached - resuming AR session")
      resumeSession()
    } else {
      print("[ReplateCameraView] View not attached - skipping AR session resume")
    }
  }

func pauseSession() {
    guard ReplateCameraView.isSessionActive else { return }

    ReplateCameraView.arView?.session.pause()
    ReplateCameraView.motionManager?.stopDeviceMotionUpdates()
    ReplateCameraView.isSessionActive = false
    print("[ReplateCameraView] AR session paused")
  }

func resumeSession() {
    guard !ReplateCameraView.isSessionActive && !ReplateCameraView.isResetting else { return }
    guard ReplateCameraView.arView != nil else { return }
    
    // Additional guard: only resume if view is still attached
    guard isViewAttached && ReplateCameraView.INSTANCE != nil else {
      print("[ReplateCameraView] View not attached - refusing to resume AR session")
      return
    }

    let configuration = ARWorldTrackingConfiguration()
    configuration.isLightEstimationEnabled = true

    // If an anchor has been placed, keep tracking but stop looking for new planes to avoid
    // the guide drifting when the reference plane is temporarily lost.
    if ReplateCameraView.anchorLocked {
      configuration.planeDetection = []
      print("[ReplateCameraView] Resuming with planes disabled (anchor locked)")
    } else {
      configuration.planeDetection = .horizontal
    }

    ReplateCameraView.arView?.session.run(configuration)
    startDeviceMotionUpdates()
    ReplateCameraView.isSessionActive = true
    print("[ReplateCameraView] AR session resumed")
  }

  private func cleanupAppStateObservers() {
    NotificationCenter.default.removeObserver(
      self,
      name: UIApplication.didEnterBackgroundNotification,
      object: nil
    )
    NotificationCenter.default.removeObserver(
      self,
      name: UIApplication.willEnterForegroundNotification,
      object: nil
    )
    print("[ReplateCameraView] App state observers cleaned up")
  }

  deinit {
    isViewAttached = false
    cleanupAppStateObservers()
    pauseSession()
    print("[ReplateCameraView] Component deinit - AR session cleaned up")
  }

}



// MARK: - Supporting Types
enum ARError: Error {
    case noAnchor
    case invalidAnchor
    case notInFocus
    case captureError
    case tooManyImages
    case processingError
    case savingError
    case transformError
    case lightingError
    case notInRange
    case unknown

    var localizedDescription: String {
        switch self {
        case .noAnchor: return "[ReplateCameraController] No anchor set yet"
        case .invalidAnchor: return "[ReplateCameraController] AnchorNode is not valid"
        case .notInFocus: return "[ReplateCameraController] Object not in focus"
        case .captureError: return "[ReplateCameraController] Error capturing image"
        case .tooManyImages: return "[ReplateCameraController] Too many images and the last one's not from a new angle"
        case .processingError: return "[ReplateCameraController] Error processing image"
        case .savingError: return "[ReplateCameraController] Error saving photo"
        case .transformError: return "[ReplateCameraController] Camera transform data not available"
        case .lightingError: return "[ReplateCameraController] Image too dark"
        case .notInRange: return "[ReplateCameraController] Camera not in range"
        case .unknown: return "[ReplateCameraController] Unknown error occurred"
        }
    }
}

struct DeviceTargetInfo {
    let isInFocus: Bool
    let targetIndex: Int
    let transform: simd_float4x4
    let cameraPosition: SIMD3<Float>
    let deviceDirection: SIMD3<Float>

    var isValidTarget: Bool {
        return targetIndex != -1
    }
}

class SafeCallbackHandler {
    private let resolver: RCTPromiseResolveBlock
    private let rejecter: RCTPromiseRejectBlock
    var hasCalledBack = false
    private let lock = NSLock()

    init(resolver: @escaping RCTPromiseResolveBlock, rejecter: @escaping RCTPromiseRejectBlock) {
        self.resolver = resolver
        self.rejecter = rejecter
    }

    func resolve(_ result: Any) {
        lock.lock()
        defer { lock.unlock() }
        guard !hasCalledBack else {
          print("rejecter: Callback already invoked.")
          return
        }
        hasCalledBack = true
        resolver(result)
    }

    func reject(_ error: ARError) {
        lock.lock()
        defer { lock.unlock() }

        guard !hasCalledBack else {
            print("rejecter: Callback already invoked.")
            return
        }
        hasCalledBack = true
        rejecter(
            String(describing: error),
            error.localizedDescription,
            NSError(domain: "ReplateCameraController", code: 0, userInfo: nil)
        )
    }
}

// MARK: - Main Controller
@objc(ReplateCameraController)
class ReplateCameraController: NSObject {
    // MARK: - Static Properties
    private static let lock = NSLock()
    private static let arQueue = DispatchQueue(label: "com.replate.ar.controller", qos: .userInteractive)

    // Configuration Constants
    private static let MIN_DISTANCE: Float = 0.25
    private static let MAX_DISTANCE: Float = 0.55
    private static let ANGLE_THRESHOLD: Float = 0.6
//    private static let TARGET_IMAGE_SIZE = CGSize(width: 2048, height: 1556)
    private static let TARGET_IMAGE_SIZE = CGSize(width: 3072, height: 2304)
    private static let MIN_AMBIENT_INTENSITY: CGFloat = 650

    // Callbacks
    static var completedTutorialCallback: RCTResponseSenderBlock?
    static var anchorSetCallback: RCTResponseSenderBlock?
    static var completedUpperSpheresCallback: RCTResponseSenderBlock?
    static var completedLowerSpheresCallback: RCTResponseSenderBlock?
    static var openedTutorialCallback: RCTResponseSenderBlock?
    static var tooCloseCallback: RCTResponseSenderBlock?
    static var tooFarCallback: RCTResponseSenderBlock?
    static var backInRangeCallback: RCTResponseSenderBlock?

    // State tracking for backInRange callback
    static var wasOutOfRange = false
    // Track if an anchor has been placed to lock plane detection immediately
    static var anchorLocked = false

    // MARK: - Callback Registration Methods
    @objc(registerOpenedTutorialCallback:)
    func registerOpenedTutorialCallback(_ callback: @escaping RCTResponseSenderBlock) {
        Self.lock.lock()
        defer { Self.lock.unlock() }
        ReplateCameraController.openedTutorialCallback = callback
    }

    @objc(registerCompletedTutorialCallback:)
    func registerCompletedTutorialCallback(_ callback: @escaping RCTResponseSenderBlock) {
        Self.lock.lock()
        defer { Self.lock.unlock() }
        ReplateCameraController.completedTutorialCallback = callback
    }

    @objc(registerAnchorSetCallback:)
    func registerAnchorSetCallback(_ callback: @escaping RCTResponseSenderBlock) {
        Self.lock.lock()
        defer { Self.lock.unlock() }
        ReplateCameraController.anchorSetCallback = callback
    }

    @objc(registerCompletedUpperSpheresCallback:)
    func registerCompletedUpperSpheresCallback(_ callback: @escaping RCTResponseSenderBlock) {
        Self.lock.lock()
        defer { Self.lock.unlock() }
        ReplateCameraController.completedUpperSpheresCallback = callback
    }

    @objc(registerCompletedLowerSpheresCallback:)
    func registerCompletedLowerSpheresCallback(_ callback: @escaping RCTResponseSenderBlock) {
        Self.lock.lock()
        defer { Self.lock.unlock() }
        ReplateCameraController.completedLowerSpheresCallback = callback
    }

    @objc(registerTooCloseCallback:)
    func registerTooCloseCallback(_ callback: @escaping RCTResponseSenderBlock) {
        Self.lock.lock()
        defer { Self.lock.unlock() }
        ReplateCameraController.tooCloseCallback = callback
    }

    @objc(registerTooFarCallback:)
    func registerTooFarCallback(_ callback: @escaping RCTResponseSenderBlock) {
        Self.lock.lock()
        defer { Self.lock.unlock() }
        ReplateCameraController.tooFarCallback = callback
    }

    @objc(registerBackInRangeCallback:)
    func registerBackInRangeCallback(_ callback: @escaping RCTResponseSenderBlock) {
        Self.lock.lock()
        defer { Self.lock.unlock() }
        ReplateCameraController.backInRangeCallback = callback
    }

    // MARK: - Public Methods
    @objc(getPhotosCount:rejecter:)
    func getPhotosCount(_ resolver: RCTPromiseResolveBlock, rejecter: RCTPromiseRejectBlock) {
        Self.lock.lock()
        let count = ReplateCameraView.totalPhotosTaken
        Self.lock.unlock()
        resolver(count)
    }

    @objc(isScanComplete:rejecter:)
    func isScanComplete(_ resolver: RCTPromiseResolveBlock, rejecter: RCTPromiseRejectBlock) {
        Self.lock.lock()
        let isComplete = ReplateCameraView.photosFromDifferentAnglesTaken == 144
        Self.lock.unlock()
        resolver(isComplete)
    }

    @objc(getRemainingAnglesToScan:rejecter:)
    func getRemainingAnglesToScan(_ resolver: RCTPromiseResolveBlock, rejecter: RCTPromiseRejectBlock) {
        Self.lock.lock()
        let remaining = 144 - ReplateCameraView.photosFromDifferentAnglesTaken
        Self.lock.unlock()
        resolver(remaining)
    }

    @objc
    func reset() {
        DispatchQueue.main.async {
            ReplateCameraView.reset()
        }
    }

    @objc
    func pauseSession() {
        DispatchQueue.main.async {
            ReplateCameraView.INSTANCE?.pauseSession()
        }
    }

    @objc
    func resumeSession() {
        DispatchQueue.main.async {
            ReplateCameraView.INSTANCE?.resumeSession()
        }
    }

    @objc
    func stopSession() {
        DispatchQueue.main.async {
            ReplateCameraView.tearDownARSession()
        }
    }

    @objc
    func destroySession() {
    DispatchQueue.main.async {
        ReplateCameraView.destroy()
    }
    }

    @objc(getMemoryUsage:rejecter:)
    func getMemoryUsage(_ resolver: RCTPromiseResolveBlock, rejecter: RCTPromiseRejectBlock) {
        var memoryInfo = mach_task_basic_info()
        var count = mach_msg_type_number_t(MemoryLayout<mach_task_basic_info>.size)/4

        let result: kern_return_t = withUnsafeMutablePointer(to: &memoryInfo) {
            $0.withMemoryRebound(to: integer_t.self, capacity: 1) {
                task_info(mach_task_self_,
                         task_flavor_t(MACH_TASK_BASIC_INFO),
                         $0,
                         &count)
            }
        }

        if result == KERN_SUCCESS {
            let memoryUsageMB = Double(memoryInfo.resident_size) / 1024.0 / 1024.0
            resolver(["memoryUsageMB": memoryUsageMB])
        } else {
            resolver(["memoryUsageMB": -1])
        }
    }

    // MARK: - Photo Capture and Processing
    @objc(takePhoto:resolver:rejecter:)
    func takePhoto(_ unlimited: Bool = false, resolver: @escaping RCTPromiseResolveBlock, rejecter: @escaping RCTPromiseRejectBlock) {
        // Add memory pressure check to prevent overwhelming the system
        var memoryInfo = mach_task_basic_info()
        var count = mach_msg_type_number_t(MemoryLayout<mach_task_basic_info>.size)/4

        let result: kern_return_t = withUnsafeMutablePointer(to: &memoryInfo) {
            $0.withMemoryRebound(to: integer_t.self, capacity: 1) {
                task_info(mach_task_self_,
                         task_flavor_t(MACH_TASK_BASIC_INFO),
                         $0,
                         &count)
            }
        }

        // If memory usage is too high, delay the photo taking
        if result == KERN_SUCCESS {
            let memoryUsage = memoryInfo.resident_size
            // If using more than 500MB, add a small delay
            if memoryUsage > 500_000_000 {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.1) {
                    self.executePhotoCapture(unlimited: unlimited, resolver: resolver, rejecter: rejecter)
                }
                return
            }
        }

        executePhotoCapture(unlimited: unlimited, resolver: resolver, rejecter: rejecter)
    }

    private func executePhotoCapture(unlimited: Bool, resolver: @escaping RCTPromiseResolveBlock, rejecter: @escaping RCTPromiseRejectBlock) {
        Self.arQueue.async { [weak self] in
            guard let self = self else { return }

            let callbackHandler = SafeCallbackHandler(resolver: resolver, rejecter: rejecter)

            do {
                try self.validateAndProcessPhoto(unlimited: unlimited, callbackHandler: callbackHandler)
            } catch let error as ARError {
                callbackHandler.reject(error)
            } catch {
                callbackHandler.reject(.unknown)
            }
        }
    }

    private func validateAndProcessPhoto(unlimited: Bool, callbackHandler: SafeCallbackHandler) throws {
        let anchorEntity = try getValidAnchorEntity()
        let deviceTargetInfo = try getDeviceTargetInfo(anchorEntity: anchorEntity)

        if deviceTargetInfo.isValidTarget {
            try processTargetedDevice(deviceTargetInfo: deviceTargetInfo, unlimited: unlimited, callbackHandler: callbackHandler)
        } else {
            throw ARError.notInFocus
        }
    }

    private func getValidAnchorEntity() throws -> AnchorEntity {
        var anchorEntity: AnchorEntity?
      if(Thread.isMainThread){
        anchorEntity = ReplateCameraView.anchorEntity

      }else{
        DispatchQueue.main.sync {
          anchorEntity = ReplateCameraView.anchorEntity
        }
      }


        guard let anchor = anchorEntity else {
            throw ARError.noAnchor
        }

        guard isAnchorNodeValid(anchor) else {
            throw ARError.invalidAnchor
        }

        return anchor
    }

    private func getDeviceTargetInfo(anchorEntity: AnchorEntity) throws -> DeviceTargetInfo {
        guard let frame = ReplateCameraView.arView.session.currentFrame else {
            throw ARError.transformError
        }

        let cameraTransform = frame.camera.transform
        let relativeCameraTransform = getTransformRelativeToAnchor(anchor: anchorEntity, cameraTransform: cameraTransform)

        var anchorPosition: SIMD3<Float>!
      if(Thread.isMainThread){
        anchorPosition = anchorEntity.position(relativeTo: nil)

      }else{
        DispatchQueue.main.sync {
          anchorPosition = anchorEntity.position(relativeTo: nil)
        }
      }


        let cameraPosition = SIMD3<Float>(cameraTransform.columns.3.x, cameraTransform.columns.3.y, cameraTransform.columns.3.z)
        let deviceDirection = normalize(SIMD3<Float>(-cameraTransform.columns.2.x, -cameraTransform.columns.2.y, -cameraTransform.columns.2.z))
        let directionToAnchor = normalize(anchorPosition - cameraPosition)
        let angleToAnchor = acos(dot(deviceDirection, directionToAnchor))

        let targetIndex = determineTargetIndex(angleToAnchor: angleToAnchor, relativeCameraTransform: relativeCameraTransform)

        return DeviceTargetInfo(
            isInFocus: angleToAnchor < Self.ANGLE_THRESHOLD,
            targetIndex: targetIndex,
            transform: relativeCameraTransform,
            cameraPosition: cameraPosition,
            deviceDirection: deviceDirection
        )
    }

    private func determineTargetIndex(angleToAnchor: Float, relativeCameraTransform: simd_float4x4) -> Int {
        guard angleToAnchor < Self.ANGLE_THRESHOLD else { return -1 }

        let spheresHeight = ReplateCameraView.spheresHeight
        let distanceBetweenCircles = ReplateCameraView.distanceBetweenCircles
        let twoThirdsDistance = spheresHeight + distanceBetweenCircles + distanceBetweenCircles/5
        let cameraHeight = relativeCameraTransform.columns.3.y

        return cameraHeight < twoThirdsDistance ? 0 : 1
    }

    private func processTargetedDevice(deviceTargetInfo: DeviceTargetInfo, unlimited: Bool, callbackHandler: SafeCallbackHandler) throws {
        // Capture frame reference early to avoid main thread dependency
        guard let frame = ReplateCameraView.arView?.session.currentFrame else {
            callbackHandler.reject(.captureError)
            return
        }

        // Check lighting conditions
        if let lightEstimate = frame.lightEstimate {
            guard lightEstimate.ambientIntensity >= Self.MIN_AMBIENT_INTENSITY else {
                callbackHandler.reject(.lightingError)
                return
            }
        }

        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }

            self.updateCircleFocus(targetIndex: deviceTargetInfo.targetIndex)
            let isInRange = self.checkCameraDistance(deviceTargetInfo: deviceTargetInfo)
            if !isInRange {
                callbackHandler.reject(.notInRange)
                return
            }

            self.updateSpheres(
                deviceTargetInfo: deviceTargetInfo,
                cameraTransform: deviceTargetInfo.transform
            ) { success in
                if !unlimited && !success {
                    callbackHandler.reject(.tooManyImages)
                    return
                }

                // Process image on background queue to avoid main thread blocking
                ReplateCameraView.arQueue.async {
                    self.processAndSaveImage(frame.capturedImage, callbackHandler: callbackHandler)
                }
            }
        }
    }

    private func updateCircleFocus(targetIndex: Int) {
        if targetIndex != ReplateCameraView.circleInFocus {
            setOpacityToCircle(circleId: ReplateCameraView.circleInFocus, opacity: 0.5)
            setOpacityToCircle(circleId: targetIndex, opacity: 1)
          ReplateCameraView.circleInFocus = targetIndex
          ReplateCameraView.INSTANCE.generateImpactFeedback(strength: .heavy)
        }
    }

  private func checkCameraDistance(deviceTargetInfo: DeviceTargetInfo) -> Bool {
        let distance = isCameraWithinRange(
            cameraTransform: deviceTargetInfo.transform,
            anchorEntity: ReplateCameraView.anchorEntity!
        )

        switch distance {
        case 1:
            ReplateCameraController.wasOutOfRange = true
            ReplateCameraController.tooFarCallback?([])
            ReplateCameraController.tooFarCallback = nil
            return false
        case -1:
            ReplateCameraController.wasOutOfRange = true
            ReplateCameraController.tooCloseCallback?([])
            ReplateCameraController.tooCloseCallback = nil
            return false
        default:
            // Camera is in correct range
            if ReplateCameraController.wasOutOfRange {
                ReplateCameraController.wasOutOfRange = false
                ReplateCameraController.backInRangeCallback?([])
                ReplateCameraController.backInRangeCallback = nil
            }
            return true
        }
    }

  private func processAndSaveImage(_ pixelBuffer: CVPixelBuffer, callbackHandler: SafeCallbackHandler) {
    // Wrap in autoreleasepool to ensure memory is released immediately
    autoreleasepool {
        // Convert to CIImage
        let ciImage = CIImage(cvImageBuffer: pixelBuffer)

        // Create a fresh filter instance to avoid state accumulation
        guard let filter = CIFilter(name: "CILanczosScaleTransform") else {
            callbackHandler.reject(.processingError)
            return
        }

        filter.setValue(ciImage, forKey: kCIInputImageKey)
        filter.setValue(Self.TARGET_IMAGE_SIZE.width / ciImage.extent.width, forKey: kCIInputScaleKey)
        filter.setValue(1.0, forKey: kCIInputAspectRatioKey)

        guard let resized = filter.outputImage,
              let cgImage = ReplateCameraView.ciContext.createCGImage(resized, from: resized.extent)
        else {
            callbackHandler.reject(.processingError)
            return
        }

        let uiImage = UIImage(cgImage: cgImage)
        let rotatedImage = uiImage.rotate(radians: .pi / 2)

        guard let savedURL = saveImageAsJPEG(rotatedImage) else {
            DispatchQueue.main.async {
                callbackHandler.reject(.processingError)
            }
            return
        }

        DispatchQueue.main.async {
            callbackHandler.resolve(savedURL.absoluteString)
        }
    }
  }



 func resizeImage(_ image: CIImage, to targetSize: CGSize) -> CIImage? {
        guard let scaleFilter = CIFilter(name: "CILanczosScaleTransform") else { return nil }

        scaleFilter.setValue(image, forKey: kCIInputImageKey)
        scaleFilter.setValue(targetSize.width / image.extent.width, forKey: kCIInputScaleKey)
        scaleFilter.setValue(1.0, forKey: kCIInputAspectRatioKey)

        return scaleFilter.outputImage
    }

    // MARK: - Entity Management
    func setOpacityToCircle(circleId: Int, opacity: Float) {
        DispatchQueue.main.async {
            for i in 0..<72 {
                let offset = circleId == 0 ? 0 : 72
                guard i + offset < ReplateCameraView.spheresModels.count else { continue }

                let entity = ReplateCameraView.spheresModels[i + offset]
                self.updateEntityMaterial(entity, opacity: opacity)
            }
        }
    }

    private func updateEntityMaterial(_ entity: ModelEntity, opacity: Float) {
        guard let material = entity.model?.materials.first as? SimpleMaterial else { return }

        if #available(iOS 15.0, *) {
            let newMaterial = SimpleMaterial(
                color: material.color.tint.withAlphaComponent(CGFloat(opacity)),
                roughness: 1,
                isMetallic: false
            )
            entity.model?.materials[0] = newMaterial
        }
    }

    private func updateSpheres(deviceTargetInfo: DeviceTargetInfo, cameraTransform: simd_float4x4, completion: @escaping (Bool) -> Void) {
        var completionCalled = false

        func safeCompletion(_ result: Bool) {
            guard !completionCalled else {
                print("Completion already called")
                return
            }
            completionCalled = true
            completion(result)
        }

        // Validate spheres initialization
        guard ReplateCameraView.spheresModels.count >= 144 else {
            print("[updateSpheres] Spheres not fully initialized. Count: \(ReplateCameraView.spheresModels.count)")
            safeCompletion(false)
            return
        }

        // Get anchor
        guard let anchorNode = ReplateCameraView.anchorEntity else {
            print("[updateSpheres] No anchor entity found.")
            safeCompletion(false)
            return
        }

        // Calculate camera metrics
        let cameraDistance = isCameraWithinRange(
            cameraTransform: cameraTransform,
            anchorEntity: anchorNode
        )

        // Calculate sphere index
        let angleDegrees = angleBetweenAnchorXAndCamera(
            anchor: anchorNode,
            cameraTransform: cameraTransform
        )
        let sphereIndex = max(Int(round(angleDegrees / 5.0)), 0) % 72

        DispatchQueue.main.async {
            self.processSphereUpdate(
                sphereIndex: sphereIndex,
                targetIndex: deviceTargetInfo.targetIndex,
                completion: safeCompletion
            )
        }
    }

    private func processSphereUpdate(sphereIndex: Int, targetIndex: Int, completion: @escaping (Bool) -> Void) {
        var mesh: ModelEntity?
        var newAngle = false
        var callback: RCTResponseSenderBlock?

        if targetIndex == 1 {
            guard sphereIndex < ReplateCameraView.upperSpheresSet.count else {
                print("[updateSpheres] Sphere index out of bounds")
                completion(false)
                return
            }

            if !ReplateCameraView.upperSpheresSet[sphereIndex] {
                ReplateCameraView.upperSpheresSet[sphereIndex] = true
                ReplateCameraView.photosFromDifferentAnglesTaken += 1
                newAngle = true

                guard 72 + sphereIndex < ReplateCameraView.spheresModels.count else {
                    print("[updateSpheres] Upper spheresModels index out of range")
                    completion(false)
                    return
                }

                mesh = ReplateCameraView.spheresModels[72 + sphereIndex]

                if ReplateCameraView.upperSpheresSet.allSatisfy({ $0 }) {
                    callback = ReplateCameraController.completedUpperSpheresCallback
                    ReplateCameraController.completedUpperSpheresCallback = nil
                }
            }
        } else if targetIndex == 0 {
            guard sphereIndex < ReplateCameraView.lowerSpheresSet.count else {
                print("[updateSpheres] Lower sphere index out of range")
                completion(false)
                return
            }

            if !ReplateCameraView.lowerSpheresSet[sphereIndex] {
                ReplateCameraView.lowerSpheresSet[sphereIndex] = true
                ReplateCameraView.photosFromDifferentAnglesTaken += 1
                newAngle = true

                guard sphereIndex < ReplateCameraView.spheresModels.count else {
                    print("[updateSpheres] Lower spheresModels index out of range")
                    completion(false)
                    return
                }

                mesh = ReplateCameraView.spheresModels[sphereIndex]

                if ReplateCameraView.lowerSpheresSet.allSatisfy({ $0 }) {
                    callback = ReplateCameraController.completedLowerSpheresCallback
                    ReplateCameraController.completedLowerSpheresCallback = nil
                }
            }
        }

        if let mesh = mesh {
            let material = SimpleMaterial(color: .green, roughness: 1, isMetallic: false)
            mesh.model?.materials[0] = material
          ReplateCameraView.INSTANCE.generateImpactFeedback(strength: .light)
        }

        callback?([])
        completion(newAngle)
    }

    // MARK: - Anchor Validation
    func isAnchorNodeValid(_ anchorNode: AnchorEntity) -> Bool {
        var isValid = false
      if(Thread.isMainThread){
        let transform = anchorNode.transform
        let position = transform.translation
        let rotation = transform.rotation
        let scale = transform.scale

        isValid = !position.isNaN &&
        !rotation.isNaN &&
        scale != SIMD3<Float>(0, 0, 0) &&
        abs(length(rotation.vector) - 1.0) < 0.0001
      }else{
        DispatchQueue.main.sync {
          let transform = anchorNode.transform
          let position = transform.translation
          let rotation = transform.rotation
          let scale = transform.scale

          isValid = !position.isNaN &&
          !rotation.isNaN &&
          scale != SIMD3<Float>(0, 0, 0) &&
          abs(length(rotation.vector) - 1.0) < 0.0001
        }
      }

        return isValid
    }

    // MARK: - Distance and Angle Calculations
    func isCameraWithinRange(cameraTransform: simd_float4x4, anchorEntity: AnchorEntity) -> Int {
        var distance: Float = 0

      if(Thread.isMainThread){
        let cameraPosition = SIMD3<Float>(
          cameraTransform.columns.3.x,
          cameraTransform.columns.3.y,
          cameraTransform.columns.3.z
        )
        let anchorPosition = anchorEntity.transform.translation
        distance = distanceBetween(cameraPosition, anchorPosition)
      }else{
        if(Thread.isMainThread){
          let cameraPosition = SIMD3<Float>(
            cameraTransform.columns.3.x,
            cameraTransform.columns.3.y,
            cameraTransform.columns.3.z
          )
          let anchorPosition = anchorEntity.transform.translation
          distance = distanceBetween(cameraPosition, anchorPosition)
        }else{
          DispatchQueue.main.sync {
            let cameraPosition = SIMD3<Float>(
              cameraTransform.columns.3.x,
              cameraTransform.columns.3.y,
              cameraTransform.columns.3.z
            )
            let anchorPosition = anchorEntity.transform.translation
            distance = distanceBetween(cameraPosition, anchorPosition)
          }
        }

      }
        return distance <= Self.MIN_DISTANCE ? -1 :
               distance >= Self.MAX_DISTANCE ? 1 : 0
    }

    func distanceBetween(_ pos1: SIMD3<Float>, _ pos2: SIMD3<Float>) -> Float {
        let difference = pos1 - pos2
        return sqrt(dot(difference, difference))
    }

    // MARK: - Static Utility Methods
   func getTransformRelativeToAnchor(anchor: AnchorEntity, cameraTransform: simd_float4x4) -> simd_float4x4 {
        var relativeTransform: simd_float4x4!
     if(Thread.isMainThread){
       let anchorTransform = anchor.transformMatrix(relativeTo: nil)
       relativeTransform = anchorTransform.inverse * cameraTransform
     }else{
       DispatchQueue.main.sync {
         let anchorTransform = anchor.transformMatrix(relativeTo: nil)
         relativeTransform = anchorTransform.inverse * cameraTransform
       }
     }
        return relativeTransform
    }

   func angleBetweenAnchorXAndCamera(anchor: AnchorEntity, cameraTransform: simd_float4x4) -> Float {
        var angleDegrees: Float = 0
     if(Thread.isMainThread){
       let anchorTransform = anchor.transform.matrix
       let anchorPositionXZ = simd_float2(
        anchor.transform.translation.x,
        anchor.transform.translation.z
       )
       let relativeCameraPositionXZ = simd_float2(
        cameraTransform.columns.3.x,
        cameraTransform.columns.3.z
       )

       let directionXZ = relativeCameraPositionXZ - anchorPositionXZ
       let anchorXAxisXZ = simd_float2(
        anchorTransform.columns.0.x,
        anchorTransform.columns.0.z
       )

       let angle = atan2(directionXZ.y, directionXZ.x) -
       atan2(anchorXAxisXZ.y, anchorXAxisXZ.x)

       angleDegrees = angle * (180.0 / .pi)
       if angleDegrees < 0 {
         angleDegrees += 360
       }
     }else{
       DispatchQueue.main.sync {
         let anchorTransform = anchor.transform.matrix
         let anchorPositionXZ = simd_float2(
          anchor.transform.translation.x,
          anchor.transform.translation.z
         )
         let relativeCameraPositionXZ = simd_float2(
          cameraTransform.columns.3.x,
          cameraTransform.columns.3.z
         )

         let directionXZ = relativeCameraPositionXZ - anchorPositionXZ
         let anchorXAxisXZ = simd_float2(
          anchorTransform.columns.0.x,
          anchorTransform.columns.0.z
         )

         let angle = atan2(directionXZ.y, directionXZ.x) -
         atan2(anchorXAxisXZ.y, anchorXAxisXZ.x)

         angleDegrees = angle * (180.0 / .pi)
         if angleDegrees < 0 {
           angleDegrees += 360
         }
       }
     }
        return angleDegrees
    }

    func cgImage(from ciImage: CIImage) -> CGImage? {
        let context = CIContext(options: nil)
        return context.createCGImage(ciImage, from: ciImage.extent)
    }

  func saveImageAsJPEG(_ image: UIImage) -> URL? {
    guard let imageData = image.jpegData(compressionQuality: 1),
          let source = CGImageSourceCreateWithData(imageData as CFData, nil) else {
      return nil
    }

    let temporaryDirectoryURL = URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
    let uniqueFilename = "image_\(Date().timeIntervalSince1970).jpg"
    let fileURL = temporaryDirectoryURL.appendingPathComponent(uniqueFilename)

    guard let imageProperties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any] else {
      return nil
    }

    var mutableMetadata = imageProperties
    mutableMetadata[kCGImagePropertyExifDictionary] = [
      kCGImagePropertyExifUserComment: getTransformJSON(session: ReplateCameraView.arView.session)
    ]

    guard let destination = CGImageDestinationCreateWithURL(
      fileURL as CFURL,
      kUTTypeJPEG,
      1,
      nil
    ) else {
      return nil
    }

    CGImageDestinationAddImageFromSource(
      destination,
      source,
      0,
      mutableMetadata as CFDictionary
    )

    guard CGImageDestinationFinalize(destination) else {
      return nil
    }

    return fileURL
  }

  func saveImageAsPNG(_ image: UIImage) -> URL? {
    // Convert UIImage to PNG data
    guard let imageData = image.pngData(),
          let source = CGImageSourceCreateWithData(imageData as CFData, nil) else {
      return nil
    }

    // Define temporary file URL
    let temporaryDirectoryURL = URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
    let uniqueFilename = "image_\(Date().timeIntervalSince1970).png"
    let fileURL = temporaryDirectoryURL.appendingPathComponent(uniqueFilename)

    // Retrieve existing image properties
    guard let imageProperties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any] else {
      return nil
    }

    // Add metadata (including a user comment with transform JSON)
    var mutableMetadata = imageProperties
    mutableMetadata[kCGImagePropertyPNGDictionary] = [
      kCGImagePropertyPNGComment: getTransformJSON(session: ReplateCameraView.arView.session)
    ]

    // Create destination for PNG file
    guard let destination = CGImageDestinationCreateWithURL(
      fileURL as CFURL,
      kUTTypePNG,
      1,
      nil
    ) else {
      return nil
    }

    // Add image with metadata to destination
    CGImageDestinationAddImageFromSource(
      destination,
      source,
      0,
      mutableMetadata as CFDictionary
    )

    // Finalize image creation
    guard CGImageDestinationFinalize(destination) else {
      return nil
    }

    return fileURL
  }


  func getTransformJSON(session: ARSession) -> String {
    let transform = session.currentFrame?.camera.transform ?? simd_float4x4()
    // Extract translation
    let translation = [
      "x": transform.columns.3.x,
      "y": transform.columns.3.y,
      "z": transform.columns.3.z
    ]

    // Extract scale by calculating the length of each column vector
    let scale = [
      "x": simd_length(simd_float3(transform.columns.0.x, transform.columns.0.y, transform.columns.0.z)),
      "y": simd_length(simd_float3(transform.columns.1.x, transform.columns.1.y, transform.columns.1.z)),
      "z": simd_length(simd_float3(transform.columns.2.x, transform.columns.2.y, transform.columns.2.z))
    ]

    // Extract rotation by normalizing each axis and converting it into a 3x3 rotation matrix
    let rotationMatrix = simd_float3x3(columns: (
      simd_float3(transform.columns.0.x / scale["x"]!, transform.columns.0.y / scale["x"]!, transform.columns.0.z / scale["x"]!),
      simd_float3(transform.columns.1.x / scale["y"]!, transform.columns.1.y / scale["y"]!, transform.columns.1.z / scale["y"]!),
      simd_float3(transform.columns.2.x / scale["z"]!, transform.columns.2.y / scale["z"]!, transform.columns.2.z / scale["z"]!)
    ))

    // Convert rotation matrix to quaternion
    let quaternion = simd_quatf(rotationMatrix)
    let rotation = [
      "x": quaternion.vector.x,
      "y": quaternion.vector.y,
      "z": quaternion.vector.z,
      "w": quaternion.vector.w
    ]

    // Format as JSON
    let jsonObject: [String: Any] = [
      "translation": translation,
      "rotation": rotation,
      "scale": scale,
      "gravityVector": ReplateCameraView.gravityVector
    ]

    // Convert dictionary to JSON string
    if let jsonData = try? JSONSerialization.data(withJSONObject: jsonObject, options: .prettyPrinted),
       let jsonString = String(data: jsonData, encoding: .utf8) {
      return jsonString
    } else {
      return "{}"  // Return empty JSON if conversion fails
    }
  }
}

extension ARView: ARCoachingOverlayViewDelegate {
  func addCoaching() {
    print("ADD COACHING")
    // Create a ARCoachingOverlayView object
    let coachingOverlay = ARCoachingOverlayView()
    // Make sure it rescales if the device orientation changes
    coachingOverlay.autoresizingMask = [.flexibleWidth, .flexibleHeight]
    self.addSubview(coachingOverlay)
    coachingOverlay.center = self.convert(self.center, from: self.superview)
    // Set the Augmented Reality goal
    coachingOverlay.goal = .horizontalPlane
    // Set the ARSession
    coachingOverlay.session = self.session
    // Set the delegate for any callbacks
    coachingOverlay.delegate = self
    coachingOverlay.setActive(true, animated: true)
    ReplateCameraView.INSTANCE.generateImpactFeedback(strength: .light)
    let callback = ReplateCameraController.openedTutorialCallback
    if (callback != nil) {
      callback!([])
      ReplateCameraController.openedTutorialCallback = nil
    }
  }

  // Example callback for the delegate object
  public func coachingOverlayViewDidDeactivate(
    _ coachingOverlayView: ARCoachingOverlayView
  ) {
    let callback = ReplateCameraController.completedTutorialCallback
    if (callback != nil) {
      callback!([])
      ReplateCameraController.completedTutorialCallback = nil
    }
    ReplateCameraView.INSTANCE.generateImpactFeedback(strength: .heavy)
    ReplateCameraView.addRecognizers()
  }
}

extension UIImage {
  func averageColor() -> UIColor? {
    // Convert UIImage to CGImage
    guard let cgImage = self.cgImage else {
      return nil
    }

    // Get width and height of the image
    let width = cgImage.width
    let height = cgImage.height

    // Create a data provider from CGImage
    guard let dataProvider = cgImage.dataProvider else {
      return nil
    }

    // Access pixel data
    guard let pixelData = dataProvider.data else {
      return nil
    }

    // Create a pointer to the pixel data
    let data: UnsafePointer<UInt8> = CFDataGetBytePtr(pixelData)

    var totalRed: CGFloat = 0
    var totalGreen: CGFloat = 0
    var totalBlue: CGFloat = 0

    // Loop through each pixel and calculate sum of RGB values
    for y in 0..<height {
      for x in 0..<width {
        let pixelInfo: Int = ((width * y) + x) * 4
        let red = CGFloat(data[pixelInfo]) / 255.0
        let green = CGFloat(data[pixelInfo + 1]) / 255.0
        let blue = CGFloat(data[pixelInfo + 2]) / 255.0

        totalRed += red
        totalGreen += green
        totalBlue += blue
      }
    }

    // Calculate average RGB values
    let count = CGFloat(width * height)
    let averageRed = totalRed / count
    let averageGreen = totalGreen / count
    let averageBlue = totalBlue / count

    // Create and return average color
    return UIColor(red: averageRed, green: averageGreen, blue: averageBlue, alpha: 1.0)
  }
}

extension UIColor {
  func getRGBComponents() -> (red: CGFloat, green: CGFloat, blue: CGFloat)? {
    var red: CGFloat = 0
    var green: CGFloat = 0
    var blue: CGFloat = 0
    var alpha: CGFloat = 0

    // Check if the color can be converted to RGB
    guard self.getRed(&red, green: &green, blue: &blue, alpha: &alpha) else {
      return nil
    }

    return (red, green, blue)
  }
}

public extension simd_float2 {
  static func -(lhs: simd_float2, rhs: simd_float2) -> simd_float2 {
    return simd_float2(lhs.x - rhs.x, lhs.y - rhs.y)
  }
}

public extension SIMD3 where Scalar == Float {
  var isNaN: Bool {
    return x.isNaN || y.isNaN || z.isNaN
  }
}

public extension simd_quatf {
  var isNaN: Bool {
    return vector.x.isNaN || vector.y.isNaN || vector.w.isNaN
  }
}
