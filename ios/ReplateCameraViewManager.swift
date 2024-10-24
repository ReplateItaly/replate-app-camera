import ARKit
import RealityKit
import UIKit
import AVFoundation
import ImageIO
import MobileCoreServices

@objc(ReplateCameraViewManager)
class ReplateCameraViewManager: RCTViewManager {

  override func view() -> (ReplateCameraView) {
    let replCameraView = ReplateCameraView()
    return replCameraView
  }

  @objc override static func requiresMainQueueSetup() -> Bool {
    return false
  }

}

extension UIImage {
  func rotate(radians: Float) -> UIImage {
    var newSize = CGRect(origin: CGPoint.zero, size: self.size).applying(CGAffineTransform(rotationAngle: CGFloat(radians))).size
    // Trim off the extremely small float value to prevent core graphics from rounding it up
    newSize.width = floor(newSize.width)
    newSize.height = floor(newSize.height)

    UIGraphicsBeginImageContextWithOptions(newSize, false, self.scale)
    let context = UIGraphicsGetCurrentContext()!

    // Move origin to middle
    context.translateBy(x: newSize.width / 2, y: newSize.height / 2)
    // Rotate around middle
    context.rotate(by: CGFloat(radians))
    // Draw the image at its center
    self.draw(in: CGRect(x: -self.size.width / 2, y: -self.size.height / 2, width: self.size.width, height: self.size.height))

    let newImage = UIGraphicsGetImageFromCurrentImageContext()!
    UIGraphicsEndImageContext()

    return newImage
  }
}

class ReplateCameraView: UIView, ARSessionDelegate {

  static var arView: ARView!
  static var anchorEntity: AnchorEntity? = nil
  static var model: Entity!
  static var spheresModels: [ModelEntity] = []
  static var upperSpheresSet: [Bool] = [Bool](repeating: false, count: 72)
  static var lowerSpheresSet: [Bool] = [Bool](repeating: false, count: 72)
  static var totalPhotosTaken: Int = 0
  static var photosFromDifferentAnglesTaken = 0
  static var INSTANCE: ReplateCameraView!
  static var sphereRadius = Float(0.004)
  static var spheresRadius = Float(0.13)
  static var sphereAngle = Float(5)
  static var spheresHeight = Float(0.10)
  static var dragSpeed = CGFloat(7000)
  static var isPaused = false
  static var sessionId: UUID!
  static var focusModel: Entity!
  static var distanceBetweenCircles = Float(0.10)
  static var circleInFocus = 0 //0 for lower, 1 for upper
  static var dotAnchors: [AnchorEntity] = []
  static var width = CGFloat(0)
  static var height = CGFloat(0)
  private var isResetting = false  // Flag to prevent infinite loop
  private let resetSemaphore = DispatchSemaphore(value: 1)  // Semaphore for synchronization


  override init(frame: CGRect) {
    super.init(frame: frame)
    requestCameraPermissions()
    //        setupAR()
    ReplateCameraView.INSTANCE = self
  }

  required init?(coder: NSCoder) {
    super.init(coder: coder)
    requestCameraPermissions()
    ReplateCameraView.INSTANCE = self
    //        setupAR()
  }

