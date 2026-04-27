import Foundation
import Metal
import MetalKit
import NitroModules
import UIKit

private struct LutUniforms {
  var level: Float
  var intensity: Float
  var padding: SIMD2<Float>
}

private struct CubeUniforms {
  var domainMin: SIMD4<Float>
  var domainMax: SIMD4<Float>
  var param: SIMD4<Float>
}

enum MetalLutError: Error {
  case noDevice
  case failedToBuildPipeline(String)
  case invalidLutDimensions(Int, Int)
  case imageLoadFailed(String)
  case renderFailed
  case invalidCubeFile(String)
}

final class MetalLutRenderer {
  private let device: MTLDevice
  private let commandQueue: MTLCommandQueue
  private let pipelineStateHald: MTLRenderPipelineState
  private let pipelineStateCube: MTLRenderPipelineState
  private let textureLoader: MTKTextureLoader

  init() throws {
    guard let dev = MTLCreateSystemDefaultDevice() else {
      throw MetalLutError.noDevice
    }
    self.device = dev
    guard let queue = dev.makeCommandQueue() else {
      throw MetalLutError.noDevice
    }
    self.commandQueue = queue
    self.textureLoader = MTKTextureLoader(device: dev)

    guard let library = dev.makeDefaultLibrary() else {
      throw MetalLutError.failedToBuildPipeline("Missing default Metal library")
    }
    guard let vfn = library.makeFunction(name: "lutVertex") else {
      throw MetalLutError.failedToBuildPipeline("LUT vertex not found")
    }
    self.pipelineStateHald = try Self.makePipeline(
      device: dev,
      label: "hald",
      vertex: vfn,
      fragment: library.makeFunction(name: "lutFragment")
    )
    self.pipelineStateCube = try Self.makePipeline(
      device: dev,
      label: "cube",
      vertex: vfn,
      fragment: library.makeFunction(name: "lutFragmentCube")
    )
  }

  private static func makePipeline(
    device: MTLDevice,
    label: String,
    vertex: MTLFunction,
    fragment: MTLFunction?
  ) throws -> MTLRenderPipelineState {
    guard let ffn = fragment else {
      throw MetalLutError.failedToBuildPipeline("Missing fragment: \(label)")
    }
    let desc = MTLRenderPipelineDescriptor()
    desc.label = "Lut_\(label)"
    desc.vertexFunction = vertex
    desc.fragmentFunction = ffn
    desc.colorAttachments[0].pixelFormat = .rgba8Unorm
    do {
      return try device.makeRenderPipelineState(descriptor: desc)
    } catch {
      throw MetalLutError.failedToBuildPipeline(error.localizedDescription)
    }
  }

  private static func haldLevel(fromLutSide side: Int) throws -> Float {
    let s = Float(side)
    let r = cbrtf(s)
    let L = Int(round(r))
    guard L > 1, abs(powf(Float(L), 3) - s) < 0.5 else {
      throw MetalLutError.invalidLutDimensions(side, side)
    }
    return Float(L)
  }

  private func loadTexture(path: String, srgb: Bool) throws -> MTLTexture {
    let url = URL(fileURLWithPath: path)
    let opts: [MTKTextureLoader.Option: Any] = [
      .SRGB: srgb,
      .generateMipmaps: false,
    ]
    return try textureLoader.newTexture(URL: url, options: opts)
  }

  private func loadSourceTexture(from image: UIImage) throws -> MTLTexture {
    guard let cg = image.cgImage else {
      throw MetalLutError.imageLoadFailed("No CGImage")
    }
    let opts: [MTKTextureLoader.Option: Any] = [
      .SRGB: true,
      .generateMipmaps: false,
    ]
    return try textureLoader.newTexture(cgImage: cg, options: opts)
  }

  private func makeTexture3DFromCubeLut(
    _ data: CubeLutData
  ) throws -> MTLTexture {
    let n = data.size
    let desc = MTLTextureDescriptor()
    desc.textureType = .type3D
    desc.pixelFormat = .rgba8Unorm
    desc.width = n
    desc.height = n
    desc.depth = n
    desc.mipmapLevelCount = 1
    desc.usage = .shaderRead
    desc.storageMode = .shared
    guard let tex = device.makeTexture(descriptor: desc) else {
      throw MetalLutError.renderFailed
    }
    data.rgba8.withUnsafeBufferPointer { ptr in
      let region = MTLRegion(
        origin: MTLOrigin(x: 0, y: 0, z: 0),
        size: MTLSize(width: n, height: n, depth: n)
      )
      tex.replace(
        region: region,
        mipmapLevel: 0,
        withBytes: ptr.baseAddress!,
        bytesPerRow: n * 4,
        bytesPerImage: n * n * 4
      )
    }
    return tex
  }

