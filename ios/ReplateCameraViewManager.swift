import ARKit
import RealityKit
import UIKit
import AVFoundation
import ImageIO
import MobileCoreServices
import CoreMotion

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
        context.translateBy(x: newSize.width/2, y: newSize.height/2)
        context.rotate(by: CGFloat(radians))
        self.draw(in: CGRect(x: -self.size.width/2,
                            y: -self.size.height/2,
                            width: self.size.width,
                            height: self.size.height))
        let newImage = UIGraphicsGetImageFromCurrentImageContext() ?? self
        UIGraphicsEndImageContext()
        return newImage
    }
}

// MARK: - Safe Array Access
extension Array {
    subscript(safe index: Index) -> Element? {
        return indices.contains(index) ? self[index] : nil
    }
}

// MARK: - ReplateCameraView
class ReplateCameraView: UIView, ARSessionDelegate {
    // MARK: Static
    private static let lock = NSLock()
    private static let arQueue = DispatchQueue(label: "com.replate.ar", qos: .userInteractive)
    static var arView: ARView!
    static var anchorEntity: AnchorEntity?
    static var sessionId: UUID!
    static var motionManager: CMMotionManager!
    static var gravityVector: [String: Double] = [:]
    static var INSTANCE: ReplateCameraView!
    static var width: CGFloat = 0
    static var height: CGFloat = 0

    // Sphere state
    static var spheresModels: [ModelEntity] = []
    static var upperSpheresSet = [Bool](repeating: false, count: 72)
    static var lowerSpheresSet = [Bool](repeating: false, count: 72)
    static var spheresRadius: Float = 0.13
    static var spheresHeight: Float = 0.10
    static var distanceBetweenCircles: Float = 0.10
    static var dotAnchors: [AnchorEntity] = []

    // Gesture throttle
    private var lastPinchTime: TimeInterval = 0

    // Cached mesh + material
    private static let baseSphereMesh = MeshResource.generateSphere(radius: 0.004)
    private static let baseSphereMaterial = SimpleMaterial(color: .white, roughness: 1, isMetallic: false)