  static func addRecognizer() {
    let recognizer = UITapGestureRecognizer(target: ReplateCameraView.INSTANCE,
                                            action: #selector(ReplateCameraView.INSTANCE.viewTapped(_:)))
    ReplateCameraView.arView.addGestureRecognizer(recognizer)
    let panGestureRecognizer = UIPanGestureRecognizer(target: ReplateCameraView.INSTANCE, action: #selector(ReplateCameraView.INSTANCE.handlePan(_:)))
    ReplateCameraView.arView.addGestureRecognizer(panGestureRecognizer)
    let pinchGestureRecognizer = UIPinchGestureRecognizer(target: ReplateCameraView.INSTANCE, action: #selector(ReplateCameraView.INSTANCE.handlePinch(_:)))
    ReplateCameraView.arView.addGestureRecognizer(pinchGestureRecognizer)
  }

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

  override func layoutSubviews() {
    super.layoutSubviews()
    ReplateCameraView.width = self.frame.width
    ReplateCameraView.height = self.frame.height
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
      // Ensure execution on the main thread
      DispatchQueue.main.async {
        // Calculate the scale based on the gesture recognizer's scale
        let scale = Float(gestureRecognizer.scale)

        // Ensure anchor entity is not nil before proceeding
        guard let anchorEntity = ReplateCameraView.anchorEntity else {
          print("[handlePinch] Anchor entity is nil.")
          return
        }

        // Remove all child entities safely
        ReplateCameraView.spheresModels.forEach { entity in
          anchorEntity.removeChild(entity)
        }
        if let focusModel = ReplateCameraView.focusModel {
          anchorEntity.removeChild(focusModel)
        }

        // Clear spheres models array
        ReplateCameraView.spheresModels = []

        // Update the scales
        ReplateCameraView.sphereRadius *= scale
        ReplateCameraView.spheresRadius *= scale
        ReplateCameraView.sphereAngle *= scale

        // Recreate spheres and the focus sphere
        self.createSpheres(y: ReplateCameraView.spheresHeight)
        self.createSpheres(y: ReplateCameraView.distanceBetweenCircles + ReplateCameraView.spheresHeight)
        self.createFocusSphere()

        // Update the material of the spheres based on their state
        for i in 0..<72 {
          let material = SimpleMaterial(color: .green, roughness: 1, isMetallic: false)
          if ReplateCameraView.upperSpheresSet[i] {
            if 72 + i < ReplateCameraView.spheresModels.count {
              let entity = ReplateCameraView.spheresModels[72 + i]
              entity.model?.materials[0] = material
            } else {
              print("[handlePinch] Upper sphere index out of bounds: \(72 + i)")
            }
          }
          if ReplateCameraView.lowerSpheresSet[i] {
            if i < ReplateCameraView.spheresModels.count {
              let entity = ReplateCameraView.spheresModels[i]
              entity.model?.materials[0] = material
            } else {
              print("[handlePinch] Lower sphere index out of bounds: \(i)")
            }
          }
        }

        // Reset the gesture recognizer's scale to 1 to avoid cumulative scaling
        gestureRecognizer.scale = 1.0
      }
    default:
      break
    }
  }

  func addDots(to planeAnchor: ARPlaneAnchor) {
    print("Adding dots to plane anchor") // Debugging line
    let center = planeAnchor.center
    let extent = planeAnchor.extent

    var dotPositions: [SIMD3<Float>] = []
    let dotSpacing: Float = 0.05 // Adjust the spacing of dots (smaller value for more dots)

    for x in stride(from: -extent.x / 2, through: extent.x / 2, by: dotSpacing) {
      for z in stride(from: -extent.z / 2, through: extent.z / 2, by: dotSpacing) {
        let position = SIMD3<Float>(x + center.x, 0, z + center.z)
        dotPositions.append(position)
      }
    }

    DispatchQueue.main.asyncAfter(deadline: DispatchTime.now().advanced(by: DispatchTimeInterval.milliseconds(1000)), execute: {
      // Add the dots to the ARView
      for position in dotPositions {
        let dotAnchor = AnchorEntity(world: planeAnchor.transform)
        let dot = self.createDot(at: position) // Assuming you're using the circle function
        dot.position.y = 0 // Ensure the dot position matches the plane's height
        dotAnchor.addChild(dot)
        ReplateCameraView.arView.scene.addAnchor(dotAnchor)
        ReplateCameraView.dotAnchors.append(dotAnchor)
      }
    })
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

  private func loadModel(named name: String) -> ModelEntity {
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

  @objc private func viewTapped(_ recognizer: UITapGestureRecognizer) {
    print("VIEW TAPPED")
    let tapLocation: CGPoint = recognizer.location(in: ReplateCameraView.arView)
    let estimatedPlane: ARRaycastQuery.Target = .estimatedPlane
    let alignment: ARRaycastQuery.TargetAlignment = .horizontal

    let result: [ARRaycastResult] = ReplateCameraView.arView.raycast(from: tapLocation,
                                                                     allowing: estimatedPlane,
                                                                     alignment: alignment)

    guard let rayCast: ARRaycastResult = result.first
    else {
      return
    }
    let anchor = AnchorEntity(world: rayCast.worldTransform)
    //    anchor.orientation = simd_quatf(ix: 0, iy: 0, iz: 0, r: 1)
    //    anchor.transform.rotation = simd_quatf(ix: 0, iy: 0, iz: 0, r: 1)
    print("ANCHOR FOUND\n", anchor.transform)
    let callback = ReplateCameraController.anchorSetCallback
    if (callback != nil) {
      callback!([])
      ReplateCameraController.anchorSetCallback = nil
    }
    if (ReplateCameraView.model == nil && ReplateCameraView.anchorEntity == nil) {
      DispatchQueue.main.async{
        for dot in ReplateCameraView.dotAnchors {
          dot.removeFromParent()
          ReplateCameraView.arView.scene.removeAnchor(dot)
        }
        ReplateCameraView.dotAnchors = []
      }
      ReplateCameraView.anchorEntity = anchor
      createSpheres(y: ReplateCameraView.spheresHeight)
      createSpheres(y: ReplateCameraView.distanceBetweenCircles + ReplateCameraView.spheresHeight)
      createFocusSphere()

      //DEBUG MESHES
      //            let xAxis = MeshResource.generateBox(width: 2, height: 0.001, depth: 0.01)
      //            let xLineEntity = ModelEntity(mesh: xAxis)
      //            xLineEntity.setPosition(SIMD3<Float>(0, 0, 0), relativeTo: ReplateCameraView.anchorEntity)
      //            xLineEntity.model?.materials = [SimpleMaterial(color: .red, isMetallic: false)]
      //            ReplateCameraView.anchorEntity.addChild(xLineEntity)
      //            let xLineLeftSphere = MeshResource.generateSphere(radius: ReplateCameraView.sphereRadius * 5)
      //            let xLineLeftSphereEntity = ModelEntity(mesh: xLineLeftSphere)
      //            ReplateCameraView.anchorEntity.addChild(xLineLeftSphereEntity)
      //            xLineLeftSphereEntity.setPosition(SIMD3<Float>(-1, 0.002, 0), relativeTo: ReplateCameraView.anchorEntity)
      //            xLineLeftSphereEntity.model?.materials = [SimpleMaterial(color: .red, isMetallic: false)]
      //            let xLineRightSphere = MeshResource.generateSphere(radius: ReplateCameraView.sphereRadius * 5)
      //            let xLineRightSphereEntity = ModelEntity(mesh: xLineRightSphere)
      //            ReplateCameraView.anchorEntity.addChild(xLineRightSphereEntity)
      //            xLineRightSphereEntity.setPosition(SIMD3<Float>(1, 0.002, 0), relativeTo: ReplateCameraView.anchorEntity)
      //            xLineRightSphereEntity.model?.materials = [SimpleMaterial(color: .yellow, isMetallic: false)]
      //            let yAxis = MeshResource.generateBox(width: 0.01, height: 0.001, depth: 2)
      //            let yLineEntity = ModelEntity(mesh: yAxis)
      //            yLineEntity.setPosition(SIMD3<Float>(0, 0, 0), relativeTo: ReplateCameraView.anchorEntity)
      //            let yLineLeftSphere = MeshResource.generateSphere(radius: ReplateCameraView.sphereRadius * 5)
      //            let yLineLeftSphereEntity = ModelEntity(mesh: yLineLeftSphere)
      //            ReplateCameraView.anchorEntity.addChild(yLineLeftSphereEntity)
      //            yLineLeftSphereEntity.setPosition(SIMD3<Float>(0, 0.002, -1), relativeTo: ReplateCameraView.anchorEntity)
      //            yLineLeftSphereEntity.model?.materials = [SimpleMaterial(color: .systemPink, isMetallic: false)]
      //            let yLineRightSphere = MeshResource.generateSphere(radius: ReplateCameraView.sphereRadius * 5)
      //            let yLineRightSphereEntity = ModelEntity(mesh: yLineRightSphere)
      //            ReplateCameraView.anchorEntity.addChild(yLineRightSphereEntity)
      //            yLineRightSphereEntity.setPosition(SIMD3<Float>(0, 0.002, 1), relativeTo: ReplateCameraView.anchorEntity)
      //            yLineRightSphereEntity.model?.materials = [SimpleMaterial(color: .orange, isMetallic: false)]
      //            yLineEntity.model?.materials = [SimpleMaterial(color: .purple, isMetallic: false)]
      //            ReplateCameraView.anchorEntity.addChild(yLineEntity)
      //            let circleEntity = ModelEntity(mesh: MeshResource.generateBox(size: 2, cornerRadius: 1))
      //            circleEntity.setPosition(SIMD3<Float>(0, 0, 0), relativeTo: ReplateCameraView.anchorEntity)
      //            circleEntity.model?.materials = [SimpleMaterial(color: .yellow, isMetallic: false)]

      guard let anchorEntity = ReplateCameraView.anchorEntity else { return }
      ReplateCameraView.arView.scene.anchors.append(anchorEntity)
    }
  }

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

  func createSphere(position: SIMD3<Float>) -> ModelEntity {
    // Ensure execution on the main thread
    guard Thread.isMainThread else {
      return DispatchQueue.main.sync {
        return createSphere(position: position)
      }
    }

    // Generate sphere mesh safely
    let sphereMesh = MeshResource.generateSphere(radius: ReplateCameraView.sphereRadius)

    // Create sphere entity with the specified material
    let sphereEntity = ModelEntity(mesh: sphereMesh, materials: [SimpleMaterial(color: .white.withAlphaComponent(1), roughness: 1, isMetallic: false)])

    // Set the position for the sphere entity
    sphereEntity.position = position

    // Return the created sphere entity
    return sphereEntity
  }

  func createSpheres(y: Float) {
    let radius = ReplateCameraView.spheresRadius
    for i in 0..<72 {
      // Adjust the angle calculation so that the first sphere starts at 0 degrees
      let angle = Float(i) * (Float.pi / 180) * 5 // 5 degrees in radians
      let x = radius * cos(angle)
      let z = radius * sin(angle)
      let spherePosition = SIMD3<Float>(x, y, z)
      let sphereEntity = createSphere(position: spherePosition)
      //            if (i == 0) {
      //                let material = SimpleMaterial(color: .purple, isMetallic: true)
      //                sphereEntity.model?.materials[0] = material
      //            }
      ReplateCameraView.spheresModels.append(sphereEntity)
      ReplateCameraView.anchorEntity?.addChild(sphereEntity)
    }
  }

  func setupAR() {
    print("Setup AR")
    let configuration = ARWorldTrackingConfiguration()
    configuration.isLightEstimationEnabled = true

    ReplateCameraView.arView.renderOptions.insert(ARView.RenderOptions.disableMotionBlur)
    ReplateCameraView.arView.renderOptions.insert(ARView.RenderOptions.disableCameraGrain)
    ReplateCameraView.arView.renderOptions.insert(ARView.RenderOptions.disableAREnvironmentLighting)
    ReplateCameraView.arView.renderOptions.insert(ARView.RenderOptions.disableHDR)
    ReplateCameraView.arView.renderOptions.insert(ARView.RenderOptions.disableFaceMesh)
    ReplateCameraView.arView.renderOptions.insert(ARView.RenderOptions.disableGroundingShadows)
    ReplateCameraView.arView.renderOptions.insert(ARView.RenderOptions.disablePersonOcclusion)
    configuration.planeDetection = ARWorldTrackingConfiguration.PlaneDetection.horizontal
    if #available(iOS 16.0, *) {
      print("recommendedVideoFormatForHighResolutionFrameCapturing")
      configuration.videoFormat = ARWorldTrackingConfiguration.recommendedVideoFormatForHighResolutionFrameCapturing ?? ARWorldTrackingConfiguration.recommendedVideoFormatFor4KResolution ?? ARWorldTrackingConfiguration.supportedVideoFormats.max(by: { format1, format2 in
        let resolution1 = format1.imageResolution.width * format1.imageResolution.height
        let resolution2 = format2.imageResolution.width * format2.imageResolution.height
        return resolution1 < resolution2
      })!
    } else {
      print("Alternative high resolution method")
      let maxResolutionFormat = ARWorldTrackingConfiguration.supportedVideoFormats.max(by: { format1, format2 in
        let resolution1 = format1.imageResolution.width * format1.imageResolution.height
        let resolution2 = format2.imageResolution.width * format2.imageResolution.height
        return resolution1 < resolution2
      })!
      configuration.videoFormat = maxResolutionFormat
    }
    ReplateCameraView.arView.session.run(configuration)
    ReplateCameraView.arView.addCoaching()
    ReplateCameraView.sessionId = ReplateCameraView.arView.session.identifier
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
    print("Planes detected: \(anchors.count)") // Debugging line
    for anchor in anchors {
      if let planeAnchor = anchor as? ARPlaneAnchor {
        print("Adding dots to plane")
        if (ReplateCameraView.spheresModels.isEmpty && ReplateCameraView.dotAnchors.isEmpty) {
          addDots(to: planeAnchor)
        }
      }
    }
  }

  func session(_ session: ARSession, didUpdate frame: ARFrame) {
    // Handle AR frame updates
    // You can perform actions here, such as updating the AR content based on the camera frame
  }

  func sessionWasInterrupted(_ session: ARSession) {
    print("SESSION INTERRUPTED")
  }

  func sessionInterruptionEnded(_ session: ARSession) {
    print("SESSION RESUMED")
  }

  func reset() {
    DispatchQueue.global().async {
      print("Entering reset. Waiting for semaphore.")
      self.resetSemaphore.wait()  // Wait for the semaphore
      print("Obtained semaphore.")
      if self.isResetting {
        print("Signaling semaphore because reset is already in progress.")
        self.resetSemaphore.signal()  // Release the semaphore
        return
      }
      self.isResetting = true
      self.resetSemaphore.signal()  // Release the semaphore
      print("Resetting, releasing semaphore.")

      if (!Thread.isMainThread) {
        DispatchQueue.main.sync {
          print("PROOOOOVA1")
          // Pause the existing AR session
          ReplateCameraView.arView?.session.pause()
          ReplateCameraView.arView?.session.delegate = nil
          ReplateCameraView.arView?.scene.anchors.removeAll()

          // Perform UI updates and property resets on the main thread asynchronously
          // Remove the existing ARView from the superview
          ReplateCameraView.arView?.removeFromSuperview()

          // Resign key window
          ReplateCameraView.arView?.window?.resignKey()
          print("PROOOOOOVA2")

          // Reset the static properties
          ReplateCameraView.anchorEntity = nil
          ReplateCameraView.model = nil
          ReplateCameraView.spheresModels = []
          ReplateCameraView.upperSpheresSet = [Bool](repeating: false, count: 72)
          ReplateCameraView.lowerSpheresSet = [Bool](repeating: false, count: 72)
          ReplateCameraView.totalPhotosTaken = 0
          ReplateCameraView.photosFromDifferentAnglesTaken = 0
          ReplateCameraView.sphereRadius = Float(0.004)
          ReplateCameraView.spheresRadius = Float(0.13)
          ReplateCameraView.sphereAngle = Float(5)
          ReplateCameraView.spheresHeight = Float(0.10)
          ReplateCameraView.dragSpeed = CGFloat(7000)


          ReplateCameraView.arView = ARView(frame: CGRect(x: 0, y: 0, width: ReplateCameraView.width, height: ReplateCameraView.height))
          ReplateCameraView.arView.backgroundColor = self.hexStringToUIColor(hexColor: "#32a852")
          print("PROOOOOOVA3")

          // Add the new ARView to the view hierarchy
          self.addSubview(ReplateCameraView.arView)

          // Set the session delegate and run the session
          ReplateCameraView.arView?.session.delegate = self
          print("PROOOOOOVA")
          self.setupAR()

          self.resetSemaphore.wait()  // Wait for the semaphore
          self.isResetting = false
          self.resetSemaphore.signal()  // Release the semaphore
        }
      }
    }
  }


  static func generateImpactFeedback(strength: UIImpactFeedbackGenerator.FeedbackStyle) {
    do{
      let impactFeedbackGenerator = try UIImpactFeedbackGenerator(style: strength)
      impactFeedbackGenerator.prepare()
      impactFeedbackGenerator.impactOccurred()
    }catch{
      print("Error when sending feedback")
    }
  }

}

@objc(ReplateCameraController)
class ReplateCameraController: NSObject {

  static var completedTutorialCallback: RCTResponseSenderBlock?
  static var anchorSetCallback: RCTResponseSenderBlock?
  static var completedUpperSpheresCallback: RCTResponseSenderBlock?
  static var completedLowerSpheresCallback: RCTResponseSenderBlock?
  static var openedTutorialCallback: RCTResponseSenderBlock?
  static var tooCloseCallback: RCTResponseSenderBlock?
  static var tooFarCallback: RCTResponseSenderBlock?

  @objc(registerOpenedTutorialCallback:)
  func registerOpenedTutorialCallback(_ myCallback: @escaping RCTResponseSenderBlock) {
    ReplateCameraController.openedTutorialCallback = myCallback
  }

  @objc(registerCompletedTutorialCallback:)
  func registerCompletedTutorialCallback(_ myCallback: @escaping RCTResponseSenderBlock) {
    ReplateCameraController.completedTutorialCallback = myCallback
  }

  @objc(registerAnchorSetCallback:)
  func registerAnchorSetCallback(_ myCallback: @escaping RCTResponseSenderBlock) {
    ReplateCameraController.anchorSetCallback = myCallback
  }

  @objc(registerCompletedUpperSpheresCallback:)
  func registerCompletedUpperSpheresCallback(_ myCallback: @escaping RCTResponseSenderBlock) {
    ReplateCameraController.completedUpperSpheresCallback = myCallback
  }

  @objc(registerCompletedLowerSpheresCallback:)
  func registerCompletedLowerSpheresCallback(_ myCallback: @escaping RCTResponseSenderBlock) {
    ReplateCameraController.completedLowerSpheresCallback = myCallback
  }

  @objc(registerTooCloseCallback:)
  func registerTooCloseCallback(_ myCallback: @escaping RCTResponseSenderBlock) {
    ReplateCameraController.tooCloseCallback = myCallback
  }

  @objc(registerTooFarCallback:)
  func registerTooFarCallback(_ myCallback: @escaping RCTResponseSenderBlock) {
    ReplateCameraController.tooFarCallback = myCallback
  }

  @objc(getPhotosCount:rejecter:)
  func getPhotosCount(_ resolver: RCTPromiseResolveBlock, rejecter: RCTPromiseRejectBlock) -> Void {
    resolver(ReplateCameraView.totalPhotosTaken)
  }

  @objc(isScanComplete:rejecter:)
  func isScanComplete(_ resolver: RCTPromiseResolveBlock, rejecter: RCTPromiseRejectBlock) -> Void {
    resolver(ReplateCameraView.photosFromDifferentAnglesTaken == 144)
  }

  @objc(getRemainingAnglesToScan:rejecter:)
  func getRemainingAnglesToScan(_ resolver: RCTPromiseResolveBlock, rejecter: RCTPromiseRejectBlock) -> Void {
    resolver(144 - ReplateCameraView.photosFromDifferentAnglesTaken)
  }

  @objc
  func reset(){
    ReplateCameraView.INSTANCE.reset()
  }

  func isAnchorNodeValid(_ anchorNode: AnchorEntity) -> Bool {
      // Check if the transform is initialized
      let transform = anchorNode.transform
      if(transform == nil){
          print("Transform is not initialized.")
          return false
      }

      // Check position, rotation, and scale
      let position = transform.translation
      let rotation = transform.rotation
      let scale = transform.scale

      // Validate position, rotation, and scale
      guard !position.isNaN else {
          print("Position contains NaN values.")
          return false
      }

      guard !rotation.isNaN else {
          print("Rotation contains NaN values.")
          return false
      }

      guard scale != SIMD3<Float>(0, 0, 0) else {
          print("Scale is zero.")
          return false
      }

      // Additional check to ensure the rotation quaternion is normalized
      guard abs(length(rotation.vector) - 1.0) < 0.0001 else {
          print("Rotation quaternion is not normalized.")
          return false
      }

      // All checks passed
      return true
  }

  @objc(takePhoto:resolver:rejecter:)
  func takePhoto(_ unlimited: Bool = false, resolver: @escaping RCTPromiseResolveBlock, rejecter: @escaping RCTPromiseRejectBlock) {
    var hasCalledBack = false

    func safeResolver(_ result: Any) {
      if !hasCalledBack {
        hasCalledBack = true
        resolver(result)
      } else {
        print("resolver: Callback already invoked.")
      }
    }

    func safeRejecter(_ code: String, _ message: String, _ error: NSError) {
      if !hasCalledBack {
        hasCalledBack = true
        rejecter(code, "{\"code\": \(code), \"message\": \"\(message)\"}", error)
      } else {
        print("rejecter: Callback already invoked.")
      }
    }

    do {
      guard let anchorNode = ReplateCameraView.anchorEntity else {
        safeRejecter("001", "[ReplateCameraController] No anchor set yet", NSError(domain: "ReplateCameraController", code: 001, userInfo: nil))
        return
      }
      var anchorPosition: SIMD3<Float>? = nil
      if(!Thread.isMainThread){
        DispatchQueue.main.sync {
          if isAnchorNodeValid(anchorNode) {
            anchorPosition = anchorNode.position(relativeTo: nil)
            print("Anchor position in world space: \(anchorPosition)")
          } else {
            print("AnchorNode is not valid.")
            safeRejecter("001", "[ReplateCameraController] AnchorNode is not valid.", NSError(domain: "ReplateCameraController", code: 001, userInfo: nil))
            return
          }
        }
      }
      guard let anchorPosition else {
        safeRejecter("001", "[ReplateCameraController] No anchor set yet", NSError(domain: "ReplateCameraController", code: 001, userInfo: nil))
        return
      }
      let spheresHeight = ReplateCameraView.spheresHeight
      let distanceBetweenCircles = ReplateCameraView.distanceBetweenCircles
      let point1Y = anchorPosition.y + spheresHeight
      let point2Y = anchorPosition.y + distanceBetweenCircles + spheresHeight
      let twoThirdsDistance = spheresHeight + distanceBetweenCircles + distanceBetweenCircles/5
      var deviceTargetInFocus = -1
      let angleThreshold: Float = 0.6
      var relativeCameraTransform: simd_float4x4
      if let cameraTransform = ReplateCameraView.arView.session.currentFrame?.camera.transform {
        relativeCameraTransform = ReplateCameraController.getTransformRelativeToAnchor(anchor: anchorNode, cameraTransform: cameraTransform)
        let cameraPosition = SIMD3<Float>(cameraTransform.columns.3.x, cameraTransform.columns.3.y, cameraTransform.columns.3.z)
        let deviceDirection = normalize(SIMD3<Float>(-cameraTransform.columns.2.x, -cameraTransform.columns.2.y, -cameraTransform.columns.2.z))
        let directionToAnchor = normalize(anchorPosition - cameraPosition)
        let angleToAnchor = acos(dot(deviceDirection, directionToAnchor))

        if angleToAnchor < angleThreshold {
          let cameraHeight = relativeCameraTransform.columns.3.y
          if cameraHeight < twoThirdsDistance {
            deviceTargetInFocus = 0
            print("Is pointing at first point")
          } else {
            deviceTargetInFocus = 1
            print("Is pointing at second point")
          }
        } else {
          print("Not pointing at anchor")
        }
      } else {
        print("Camera transform data not available")
        safeRejecter("002", "[ReplateCameraController] Camera transform data not available", NSError(domain: "ReplateCameraController", code: 002, userInfo: nil))
        return
      }

      if deviceTargetInFocus != -1 {

        func setOpacityToCircle(circleId: Int, opacity: Float) {
          DispatchQueue.main.async{
            for i in 0..<72 {
              let offset = circleId == 0 ? 0 : 72
              let entity = ReplateCameraView.spheresModels[i+offset]
              let material = entity.model?.materials[0]
              if (material is SimpleMaterial && material != nil){
                let simpleMaterial = material! as? SimpleMaterial
                if #available(iOS 15.0, *) {
                  let newMaterial = SimpleMaterial(color:                                 simpleMaterial?.color.tint.withAlphaComponent(CGFloat(opacity)) ?? SimpleMaterial.Color.white.withAlphaComponent(CGFloat(opacity)), roughness: 1, isMetallic: false)
                  entity.model?.materials[0] = newMaterial
                } else {
                  // Fallback on earlier versions
                }
              }
            }
          }
        }
        if(deviceTargetInFocus != ReplateCameraView.circleInFocus){
          setOpacityToCircle(circleId: ReplateCameraView.circleInFocus, opacity: 0.5)
          setOpacityToCircle(circleId: deviceTargetInFocus, opacity: 1)
          ReplateCameraView.circleInFocus = deviceTargetInFocus
          ReplateCameraView.generateImpactFeedback(strength: .heavy)
        }
        updateSpheres(deviceTargetInFocus: deviceTargetInFocus, cameraTransform: relativeCameraTransform) { result in
          if !unlimited && !result {
            safeRejecter("003", "[ReplateCameraController] Too many images and the last one's not from a new angle", NSError(domain: "ReplateCameraController", code: 003, userInfo: nil))
            return
          }

          if let image = ReplateCameraView.arView?.session.currentFrame?.capturedImage {
            let ciImage = CIImage(cvImageBuffer: image)

            // Define the target size for the reduced resolution
            let targetSize = CGSize(width: 1728, height: 1296) // Change this to your desired resolution

            // Create a scaling filter to resize the image
            let scaleFilter = CIFilter(name: "CILanczosScaleTransform")!
            scaleFilter.setValue(ciImage, forKey: kCIInputImageKey)
            scaleFilter.setValue(targetSize.width / ciImage.extent.width, forKey: kCIInputScaleKey) // Scale factor
            scaleFilter.setValue(1.0, forKey: kCIInputAspectRatioKey) // Maintain aspect ratio

            // Get the output CIImage
            guard let scaledCIImage = scaleFilter.outputImage else {
              safeRejecter("005", "[ReplateCameraController] Error scaling CIImage", NSError(domain: "ReplateCameraController", code: 005, userInfo: nil))
              return
            }

            // Convert the resized CIImage to CGImage
            guard let cgImage = ReplateCameraController.cgImage(from: scaledCIImage) else {
              safeRejecter("004", "[ReplateCameraController] Error converting CIImage to CGImage", NSError(domain: "ReplateCameraController", code: 004, userInfo: nil))
              return
            }

            // Create UIImage from CGImage
            let uiImage = UIImage(cgImage: cgImage)

            // Rotate the image if needed
            let finImage = uiImage.rotate(radians: .pi / 2) // Adjust radians as needed

            // Check light estimate
            if let lightEstimate = ReplateCameraView.arView.session.currentFrame?.lightEstimate {
              let ambientIntensity = lightEstimate.ambientIntensity
              let ambientColorTemperature = lightEstimate.ambientColorTemperature

              if ambientIntensity < 650 {
                safeRejecter("005", "[ReplateCameraController] Image too dark", NSError(domain: "ReplateCameraController", code: 005, userInfo: nil))
                return
              }

              print("Ambient Intensity: \(ambientIntensity)")
              print("Color Temperature: \(ambientColorTemperature)")
            }

            // Save the final image
            if let url = ReplateCameraController.saveImageAsJPEG(finImage) {
              safeResolver(url.absoluteString)
            } else {
              safeRejecter("006", "[ReplateCameraController] Error saving photo", NSError(domain: "ReplateCameraController", code: 006, userInfo: nil))
            }
          }
        }
      } else {
        safeRejecter("007", "[ReplateCameraController] Object not in focus", NSError(domain: "ReplateCameraController", code: 007, userInfo: nil))
      }
    } catch {
      print("Unexpected error occurred")
    }
  }


  static func cgImage(from ciImage: CIImage) -> CGImage? {
    let context = CIContext(options: nil)

    return context.createCGImage(ciImage, from: ciImage.extent)
  }

  static func getCameraTransformString(from session: ARSession) -> String? {
    guard let currentFrame = session.currentFrame else {
      print("No current frame available")
      return nil
    }

    // Extract the camera transform matrix
    let cameraTransform = currentFrame.camera.transform

    // Serialize the transform matrix into a string
    let transformString = """
    \(cameraTransform.columns.0.x),\(cameraTransform.columns.0.y),\(cameraTransform.columns.0.z),\(cameraTransform.columns.0.w);
    \(cameraTransform.columns.1.x),\(cameraTransform.columns.1.y),\(cameraTransform.columns.1.z),\(cameraTransform.columns.1.w);
    \(cameraTransform.columns.2.x),\(cameraTransform.columns.2.y),\(cameraTransform.columns.2.z),\(cameraTransform.columns.2.w);
    \(cameraTransform.columns.3.x),\(cameraTransform.columns.3.y),\(cameraTransform.columns.3.z),\(cameraTransform.columns.3.w)
    """

    return transformString
  }

  static func saveImageAsJPEG(_ image: UIImage) -> URL? {
    let cameraTransform = getCameraTransformString(from: ReplateCameraView.arView.session)
    // Convert UIImage to Data with JPEG representation
    guard let imageData = image.jpegData(compressionQuality: 1) else {
      // Handle error if unable to convert to JPEG data
      print("Error converting UIImage to JPEG data")
      return nil
    }

    // Create a CGImageSource from the image data
    guard let source = CGImageSourceCreateWithData(imageData as CFData, nil) else {
      print("Error creating image source")
      return nil
    }

    // Get the temporary directory URL
    let temporaryDirectoryURL = URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
    let uniqueFilename = "image_\(Date().timeIntervalSince1970).jpg"
    let fileURL = temporaryDirectoryURL.appendingPathComponent(uniqueFilename)

    // Create a mutable copy of the metadata
    guard let imageProperties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any] else {
      print("Error copying image properties")
      return nil
    }
    var mutableMetadata = imageProperties

    // Add the camera transform to the metadata
    mutableMetadata[kCGImagePropertyExifDictionary] = [
      kCGImagePropertyExifUserComment: cameraTransform
    ]

    // Create a CGImageDestination to write the image data with metadata
    guard let destination = CGImageDestinationCreateWithURL(fileURL as CFURL, kUTTypeJPEG, 1, nil) else {
      print("Error creating image destination")
      return nil
    }

    // Add the image from the source to the destination with the updated metadata
    CGImageDestinationAddImageFromSource(destination, source, 0, mutableMetadata as CFDictionary)

    // Finalize the image destination
    if !CGImageDestinationFinalize(destination) {
      print("Error finalizing image destination")
      return nil
    }

    print("Image saved at: \(fileURL.absoluteString)")
    return fileURL
  }

  func isCameraWithinRange(cameraTransform: simd_float4x4, anchorEntity: AnchorEntity, minDistance: Float = 0.15, maxDistance: Float = 0.45) -> Int {
    // Extract the camera's position from the cameraTransform (simd_float4x4)
    let cameraPosition = SIMD3<Float>(cameraTransform.columns.3.x, cameraTransform.columns.3.y, cameraTransform.columns.3.z)

    // Extract the anchor's position from its transform
    let anchorPosition = anchorEntity.transform.translation

    // Calculate the Euclidean distance between the camera and the anchor
    let distance = distanceBetween(cameraPosition, anchorPosition)
    print("Actual camera distance: \(distance)")
    // Check if the distance is within the range
    return distance <= minDistance ? -1 : distance >= maxDistance ? 1 : 0
  }

  func distanceBetween(_ pos1: SIMD3<Float>, _ pos2: SIMD3<Float>) -> Float {
    let dx = pos1.x - pos2.x
    let dy = pos1.y - pos2.y
    let dz = pos1.z - pos2.z

    // Return the Euclidean distance
    return sqrt(dx * dx + dy * dy + dz * dz)
  }

  func updateSpheres(deviceTargetInFocus: Int, cameraTransform: simd_float4x4, completion: @escaping (Bool) -> Void) {
    // Ensure the function handles a single completion call
    var completionCalled = false
    func callCompletion(_ result: Bool) {
      if !completionCalled {
        completionCalled = true
        completion(result)
      } else {
        print("Completion already called")
      }
    }

    // When the user pinches the screen, spheres are recreated,
    // we have to make sure all spheres have been recreated before proceeding
    if (ReplateCameraView.spheresModels.count < 144) {
      print("[updateSpheres] Spheres not fully initialized. Count: \(ReplateCameraView.spheresModels.count)")
      callCompletion(false)
      return
    }

    guard let anchorNode = ReplateCameraView.anchorEntity else {
      print("[updateSpheres] No anchor entity found.")
      callCompletion(false)
      return
    }

    // Get the camera's pose
    guard let frame = ReplateCameraView.arView.session.currentFrame else {
      print("[updateSpheres] No current frame available.")
      callCompletion(false)
      return
    }
    let cameraDistance = isCameraWithinRange(cameraTransform: cameraTransform, anchorEntity: anchorNode)
    print("Camera distance: \(cameraDistance)")
    func showAlert(_ message: String) {
      DispatchQueue.main.async {
        let alert = UIAlertController(title: "Alert", message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: "OK", style: .default, handler: nil))

        if let rootViewController = UIApplication.shared.keyWindow?.rootViewController {
          rootViewController.present(alert, animated: true, completion: nil)
        }
      }
    }

    switch cameraDistance{
    case 1:
//      showAlert("TOO FAR")
      print("TOO FAR")
      break;

    case -1:
//      showAlert("TOO CLOSE")
      print("TOO CLOSE")
      break;
    default: break;
    }

    if(cameraDistance != 0){
      if(cameraDistance == 1 && ReplateCameraController.tooFarCallback != nil){
        ReplateCameraController.tooFarCallback!([])
      }else if(cameraDistance == -1 && ReplateCameraController.tooCloseCallback != nil){
        ReplateCameraController.tooCloseCallback!([])
      }
    }

    // Calculate the angle between the camera and the anchor
    let angleDegrees = ReplateCameraController.angleBetweenAnchorXAndCamera(anchor: anchorNode,
                                                                            cameraTransform: cameraTransform)
    let sphereIndex = max(Int(round(angleDegrees / 5.0)), 0) % 72 // Ensure sphereIndex stays within 0-71 bounds

    var mesh: ModelEntity?
    var newAngle = false
    var callback: RCTResponseSenderBlock? = nil
    print("Sphere index \(sphereIndex) - Spheres length \(ReplateCameraView.spheresModels.count)")

    if deviceTargetInFocus == 1 {
      if sphereIndex >= ReplateCameraView.upperSpheresSet.count {
        print("[updateSpheres] Sphere index out of range. Index: \(sphereIndex), Count: \(ReplateCameraView.upperSpheresSet.count)")
        callCompletion(false)
        return
      }

      if !ReplateCameraView.upperSpheresSet[sphereIndex] {
        ReplateCameraView.upperSpheresSet[sphereIndex] = true
        ReplateCameraView.photosFromDifferentAnglesTaken += 1
        newAngle = true

        if 72 + sphereIndex >= ReplateCameraView.spheresModels.count {
          print("[updateSpheres] Upper spheresModels index out of range. Index: \(72 + sphereIndex), Count: \(ReplateCameraView.spheresModels.count)")
          callCompletion(false)
          return
        }
        mesh = ReplateCameraView.spheresModels[72 + sphereIndex]

        if ReplateCameraView.upperSpheresSet.allSatisfy({ $0 }) {
          callback = ReplateCameraController.completedUpperSpheresCallback
          ReplateCameraController.completedUpperSpheresCallback = nil
        }
      }
    } else if deviceTargetInFocus == 0 {
      if sphereIndex >= ReplateCameraView.lowerSpheresSet.count {
        print("[updateSpheres] Lower sphere index out of range. Index: \(sphereIndex), Count: \(ReplateCameraView.lowerSpheresSet.count)")
        callCompletion(false)
        return
      }

      if !ReplateCameraView.lowerSpheresSet[sphereIndex] {
        ReplateCameraView.lowerSpheresSet[sphereIndex] = true
        ReplateCameraView.photosFromDifferentAnglesTaken += 1
        newAngle = true

        if sphereIndex >= ReplateCameraView.spheresModels.count {
          print("[updateSpheres] Lower spheresModels index out of range. Index: \(sphereIndex), Count: \(ReplateCameraView.spheresModels.count)")
          callCompletion(false)
          return
        }
        mesh = ReplateCameraView.spheresModels[sphereIndex]

        if ReplateCameraView.lowerSpheresSet.allSatisfy({ $0 }) {
          callback = ReplateCameraController.completedLowerSpheresCallback
          ReplateCameraController.completedLowerSpheresCallback = nil
        }
      }
    }

    DispatchQueue.main.async {
      if let mesh = mesh {
        let material = SimpleMaterial(color: .green, roughness: 1, isMetallic: false)
        mesh.model?.materials[0] = material
        ReplateCameraView.generateImpactFeedback(strength: .light)
      }
    }

    // Ensure callback execution doesn't interfere with array access
    callback?([])
    callCompletion(newAngle)
  }

