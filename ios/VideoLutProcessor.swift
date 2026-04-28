import AVFoundation
import CoreImage
import Foundation
import UIKit

enum VideoLutError: Error {
  case imageLoadFailed(String)
  case invalidHaldLutDimensions(Int, Int)
  case exportSessionUnavailable
  case unsupportedOutputType
  case exportFailed(String)
}

private struct CoreImageColorCube {
  let dimension: Int
  let data: Data

  init(lutPath: String) throws {
    if lutPath.lowercased().hasSuffix(".cube") {
      let cube = try CubeLutParser.parse(filePath: lutPath)
      self.dimension = cube.size
      self.data = Self.floatData(fromRGBA8: cube.rgba8)
      return
    }

    let parsed = try Self.parseHaldPng(path: lutPath)
    self.dimension = parsed.dimension
    self.data = parsed.data
  }

  private static func floatData(fromRGBA8 rgba8: [UInt8]) -> Data {
    var floats = [Float](repeating: 0, count: rgba8.count)
    for i in stride(from: 0, to: rgba8.count, by: 4) {
      floats[i + 0] = Float(rgba8[i + 0]) / 255.0
      floats[i + 1] = Float(rgba8[i + 1]) / 255.0
      floats[i + 2] = Float(rgba8[i + 2]) / 255.0
      floats[i + 3] = 1.0
    }
    return floats.withUnsafeBufferPointer { Data(buffer: $0) }
  }

  private static func parseHaldPng(path: String) throws -> (dimension: Int, data: Data) {
    guard let cgImage = UIImage(contentsOfFile: path)?.cgImage else {
      throw VideoLutError.imageLoadFailed(path)
    }

    let side = cgImage.width
    guard side == cgImage.height else {
      throw VideoLutError.invalidHaldLutDimensions(cgImage.width, cgImage.height)
    }

    let haldLevel = Int(round(cbrt(Float(side))))
    guard haldLevel > 1, abs(pow(Float(haldLevel), 3.0) - Float(side)) < 0.5 else {
      throw VideoLutError.invalidHaldLutDimensions(cgImage.width, cgImage.height)
    }

    let cubeDimension = haldLevel * haldLevel
    let colorSpace = CGColorSpaceCreateDeviceRGB()
    let bytesPerRow = side * 4
    var pixels = [UInt8](repeating: 0, count: bytesPerRow * side)
    let bitmapInfo = CGBitmapInfo.byteOrder32Big.rawValue
      | CGImageAlphaInfo.premultipliedLast.rawValue

    let drewImage = pixels.withUnsafeMutableBytes { rawBuffer -> Bool in
      guard
        let baseAddress = rawBuffer.baseAddress,
        let ctx = CGContext(
          data: baseAddress,
          width: side,
          height: side,
          bitsPerComponent: 8,
          bytesPerRow: bytesPerRow,
          space: colorSpace,
          bitmapInfo: bitmapInfo
        )
      else {
        return false
      }

      ctx.draw(cgImage, in: CGRect(x: 0, y: 0, width: side, height: side))
      return true
    }

    guard drewImage else {
      throw VideoLutError.imageLoadFailed(path)
    }

    let cubeCount = cubeDimension * cubeDimension * cubeDimension
    var floats = [Float](repeating: 0, count: cubeCount * 4)
    for b in 0..<cubeDimension {
      for g in 0..<cubeDimension {
        for r in 0..<cubeDimension {
          let haldIndex = b * cubeDimension * cubeDimension + g * cubeDimension + r
          let x = haldIndex % side
          let y = haldIndex / side
          let src = (y * side + x) * 4
          let dst = haldIndex * 4
          floats[dst + 0] = Float(pixels[src + 0]) / 255.0
          floats[dst + 1] = Float(pixels[src + 1]) / 255.0
          floats[dst + 2] = Float(pixels[src + 2]) / 255.0
          floats[dst + 3] = 1.0
        }
      }
    }

    return (cubeDimension, floats.withUnsafeBufferPointer { Data(buffer: $0) })
  }
}