    // MARK: Init
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
    }

    // MARK: Layout
    override func layoutSubviews() {
        super.layoutSubviews()
        Self.lock.lock(); defer { Self.lock.unlock() }
        ReplateCameraView.width = frame.width
        ReplateCameraView.height = frame.height
    }

    // MARK: AR Setup
    static func setupAR() {
        arQueue.async {
            guard let inst = INSTANCE else { return }
            let config = ARWorldTrackingConfiguration()
            config.isLightEstimationEnabled = true
            config.planeDetection = .horizontal
            DispatchQueue.main.async {
                configureARView(config)
                addRecognizers()
            }
        }
    }
    private static func configureARView(_ config: ARWorldTrackingConfiguration) {
        arView.session.run(config)
        arView.addCoaching()
        sessionId = arView.session.identifier
    }
    static func addRecognizers() {
        guard let inst = INSTANCE else { return }
        let tap   = UITapGestureRecognizer(target: inst, action: #selector(inst.viewTapped(_:)))
        let pan   = UIPanGestureRecognizer(target: inst, action: #selector(inst.handlePan(_:)))
        let pinch = UIPinchGestureRecognizer(target: inst, action: #selector(inst.handlePinch(_:)))
        [tap, pan, pinch].forEach { arView.addGestureRecognizer($0) }
    }

    // MARK: Gestures
    @objc private func viewTapped(_ g: UITapGestureRecognizer) {
        let loc = g.location(in: ReplateCameraView.arView)
        let results = ReplateCameraView.arView.raycast(from: loc,
                                                       allowing: .estimatedPlane,
                                                       alignment: .horizontal)
        guard let hit = results.first else { return }
        let anchor = AnchorEntity(world: hit.worldTransform)
        DispatchQueue.main.async {
            ReplateCameraView.dotAnchors.forEach {
                $0.removeFromParent()
                ReplateCameraView.arView.scene.removeAnchor($0)
            }
            ReplateCameraView.dotAnchors = []
            ReplateCameraView.anchorEntity = anchor
            createSpheres(y: spheresHeight)
            createSpheres(y: distanceBetweenCircles + spheresHeight)
            ReplateCameraView.arView.scene.anchors.append(anchor)
        }
    }

    @objc private func handlePan(_ g: UIPanGestureRecognizer) {
        guard let v = g.view as? ARView,
              let a = ReplateCameraView.anchorEntity else { return }
        if g.state == .changed {
            let t = g.translation(in: v)
            let m = v.cameraTransform.matrix
            let forward = normalize(SIMD3(-m.columns.2.x, 0, -m.columns.2.z))
            let right   = normalize(SIMD3( m.columns.0.x, 0,  m.columns.0.z))
            let move = SIMD3<Float>(
                Float( t.x)*right.x + Float( t.y)*forward.x,
                0,
               -Float( t.x)*right.z - Float( t.y)*forward.z
            ) / 7000
            a.position += move
            g.setTranslation(.zero, in: v)
        }
    }

    @objc private func handlePinch(_ g: UIPinchGestureRecognizer) {
        let now = CACurrentMediaTime()
        guard now - lastPinchTime > 0.05, let a = ReplateCameraView.anchorEntity else { return }
        lastPinchTime = now
        if g.state == .changed {
            let s = Float(g.scale)
            a.scale *= SIMD3(repeating: s)
            g.scale = 1.0
        }
    }

    // MARK: Sphere Creation
    private static func createSpheres(y: Float) {
        guard let anchor = anchorEntity else { return }
        var batch: [ModelEntity] = []
        for i in 0..<72 {
            let angle = Float(i) * (Float.pi/36)
            let pos = SIMD3(
                spheresRadius * cos(angle),
                y,
                spheresRadius * sin(angle)
            )
            let sphere = ModelEntity(mesh: baseSphereMesh,
                                     materials: [baseSphereMaterial])
            sphere.position = pos
            batch.append(sphere)
        }
        spheresModels.append(contentsOf: batch)
        batch.forEach { anchor.addChild($0) }
    }

    // MARK: Dot Grid
    func addDots(to planeAnchor: ARPlaneAnchor) {
        let center = planeAnchor.center
        let extent = planeAnchor.extent
        let spacing: Float = 0.05
        var positions: [SIMD3<Float>] = []
        for x in stride(from: -extent.x/2, through: extent.x/2, by: spacing) {
            for z in stride(from: -extent.z/2, through: extent.z/2, by: spacing) {
                positions.append(SIMD3(center.x + x, 0, center.z + z))
            }
        }
        DispatchQueue.main.asyncAfter(deadline: .now()+1.0) {
            for pos in positions {
                let dotAnchor = AnchorEntity(world: planeAnchor.transform)
                let dot = self.createDot(at: pos)
                dotAnchor.addChild(dot)
                ReplateCameraView.arView.scene.addAnchor(dotAnchor)
                ReplateCameraView.dotAnchors.append(dotAnchor)
            }
        }
    }
    func createDot(at position: SIMD3<Float>) -> ModelEntity {
        let size: Float = 0.005
        let mesh = MeshResource.generateBox(size: [size, 0.0001, size], cornerRadius: size/2)
        let mat = SimpleMaterial(color: .white, roughness: 1, isMetallic: false)
        let e = ModelEntity(mesh: mesh, materials: [mat])
        e.position = position
        return e
    }

    // MARK: Reset
    @objc static func reset() {
        arQueue.async {
            lock.lock(); defer { lock.unlock() }
            DispatchQueue.main.async {
                arView.session.pause()
                arView.session.delegate = nil
                arView.scene.anchors.removeAll()
                arView.removeFromSuperview()
                anchorEntity = nil
                spheresModels.removeAll()
                upperSpheresSet = [Bool](repeating: false, count: 72)
                lowerSpheresSet = [Bool](repeating: false, count: 72)
                dotAnchors.removeAll()
                INSTANCE.setupNewARView()
            }
        }
    }
    private func setupNewARView() {
        ReplateCameraView.arView = ARView(frame: bounds)
        addSubview(ReplateCameraView.arView)
        ReplateCameraView.arView.session.delegate = self
        ReplateCameraView.motionManager = CMMotionManager()
        if ReplateCameraView.motionManager.isDeviceMotionAvailable {
            startDeviceMotionUpdates()
        }
        ReplateCameraView.setupAR()
    }

    // MARK: Permissions & Motion
    func requestCameraPermissions() {
        if AVCaptureDevice.authorizationStatus(for: .video) != .authorized {
            AVCaptureDevice.requestAccess(for: .video) { _ in }
        }
    }
    func startDeviceMotionUpdates() {
        ReplateCameraView.motionManager.deviceMotionUpdateInterval = 0.1
        ReplateCameraView.motionManager.startDeviceMotionUpdates(to: .main) { m, _ in
            if let g = m?.gravity {
                ReplateCameraView.gravityVector = ["x": g.x, "y": g.y, "z": g.z]
            }
        }
    }
}

// MARK: - ARError
enum ARError: Error {
    case noAnchor, invalidAnchor, notInFocus, captureError
    case tooManyImages, processingError, savingError
    case transformError, lightingError, notInRange, unknown
    var localizedDescription: String {
        switch self {
        case .noAnchor: return "[ReplateCameraController] No anchor set yet"
        case .invalidAnchor: return "[ReplateCameraController] AnchorNode is not valid"
        case .notInFocus: return "[ReplateCameraController] Object not in focus"
        case .captureError: return "[ReplateCameraController] Error capturing image"
        case .tooManyImages: return "[ReplateCameraController] Too many images / angle repeat"
        case .processingError: return "[ReplateCameraController] Error processing image"
        case .savingError: return "[ReplateCameraController] Error saving photo"
        case .transformError: return "[ReplateCameraController] Camera transform unavailable"
        case .lightingError: return "[ReplateCameraController] Image too dark"
        case .notInRange: return "[ReplateCameraController] Camera not in range"
        case .unknown: return "[ReplateCameraController] Unknown error"
        }
    }
}

// MARK: - DeviceTargetInfo
struct DeviceTargetInfo {
    let isInFocus: Bool
    let targetIndex: Int
    let transform: simd_float4x4
    let cameraPosition: SIMD3<Float>
    let deviceDirection: SIMD3<Float>
    let distance: Float
    var isValidTarget: Bool { targetIndex != -1 }
}

// MARK: - SafeCallbackHandler
class SafeCallbackHandler {
    private let resolver: RCTPromiseResolveBlock
    private let rejecter: RCTPromiseRejectBlock
    private var hasCalledBack = false
    private let lock = NSLock()
    init(resolver: @escaping RCTPromiseResolveBlock,
         rejecter: @escaping RCTPromiseRejectBlock) {
        self.resolver = resolver
        self.rejecter = rejecter
    }
    func resolve(_ result: Any) {
        lock.lock(); defer { lock.unlock() }
        guard !hasCalledBack else { return }
        hasCalledBack = true
        resolver(result)
    }
    func reject(_ error: ARError) {
        lock.lock(); defer { lock.unlock() }
        guard !hasCalledBack else { return }
        hasCalledBack = true
        rejecter(String(describing: error),
                 error.localizedDescription,
                 NSError(domain: "ReplateCameraController", code: 0))
    }
}

// MARK: - ReplateCameraController
@objc(ReplateCameraController)
class ReplateCameraController: NSObject {
    private static let lock = NSLock()
    private static let arQueue = DispatchQueue(label: "com.replate.ar.controller", qos: .userInteractive)
    private static let imageProcessingQueue = DispatchQueue(label: "com.replate.ar.image", qos: .userInitiated, attributes: .concurrent)

    private static let MIN_DISTANCE: Float = 0.25
    private static let MAX_DISTANCE: Float = 0.55
    private static let ANGLE_THRESHOLD: Float = 0.6
    private static let TARGET_IMAGE_SIZE = CGSize(width: 3072, height: 2304)
    private static let MIN_AMBIENT_INTENSITY: CGFloat = 650

    private var lastCaptureTime: TimeInterval = 0
    private var captureCount: Int = 0
    private lazy var imageProcessingContext: CIContext = {
        CIContext(options: [.useSoftwareRenderer: false, .priorityRequestLow: true])
    }()

    static var completedTutorialCallback: RCTResponseSenderBlock?
    static var anchorSetCallback: RCTResponseSenderBlock?
    static var completedUpperSpheresCallback: RCTResponseSenderBlock?
    static var completedLowerSpheresCallback: RCTResponseSenderBlock?
    static var openedTutorialCallback: RCTResponseSenderBlock?
    static var tooCloseCallback: RCTResponseSenderBlock?
    static var tooFarCallback: RCTResponseSenderBlock?

    override init() {
        super.init()
    }

    // MARK: Callback Registration
    @objc func registerOpenedTutorialCallback(_ cb: @escaping RCTResponseSenderBlock) {
        Self.lock.lock(); defer { Self.lock.unlock() }
        Self.openedTutorialCallback = cb
    }
    @objc func registerCompletedTutorialCallback(_ cb: @escaping RCTResponseSenderBlock) {
        Self.lock.lock(); defer { Self.lock.unlock() }
        Self.completedTutorialCallback = cb
    }
    @objc func registerAnchorSetCallback(_ cb: @escaping RCTResponseSenderBlock) {
        Self.lock.lock(); defer { Self.lock.unlock() }
        Self.anchorSetCallback = cb
    }
    @objc func registerCompletedUpperSpheresCallback(_ cb: @escaping RCTResponseSenderBlock) {
        Self.lock.lock(); defer { Self.lock.unlock() }
        Self.completedUpperSpheresCallback = cb
    }
    @objc func registerCompletedLowerSpheresCallback(_ cb: @escaping RCTResponseSenderBlock) {
        Self.lock.lock(); defer { Self.lock.unlock() }
        Self.completedLowerSpheresCallback = cb
    }
    @objc func registerTooCloseCallback(_ cb: @escaping RCTResponseSenderBlock) {
        Self.lock.lock(); defer { Self.lock.unlock() }
        Self.tooCloseCallback = cb
    }
    @objc func registerTooFarCallback(_ cb: @escaping RCTResponseSenderBlock) {
        Self.lock.lock(); defer { Self.lock.unlock() }
        Self.tooFarCallback = cb
    }

    // MARK: Status Queries
    @objc func getPhotosCount(_ resolver: RCTPromiseResolveBlock, rejecter: RCTPromiseRejectBlock) {
        Self.lock.lock(); let c = ReplateCameraView.totalPhotosTaken; Self.lock.unlock()
        resolver(c)
    }
    @objc func isScanComplete(_ resolver: RCTPromiseResolveBlock, rejecter: RCTPromiseRejectBlock) {
        Self.lock.lock(); let done = ReplateCameraView.photosFromDifferentAnglesTaken == 144; Self.lock.unlock()
        resolver(done)
    }
    @objc func getRemainingAnglesToScan(_ resolver: RCTPromiseResolveBlock, rejecter: RCTPromiseRejectBlock) {
        Self.lock.lock(); let rem = 144 - ReplateCameraView.photosFromDifferentAnglesTaken; Self.lock.unlock()
        resolver(rem)
    }
    @objc func reset() {
        DispatchQueue.main.async { ReplateCameraView.reset() }
    }

    // MARK: Photo Capture
    @objc func takePhoto(_ unlimited: Bool = false,
                         resolver: @escaping RCTPromiseResolveBlock,
                         rejecter: @escaping RCTPromiseRejectBlock) {
        Self.arQueue.async { [weak self] in
            guard let self = self else { return }
            let cb = SafeCallbackHandler(resolver: resolver, rejecter: rejecter)

            var pixelBuffer: CVPixelBuffer?
            var camTransform: simd_float4x4?
            var lightEst: ARLightEstimate?
            DispatchQueue.main.sync {
                if let frame = ReplateCameraView.arView.session.currentFrame {
                    pixelBuffer = frame.capturedImage
                    camTransform = frame.camera.transform
                    lightEst = frame.lightEstimate
                }
            }
            guard let buf = pixelBuffer, let camT = camTransform else {
                cb.reject(.captureError); return
            }
            do {
                try self.validateAndProcessPhotoWithData(
                    pixelBuffer: buf,
                    transform: camT,
                    lightEstimate: lightEst,
                    unlimited: unlimited,
                    callback: cb
                )
            } catch let e as ARError {
                cb.reject(e)
            } catch {
                cb.reject(.unknown)
            }
        }
    }

    private func validateAndProcessPhotoWithData(pixelBuffer: CVPixelBuffer,
                                                transform camT: simd_float4x4,
                                                lightEstimate le: ARLightEstimate?,
                                                unlimited: Bool,
                                                callback cb: SafeCallbackHandler) throws {
        let anchor = try getValidAnchorEntity()
        let info = try getDeviceTargetInfoFromTransform(anchorEntity: anchor,
                                                        cameraTransform: camT)
        guard info.isValidTarget else { throw ARError.notInFocus }
        if let light = le, light.ambientIntensity < Self.MIN_AMBIENT_INTENSITY {
            throw ARError.lightingError
        }
        if info.distance < Self.MIN_DISTANCE {
            DispatchQueue.main.async {
                Self.tooCloseCallback?([]); Self.tooCloseCallback = nil
            }
            throw ARError.notInRange
        }
        if info.distance > Self.MAX_DISTANCE {
            DispatchQueue.main.async {
                Self.tooFarCallback?([]); Self.tooFarCallback = nil
            }
            throw ARError.notInRange
        }

        // compute sphereIndex & newAngle
        let angle = angleBetweenAnchorXAndCamera(anchor: anchor, cameraTransform: camT)
        let sphereIndex = max(Int(round(angle/5.0)),0) % 72
        let newAngle = (info.targetIndex==1
            ? !ReplateCameraView.upperSpheresSet[sphereIndex]
            : !ReplateCameraView.lowerSpheresSet[sphereIndex])

        if !unlimited && !newAngle { throw ARError.tooManyImages }

        Self.imageProcessingQueue.async {
            self.processAndSaveImageConcurrently(buf, callback: cb) { success in
                guard success else { return }
                DispatchQueue.main.async {
                    self.updateCircleFocus(targetIndex: info.targetIndex)
                    self.updateSpheresAfterCapture(
                        targetIndex: info.targetIndex,
                        sphereIndex: sphereIndex,
                        isNewAngle: newAngle
                    )
                }
            }
        }
    }

    private func processAndSaveImageConcurrently(_ buf: CVPixelBuffer,
                                                 callback cb: SafeCallbackHandler,
                                                 completion: @escaping (Bool)->Void) {
        autoreleasepool {
            let ciImage = CIImage(cvImageBuffer: buf)
            let context = imageProcessingContext
            guard let resized = optimizedResizeImage(ciImage, to: Self.TARGET_IMAGE_SIZE),
                  let cg = context.createCGImage(resized, from: resized.extent) else {
                cb.reject(.processingError); completion(false); return
            }
            let ui = UIImage(cgImage: cg).rotate(radians: .pi/2)
            guard let url = optimizedSaveImage(ui, quality: getImageQuality()) else {
                cb.reject(.savingError); completion(false); return
            }
            DispatchQueue.global(qos: .background).async {
                self.addMetadataToImage(at: url)
            }
            DispatchQueue.main.async {
                cb.resolve(url.absoluteString)
                completion(true)
            }
        }
    }

    private func optimizedResizeImage(_ image: CIImage, to target: CGSize) -> CIImage? {
        guard let f = CIFilter(name: "CILanczosScaleTransform") else { return nil }
        let s = target.width / image.extent.width
        f.setValue(image, forKey: kCIInputImageKey)
        f.setValue(s,     forKey: kCIInputScaleKey)
        f.setValue(1.0,   forKey: kCIInputAspectRatioKey)
        return f.outputImage
    }

    private func optimizedSaveImage(_ image: UIImage, quality q: CGFloat) -> URL? {
        guard let data = image.jpegData(compressionQuality: q) else { return nil }
        let tmp = URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory: true)
        let url = tmp.appendingPathComponent("image_\(Date().timeIntervalSince1970).jpg")
        do { try data.write(to: url, options: .atomic); return url }
        catch { return nil }
    }

    private func addMetadataToImage(at url: URL) {
        guard let src = CGImageSourceCreateWithURL(url as CFURL, nil),
              let md  = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString:Any],
              let dst = CGImageDestinationCreateWithURL(url as CFURL, kUTTypeJPEG, 1, nil)
        else { return }
        var m = md
        m[kCGImagePropertyExifDictionary] = [
            kCGImagePropertyExifUserComment: getOptimizedTransformJSON()
        ]
        CGImageDestinationAddImageFromSource(dst, src, 0, m as CFDictionary)
        CGImageDestinationFinalize(dst)
    }

    private func getImageQuality() -> CGFloat {
        let now = Date().timeIntervalSince1970
        let delta = now - lastCaptureTime
        lastCaptureTime = now
        if delta < 0.5 {
            captureCount += 1
            return max(0.7, 1.0 - (min(captureCount,5)*0.06))
        } else {
            captureCount = max(0, captureCount-1)
            return 0.9
        }
    }

    private func getOptimizedTransformJSON() -> String {
        guard let frm = ReplateCameraView.arView.session.currentFrame else { return "{}" }
        let t = frm.camera.transform
        let obj: [String:Any] = [
            "position": ["x":t.columns.3.x, "y":t.columns.3.y, "z":t.columns.3.z],
            "gravityVector": ReplateCameraView.gravityVector
        ]
        if let d = try? JSONSerialization.data(withJSONObject: obj),
           let s = String(data: d, encoding: .utf8) {
            return s
        }
        return "{}"
    }

    private func updateCircleFocus(targetIndex: Int) {
        if targetIndex != ReplateCameraView.circleInFocus {
            setOpacityToCircle(circleId: ReplateCameraView.circleInFocus, opacity: 0.5)
            setOpacityToCircle(circleId: targetIndex, opacity: 1.0)
            ReplateCameraView.circleInFocus = targetIndex
            ReplateCameraView.INSTANCE.generateImpactFeedback(strength: .heavy)
        }
    }

    private func updateSpheresAfterCapture(targetIndex: Int, sphereIndex: Int, isNewAngle: Bool) {
        var mesh: ModelEntity?
        var cb: RCTResponseSenderBlock?
        if targetIndex==1 && isNewAngle {
            ReplateCameraView.upperSpheresSet[sphereIndex]=true
            ReplateCameraView.photosFromDifferentAnglesTaken+=1
            mesh = ReplateCameraView.spheresModels[safe:72+sphereIndex]
            if ReplateCameraView.upperSpheresSet.allSatisfy({$0}) {
                cb = Self.completedUpperSpheresCallback; Self.completedUpperSpheresCallback=nil
            }
        } else if targetIndex==0 && isNewAngle {
            ReplateCameraView.lowerSpheresSet[sphereIndex]=true
            ReplateCameraView.photosFromDifferentAnglesTaken+=1
            mesh = ReplateCameraView.spheresModels[safe:sphereIndex]
            if ReplateCameraView.lowerSpheresSet.allSatisfy({$0}) {
                cb = Self.completedLowerSpheresCallback; Self.completedLowerSpheresCallback=nil
            }
        }
        if let m = mesh {
            m.model?.materials[0] = SimpleMaterial(color:.green, roughness:1, isMetallic:false)
            ReplateCameraView.INSTANCE.generateImpactFeedback(strength:.light)
        }
        cb?([])
    }

    private func setOpacityToCircle(circleId: Int, opacity: Float) {
        DispatchQueue.main.async {
            let mat = SimpleMaterial(color: UIColor.white.withAlphaComponent(CGFloat(opacity)),
                                     roughness:1, isMetallic:false)
            for i in 0..<72 {
                let idx = circleId==0 ? i : 72+i
                ReplateCameraView.spheresModels[safe:idx]?.model?.materials[0]=mat
            }
        }
    }

    // MARK: Anchor & Transform Helpers
    private func getValidAnchorEntity() throws -> AnchorEntity {
        var a: AnchorEntity?
        if Thread.isMainThread { a = ReplateCameraView.anchorEntity }
        else { DispatchQueue.main.sync { a = ReplateCameraView.anchorEntity } }
        guard let anchor = a else { throw ARError.noAnchor }
        guard isAnchorNodeValid(anchor) else { throw ARError.invalidAnchor }
        return anchor
    }

    private func getDeviceTargetInfoFromTransform(anchorEntity: AnchorEntity,
                                                  cameraTransform: simd_float4x4) throws -> DeviceTargetInfo {
        let camPos = SIMD3<Float>(cameraTransform.columns.3.x,
                                  cameraTransform.columns.3.y,
                                  cameraTransform.columns.3.z)
        var anchorPos: SIMD3<Float>!
        if Thread.isMainThread { anchorPos = anchorEntity.position(relativeTo:nil) }
        else { DispatchQueue.main.sync { anchorPos = anchorEntity.position(relativeTo:nil) } }
        let devDir = normalize(SIMD3(-cameraTransform.columns.2.x,
                                     -cameraTransform.columns.2.y,
                                     -cameraTransform.columns.2.z))
        let toAnchor = normalize(anchorPos - camPos)
        let angle = acos(dot(devDir, toAnchor))
        let dist  = distanceBetween(camPos, anchorPos)
        let relT  = getTransformRelativeToAnchor(anchor: anchorEntity, cameraTransform: cameraTransform)
        let ti    = determineTargetIndex(angleToAnchor: angle, relativeCameraTransform: relT)
        return DeviceTargetInfo(isInFocus: angle<Self.ANGLE_THRESHOLD,
                                targetIndex: ti,
                                transform: relT,
                                cameraPosition: camPos,
                                deviceDirection: devDir,
                                distance: dist)
    }

    private func determineTargetIndex(angleToAnchor: Float,
                                      relativeCameraTransform: simd_float4x4) -> Int {
        guard angleToAnchor < Self.ANGLE_THRESHOLD else { return -1 }
        let twoThirds = ReplateCameraView.spheresHeight
                      + ReplateCameraView.distanceBetweenCircles
                      + ReplateCameraView.distanceBetweenCircles/5
        let camH = relativeCameraTransform.columns.3.y
        return camH < twoThirds ? 0 : 1
    }

    private func isAnchorNodeValid(_ anchor: AnchorEntity) -> Bool {
        var ok = false
        if Thread.isMainThread {
            let t = anchor.transform
            ok = !t.translation.isNaN &&
                 !t.rotation.isNaN &&
                 t.scale != SIMD3<Float>(repeating:0) &&
                 abs(length(t.rotation.vector)-1.0)<0.0001
        } else {
            DispatchQueue.main.sync {
                let t = anchor.transform
                ok = !t.translation.isNaN &&
                     !t.rotation.isNaN &&
                     t.scale != SIMD3<Float>(repeating:0) &&
                     abs(length(t.rotation.vector)-1.0)<0.0001
            }
        }
        return ok
    }

    private func distanceBetween(_ p1: SIMD3<Float>, _ p2: SIMD3<Float>) -> Float {
        sqrt(dot(p1-p2, p1-p2))
    }

    private func getTransformRelativeToAnchor(anchor: AnchorEntity,
                                              cameraTransform: simd_float4x4) -> simd_float4x4 {
        var rel: simd_float4x4!
        if Thread.isMainThread {
            rel = anchor.transformMatrix(relativeTo:nil).inverse * cameraTransform
        } else {
            DispatchQueue.main.sync {
                rel = anchor.transformMatrix(relativeTo:nil).inverse * cameraTransform
            }
        }
        return rel
    }

    private func angleBetweenAnchorXAndCamera(anchor: AnchorEntity,
                                              cameraTransform: simd_float4x4) -> Float {
        var deg: Float = 0
        if Thread.isMainThread {
            let at = anchor.transform.matrix
            let ap = simd_float2(anchor.transform.translation.x,
                                 anchor.transform.translation.z)
            let cp = simd_float2(cameraTransform.columns.3.x,
                                 cameraTransform.columns.3.z)
            let dir = cp - ap
            let xAxis = simd_float2(at.columns.0.x, at.columns.0.z)
            let a = atan2(dir.y, dir.x) - atan2(xAxis.y, xAxis.x)
            deg = a * (180.0/.pi)
            if deg<0 { deg+=360 }
        } else {
            DispatchQueue.main.sync {
                let at = anchor.transform.matrix
                let ap = simd_float2(anchor.transform.translation.x,
                                     anchor.transform.translation.z)
                let cp = simd_float2(cameraTransform.columns.3.x,
                                     cameraTransform.columns.3.z)
                let dir = cp - ap
                let xAxis = simd_float2(at.columns.0.x, at.columns.0.z)
                let a = atan2(dir.y, dir.x) - atan2(xAxis.y, xAxis.x)
                deg = a * (180.0/.pi)
                if deg<0 { deg+=360 }
            }
        }
        return deg
    }

    // Legacy for compatibility
    func saveImageAsJPEG(_ image: UIImage) -> URL? {
        guard let data = image.jpegData(compressionQuality:1),
              let src = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        let tmp = URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory:true)
        let url = tmp.appendingPathComponent("image_\(Date().timeIntervalSince1970).jpg")
        guard let props = CGImageSourceCopyPropertiesAtIndex(src,0,nil) as? [CFString:Any],
              let dst   = CGImageDestinationCreateWithURL(url as CFURL,
                                                          kUTTypeJPEG,1,nil)
        else { return nil }
        var m = props
        m[kCGImagePropertyExifDictionary] = [
            kCGImagePropertyExifUserComment: getOptimizedTransformJSON()
        ]
        CGImageDestinationAddImageFromSource(dst,src,0,m as CFDictionary)
        guard CGImageDestinationFinalize(dst) else { return nil }
        return url
    }

    func saveImageAsPNG(_ image: UIImage) -> URL? {
        guard let data = image.pngData(),
              let src = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        let tmp = URL(fileURLWithPath: NSTemporaryDirectory(), isDirectory:true)
        let url = tmp.appendingPathComponent("image_\(Date().timeIntervalSince1970).png")
        guard let props = CGImageSourceCopyPropertiesAtIndex(src,0,nil) as? [CFString:Any],
              let dst   = CGImageDestinationCreateWithURL(url as CFURL,
                                                          kUTTypePNG,1,nil)
        else { return nil }
        var m = props
        m[kCGImagePropertyPNGDictionary] = [
            kCGImagePropertyPNGComment: getOptimizedTransformJSON()
        ]
        CGImageDestinationAddImageFromSource(dst,src,0,m as CFDictionary)
        guard CGImageDestinationFinalize(dst) else { return nil }
        return url
    }

    func getTransformJSON(session: ARSession) -> String {
        let t = session.currentFrame?.camera.transform ?? simd_float4x4()
        let translation = [
            "x": t.columns.3.x,
            "y": t.columns.3.y,
            "z": t.columns.3.z
        ]
        let scale = [
            "x": simd_length(simd_float3(t.columns.0.x,
                                         t.columns.0.y,
                                         t.columns.0.z)),
            "y": simd_length(simd_float3(t.columns.1.x,
                                         t.columns.1.y,
                                         t.columns.1.z)),
            "z": simd_length(simd_float3(t.columns.2.x,
                                         t.columns.2.y,
                                         t.columns.2.z))
        ]
        let rotMat = simd_float3x3(columns: (
            simd_float3(t.columns.0.x/scale["x"]!,t.columns.0.y/scale["x"]!,t.columns.0.z/scale["x"]!),
            simd_float3(t.columns.1.x/scale["y"]!,t.columns.1.y/scale["y"]!,t.columns.1.z/scale["y"]!),
            simd_float3(t.columns.2.x/scale["z"]!,t.columns.2.y/scale["z"]!,t.columns.2.z/scale["z"]!)
        ))
        let q = simd_quatf(rotMat)
        let rotation = ["x":q.vector.x,"y":q.vector.y,"z":q.vector.z,"w":q.vector.w]
        let obj: [String:Any] = [
            "translation": translation,
            "rotation": rotation,
            "scale": scale,
            "gravityVector": ReplateCameraView.gravityVector
        ]
        if let d = try? JSONSerialization.data(withJSONObject: obj, options: .prettyPrinted),
           let s = String(data: d, encoding: .utf8) {
            return s
        }
        return "{}"
    }
}