  func applyLutToFile(
    imagePath: String,
    lutPath: String,
    intensity: Float
  ) throws -> String {
    let normalizedImage = normalizeFilePath(imagePath)
    let normalizedLut = normalizeFilePath(lutPath)
    if normalizedLut.lowercased().hasSuffix(".cube") {
      return try applyCubeLut(
        imagePath: normalizedImage,
        cubePath: normalizedLut,
        intensity: intensity
      )
    }
    return try applyHaldLut(
      imagePath: normalizedImage,
      haldPngPath: normalizedLut,
      intensity: intensity
    )
  }

  private func applyHaldLut(
    imagePath: String,
    haldPngPath: String,
    intensity: Float
  ) throws -> String {
    guard let uiImage = UIImage(contentsOfFile: imagePath) else {
      throw MetalLutError.imageLoadFailed(imagePath)
    }
    let fixed = uiImage.normalizedUpOrientation()
    guard let cg = fixed.cgImage else {
      throw MetalLutError.imageLoadFailed("Could not get CGImage")
    }
    let width = cg.width
    let height = cg.height
    guard width > 0, height > 0 else {
      throw MetalLutError.imageLoadFailed("Invalid image size")
    }
    let sourceTexture = try loadSourceTexture(from: fixed)
    let lutTexture = try loadTexture(path: haldPngPath, srgb: true)
    guard lutTexture.width == lutTexture.height else {
      throw MetalLutError.invalidLutDimensions(lutTexture.width, lutTexture.height)
    }
    let level = try Self.haldLevel(fromLutSide: lutTexture.width)
    guard
      let outTexture = makeOutputTexture(
        width: width,
        height: height
      )
    else {
      throw MetalLutError.renderFailed
    }
    var uniforms = LutUniforms(
      level: level,
      intensity: intensity,
      padding: .zero
    )
    var uni = uniforms
    try renderToTexture(
      outTexture: outTexture,
      width: width,
      height: height,
      pipeline: pipelineStateHald,
      setupEncoder: { enc in
        enc.setFragmentBuffer(&uni, length: MemoryLayout<LutUniforms>.stride, index: 0)
        enc.setFragmentTexture(sourceTexture, index: 0)
        enc.setFragmentTexture(lutTexture, index: 1)
      }
    )
    return try writeTextureToPng(outTexture, width: width, height: height).path
  }

  private func applyCubeLut(
    imagePath: String,
    cubePath: String,
    intensity: Float
  ) throws -> String {
    let parsed: CubeLutData
    do {
      parsed = try CubeLutParser.parse(filePath: cubePath)
    } catch {
      if let c = error as? CubeLutError {
        throw MetalLutError.invalidCubeFile(c.description)
      }
      throw error
    }
    guard let uiImage = UIImage(contentsOfFile: imagePath) else {
      throw MetalLutError.imageLoadFailed(imagePath)
    }
    let fixed = uiImage.normalizedUpOrientation()
    guard let cg = fixed.cgImage else {
      throw MetalLutError.imageLoadFailed("Could not get CGImage")
    }
    let width = cg.width
    let height = cg.height
    guard width > 0, height > 0 else {
      throw MetalLutError.imageLoadFailed("Invalid image size")
    }
    let sourceTexture = try loadSourceTexture(from: fixed)
    let cubeTex = try makeTexture3DFromCubeLut(parsed)
    guard
      let outTexture = makeOutputTexture(
        width: width,
        height: height
      )
    else {
      throw MetalLutError.renderFailed
    }
    var u = CubeUniforms(
      domainMin: SIMD4(parsed.domainMin.x, parsed.domainMin.y, parsed.domainMin.z, 0),
      domainMax: SIMD4(parsed.domainMax.x, parsed.domainMax.y, parsed.domainMax.z, 0),
      param: SIMD4(intensity, 0, 0, 0)
    )
    var uni = u
    try renderToTexture(
      outTexture: outTexture,
      width: width,
      height: height,
      pipeline: pipelineStateCube,
      setupEncoder: { enc in
        enc.setFragmentBuffer(&uni, length: MemoryLayout<CubeUniforms>.stride, index: 0)
        enc.setFragmentTexture(sourceTexture, index: 0)
        enc.setFragmentTexture(cubeTex, index: 1)
      }
    )
    return try writeTextureToPng(outTexture, width: width, height: height).path
  }

