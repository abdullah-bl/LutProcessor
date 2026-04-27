import Foundation

/// Minimal Iridas/Adobe .cube 3D LUT (ASCII) parser: LUT_3D_SIZE, optional DOMAIN, then N^3 R G B lines.
/// File order: blue fastest, then green, then red (common Resolve/Adobe export), mapped to 3D texels (R→x, G→y, B→z).
struct CubeLutData {
  let size: Int
  var domainMin: SIMD3<Float> = .init(0, 0, 0)
  var domainMax: SIMD3<Float> = .init(1, 1, 1)
  /// RGBA8 storage: R-fastest in each slice, layout matches glTexImage3D / Metal 3D (x, y, z) = (r, g, b)
  var rgba8: [UInt8]
}

enum CubeLutError: Error, CustomStringConvertible {
  case fileReadFailed
  case missingSize
  case invalidSize(Int)
  case wrongLineCount(expected: Int, got: Int)
  case badDataLine(String)

  var description: String {
    switch self {
    case .fileReadFailed: return "Could not read .cube file"
    case .missingSize: return "Missing LUT_3D_SIZE in .cube"
    case .invalidSize(let n): return "Invalid LUT_3D_SIZE: \(n)"
    case .wrongLineCount(let e, let g): return "Expected \(e) RGB lines in .cube, got \(g)"
    case .badDataLine(let s): return "Invalid .cube data line: \(s)"
    }
  }
}

enum CubeLutParser {
  static func parse(filePath: String) throws -> CubeLutData {
    let url = URL(fileURLWithPath: filePath)
    let text: String
    do {
      text = try String(contentsOf: url, encoding: .utf8)
    } catch {
      throw CubeLutError.fileReadFailed
    }
    return try parse(text: text)
  }

  static func parse(text: String) throws -> CubeLutData {
    var size = 0
    var domainMin = SIMD3<Float>(0, 0, 0)
    var domainMax = SIMD3<Float>(1, 1, 1)
    var dataLines: [String] = []
    for raw in text.split(whereSeparator: \.isNewline) {
      let line = String(raw).trimmingCharacters(in: .whitespaces)
      if line.isEmpty || line.hasPrefix("#") { continue }
      if line.uppercased().hasPrefix("TITLE") { continue }
      if line.uppercased().hasPrefix("LUT_3D_SIZE") {
        let parts = line.split { $0.isWhitespace }
        guard let last = parts.last, let n = Int(last), n >= 2, n <= 256 else {
          throw CubeLutError.missingSize
        }
        size = n
        continue
      }
      if line.uppercased().hasPrefix("DOMAIN_MIN") {
        domainMin = try parseDomainComponents(line, header: "DOMAIN_MIN")
        continue
      }
      if line.uppercased().hasPrefix("DOMAIN_MAX") {
        domainMax = try parseDomainComponents(line, header: "DOMAIN_MAX")
        continue
      }
      if size > 0 {
        dataLines.append(line)
      }
    }
    guard size > 0 else { throw CubeLutError.missingSize }
    let expected = size * size * size
    guard dataLines.count == expected else {
      throw CubeLutError.wrongLineCount(expected: expected, got: dataLines.count)
    }
    var rgba = [UInt8](repeating: 0, count: expected * 4)
    // File order: b fastest, g, r slow (index i: b = i%N, g = (i/N)%N, r = i/(N*N))
    for (i, line) in dataLines.enumerated() {
      let rgb = try parseRGBLine(line)
      let n = size
      let b = i % n
      let g = (i / n) % n
      let r = i / (n * n)
      // Memory: x=r, y=g, z=b fastest in x: offset = r + g*size + b*size*size
      let mem = (r + g * n + b * n * n) * 4
      let cr = min(1, max(0, rgb.r))
      let cg = min(1, max(0, rgb.g))
      let cb = min(1, max(0, rgb.b))
      rgba[mem + 0] = UInt8(min(255, max(0, Int((cr * 255.0).rounded()))))
      rgba[mem + 1] = UInt8(min(255, max(0, Int((cg * 255.0).rounded()))))
      rgba[mem + 2] = UInt8(min(255, max(0, Int((cb * 255.0).rounded()))))
      rgba[mem + 3] = 255
    }
    return CubeLutData(size: size, domainMin: domainMin, domainMax: domainMax, rgba8: rgba)
  }

  private static func parseDomainComponents(
    _ line: String,
    header: String
  ) throws -> SIMD3<Float> {
    let parts = line.split { $0.isWhitespace || $0 == "\t" }.filter { !$0.isEmpty }
    guard parts.count >= 4,
          parts[0].uppercased() == header.uppercased(),
          let a = Float(parts[1]),
          let b = Float(parts[2]),
          let c = Float(parts[3]) else {
      throw CubeLutError.badDataLine(line)
    }
    return SIMD3(a, b, c)
  }

  private static func parseRGBLine(_ line: String) throws -> (r: Float, g: Float, b: Float) {
    let parts = line.split { $0.isWhitespace || $0 == "\t" }.filter { !$0.isEmpty }
    guard parts.count >= 3,
          let r = Float(parts[0]), let g = Float(parts[1]), let b = Float(parts[2]) else {
      throw CubeLutError.badDataLine(line)
    }
    return (r, g, b)
  }
}