// MARK: - ARView Coaching Extension
extension ARView: ARCoachingOverlayViewDelegate {
    func addCoaching() {
        let overlay = ARCoachingOverlayView()
        overlay.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        addSubview(overlay)
        overlay.center = convert(center, from: superview)
        overlay.goal = .horizontalPlane
        overlay.session = session
        overlay.delegate = self
        overlay.setActive(true, animated: true)
        ReplateCameraView.INSTANCE.generateImpactFeedback(strength: .light)
        if let cb = ReplateCameraController.openedTutorialCallback {
            cb([]); ReplateCameraController.openedTutorialCallback = nil
        }
    }
    public func coachingOverlayViewDidDeactivate(_ coachingOverlayView: ARCoachingOverlayView) {
        if let cb = ReplateCameraController.completedTutorialCallback {
            cb([]); ReplateCameraController.completedTutorialCallback = nil
        }
        ReplateCameraView.INSTANCE.generateImpactFeedback(strength: .heavy)
        ReplateCameraView.addRecognizers()
    }
}

// MARK: - UIImage averageColor
extension UIImage {
    func averageColor() -> UIColor? {
        guard let cg = cgImage,
              let dataProv = cg.dataProvider,
              let data = dataProv.data else { return nil }
        let ptr = CFDataGetBytePtr(data)
        let w = cg.width, h = cg.height
        var r: CGFloat=0, g: CGFloat=0, b: CGFloat=0
        for y in 0..<h {
            for x in 0..<w {
                let idx = ((w*y)+x)*4
                r += CGFloat(ptr[idx])   /255
                g += CGFloat(ptr[idx+1]) /255
                b += CGFloat(ptr[idx+2]) /255
            }
        }
        let count = CGFloat(w*h)
        return UIColor(red: r/count, green: g/count, blue: b/count, alpha: 1)
    }
}

// MARK: - UIColor RGB Components
extension UIColor {
    func getRGBComponents() -> (red:CGFloat, green:CGFloat, blue:CGFloat)? {
        var r:CGFloat=0, g:CGFloat=0, b:CGFloat=0, a:CGFloat=0
        guard getRed(&r, green:&g, blue:&b, alpha:&a) else { return nil }
        return (r,g,b)
    }
}

// MARK: - SIMD & simd_quatf Extensions
public extension simd_float2 {
    static func -(lhs: simd_float2, rhs: simd_float2) -> simd_float2 {
        return simd_float2(lhs.x - rhs.x, lhs.y - rhs.y)
    }
}
public extension SIMD3 where Scalar == Float {
    var isNaN: Bool { x.isNaN || y.isNaN || z.isNaN }
}
public extension simd_quatf {
    var isNaN: Bool { vector.x.isNaN || vector.y.isNaN || vector.w.isNaN }
}