enum VideoLUTProcessor {
  static func applyLutToVideo(
    videoPath: String,
    lutPath: String,
    intensity: Float
  ) throws -> String {
    let normalizedVideo = normalizeVideoFilePath(videoPath)
    let normalizedLut = normalizeVideoFilePath(lutPath)
    let colorCube = try CoreImageColorCube(lutPath: normalizedLut)
    let asset = AVURLAsset(url: URL(fileURLWithPath: normalizedVideo))
    let composition = AVVideoComposition(asset: asset) { request in
      let source = request.sourceImage.clampedToExtent()
      let output = applyColorCube(colorCube, to: source, intensity: intensity)
        .cropped(to: request.sourceImage.extent)
      request.finish(with: output, context: nil)
    }

    let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first!
    let outputURL = dir.appendingPathComponent("lut_video_\(UUID().uuidString).mp4")
    try? FileManager.default.removeItem(at: outputURL)

    guard let export = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetHighestQuality) else {
      throw VideoLutError.exportSessionUnavailable
    }
    export.videoComposition = composition
    export.outputURL = outputURL
    export.outputFileType = .mp4
    export.shouldOptimizeForNetworkUse = true

    guard export.supportedFileTypes.contains(.mp4) else {
      throw VideoLutError.unsupportedOutputType
    }

    let semaphore = DispatchSemaphore(value: 0)
    export.exportAsynchronously {
      semaphore.signal()
    }
    semaphore.wait()

    if export.status == .completed {
      return outputURL.path
    }

    let message = export.error?.localizedDescription ?? "Unknown export failure"
    throw VideoLutError.exportFailed(message)
  }

  private static func applyColorCube(
    _ colorCube: CoreImageColorCube,
    to image: CIImage,
    intensity: Float
  ) -> CIImage {
    guard intensity > 0.001 else {
      return image
    }

    guard let cube = CIFilter(name: "CIColorCubeWithColorSpace") else {
      return image
    }
    cube.setValue(image, forKey: kCIInputImageKey)
    cube.setValue(colorCube.dimension, forKey: "inputCubeDimension")
    cube.setValue(colorCube.data, forKey: "inputCubeData")
    cube.setValue(CGColorSpaceCreateDeviceRGB(), forKey: "inputColorSpace")

    guard let graded = cube.outputImage else {
      return image
    }

    guard intensity < 0.999 else {
      return graded
    }

    guard let alpha = CIFilter(name: "CIColorMatrix") else {
      return graded
    }
    alpha.setValue(graded, forKey: kCIInputImageKey)
    alpha.setValue(CIVector(x: 1, y: 0, z: 0, w: 0), forKey: "inputRVector")
    alpha.setValue(CIVector(x: 0, y: 1, z: 0, w: 0), forKey: "inputGVector")
    alpha.setValue(CIVector(x: 0, y: 0, z: 1, w: 0), forKey: "inputBVector")
    alpha.setValue(CIVector(x: 0, y: 0, z: 0, w: CGFloat(intensity)), forKey: "inputAVector")

    guard
      let translucent = alpha.outputImage,
      let blend = CIFilter(name: "CISourceOverCompositing")
    else {
      return graded
    }
    blend.setValue(translucent, forKey: kCIInputImageKey)
    blend.setValue(image, forKey: kCIInputBackgroundImageKey)

    return blend.outputImage ?? graded
  }
}

private func normalizeVideoFilePath(_ path: String) -> String {
  var s = path
  if s.hasPrefix("file://") {
    s = String(s.dropFirst(7))
  }
  return (s as NSString).standardizingPath
}

extension VideoLutError: CustomStringConvertible {
  var description: String {
    switch self {
    case .imageLoadFailed(let p): return "Could not load LUT image: \(p)"
    case .invalidHaldLutDimensions(let w, let h):
      return "Invalid Hald CLUT: expected square side = L^3 (e.g. 64, 512), got \(w)x\(h)"
    case .exportSessionUnavailable: return "Could not create video export session"
    case .unsupportedOutputType: return "MP4 video export is not supported for this asset"
    case .exportFailed(let message): return "Failed to export LUT video: \(message)"
    }
  }
}