  private func makeOutputTexture(
    width: Int,
    height: Int
  ) -> MTLTexture? {
    let outDesc = MTLTextureDescriptor.texture2DDescriptor(
      pixelFormat: .rgba8Unorm,
      width: width,
      height: height,
      mipmapped: false
    )
    outDesc.storageMode = .shared
    outDesc.usage = [.renderTarget, .shaderRead]
    return device.makeTexture(descriptor: outDesc)
  }

  private func renderToTexture(
    outTexture: MTLTexture,
    width: Int,
    height: Int,
    pipeline: MTLRenderPipelineState,
    setupEncoder: (MTLRenderCommandEncoder) -> Void
  ) throws {
    let pass = MTLRenderPassDescriptor()
    pass.colorAttachments[0].texture = outTexture
    pass.colorAttachments[0].loadAction = .clear
    pass.colorAttachments[0].storeAction = .store
    pass.colorAttachments[0].clearColor = MTLClearColor(red: 0, green: 0, blue: 0, alpha: 1)
    guard let commandBuffer = commandQueue.makeCommandBuffer(),
          let encoder = commandBuffer.makeRenderCommandEncoder(descriptor: pass) else {
      throw MetalLutError.renderFailed
    }
    encoder.setRenderPipelineState(pipeline)
    setupEncoder(encoder)
    encoder.setViewport(
      MTLViewport(
        originX: 0, originY: 0, width: Double(width), height: Double(height), znear: 0, zfar: 1
      )
    )
    encoder.drawPrimitives(type: .triangle, vertexStart: 0, vertexCount: 3)
    encoder.endEncoding()
    commandBuffer.commit()
    commandBuffer.waitUntilCompleted()
  }

  private func writeTextureToPng(
    _ texture: MTLTexture,
    width: Int,
    height: Int
  ) throws -> URL {
    let rowBytes = width * 4
    var raw = [UInt8](repeating: 0, count: rowBytes * height)
    raw.withUnsafeMutableBytes { ptr in
      guard let base = ptr.baseAddress else { return }
      let region = MTLRegion(
        origin: MTLOrigin(x: 0, y: 0, z: 0),
        size: MTLSize(width: width, height: height, depth: 1)
      )
      texture.getBytes(
        base,
        bytesPerRow: rowBytes,
        from: region,
        mipmapLevel: 0
      )
    }
    for i in stride(from: 0, to: rowBytes * height, by: 4) {
      raw.swapAt(i, i + 2)
    }
    let colorSpace = CGColorSpaceCreateDeviceRGB()
    let bitmapInfo = CGBitmapInfo.byteOrder32Little.rawValue
      | CGImageAlphaInfo.premultipliedLast.rawValue
    return try raw.withUnsafeMutableBufferPointer { buf in
      guard let dataPtr = buf.baseAddress else {
        throw MetalLutError.renderFailed
      }
      guard
        let ctx = CGContext(
          data: dataPtr,
          width: width,
          height: height,
          bitsPerComponent: 8,
          bytesPerRow: rowBytes,
          space: colorSpace,
          bitmapInfo: bitmapInfo
        ),
        let cgOut = ctx.makeImage()
      else {
        throw MetalLutError.renderFailed
      }
      let outUi = UIImage(cgImage: cgOut, scale: 1, orientation: .up)
      guard let png = outUi.pngData() else {
        throw MetalLutError.renderFailed
      }
      let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first!
      let name = "lut_\(UUID().uuidString).png"
      let url = dir.appendingPathComponent(name)
      try png.write(to: url, options: .atomic)
      return url
    }
  }
}

private func normalizeFilePath(_ path: String) -> String {
  var s = path
  if s.hasPrefix("file://") {
    s = String(s.dropFirst(7))
  }
  return (s as NSString).standardizingPath
}

private extension UIImage {
  func normalizedUpOrientation() -> UIImage {
    if imageOrientation == .up { return self }
    let format = UIGraphicsImageRendererFormat()
    format.scale = 1
    let renderer = UIGraphicsImageRenderer(size: size, format: format)
    return renderer.image { _ in
      self.draw(in: CGRect(origin: .zero, size: size))
    }
  }
}

extension MetalLutError: CustomStringConvertible {
  var description: String {
    switch self {
    case .noDevice: return "Metal is not available on this device"
    case .failedToBuildPipeline(let m): return "Metal pipeline: \(m)"
    case .invalidLutDimensions(let w, let h):
      return "Invalid Hald CLUT: expected square side = L^3 (e.g. 64, 512), got \(w)x\(h)"
    case .imageLoadFailed(let p): return "Could not load image: \(p)"
    case .renderFailed: return "Failed to render LUT"
    case .invalidCubeFile(let m): return m
    }
  }
}