  static func getTransformRelativeToAnchor(anchor: AnchorEntity, cameraTransform: simd_float4x4) -> simd_float4x4{
    // Transform the camera position to the anchor's local space
    let anchorTransform = anchor.transformMatrix(relativeTo: nil)
    let relativePosition = anchorTransform.inverse * cameraTransform
    return relativePosition
  }

  static func angleBetweenAnchorXAndCamera(anchor: AnchorEntity, cameraTransform: simd_float4x4) -> Float {
    // Extract the position of the anchor and the camera from their transforms, ignoring the y-axis
    let anchorTransform = anchor.transform.matrix
    let anchorPositionXZ = simd_float2(anchor.transform.translation.x, anchor.transform.translation.z)
    let relativeCameraPositionXZ = simd_float2(cameraTransform.columns.3.x, cameraTransform.columns.3.z)

    // Calculate the direction vector from the anchor to the camera in the XZ plane
    let directionXZ = relativeCameraPositionXZ - anchorPositionXZ

    // Extract the x-axis of the anchor's transform in the XZ plane
    let anchorXAxisXZ = simd_float2(anchorTransform.columns.0.x, anchorTransform.columns.0.z)

    // Use atan2 to calculate the angle between the anchor's x-axis and the direction vector in the XZ plane
    let angle = atan2(directionXZ.y, directionXZ.x) - atan2(anchorXAxisXZ.y, anchorXAxisXZ.x)

    // Convert the angle to degrees
    var angleDegrees = angle * (180.0 / .pi)

    // Ensure the angle is within the range [0, 360)
    if angleDegrees < 0 {
      angleDegrees += 360
    }

    return angleDegrees
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
    ReplateCameraView.generateImpactFeedback(strength: .light)
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
    print("DEACTIVATED")
    let callback = ReplateCameraController.completedTutorialCallback
    if (callback != nil) {
      callback!([])
      ReplateCameraController.completedTutorialCallback = nil
    }
    ReplateCameraView.generateImpactFeedback(strength: .heavy)
    ReplateCameraView.addRecognizer()
    print("CRASHED")
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
