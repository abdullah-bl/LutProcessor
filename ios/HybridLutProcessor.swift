import Foundation
import NitroModules

/**
 * Applies a CLUT to an image: **Hald CLUT** (`.png`, square side = L³) or **Iridas/Adobe** **`.cube`** (3D LUT text), full resolution, PNG in cache.
 */
public final class HybridLutProcessor: HybridLutProcessorSpec {
  private let engine: Result<MetalLutRenderer, Error>

  public override init() {
    self.engine = Result { try MetalLutRenderer() }
    super.init()
  }

  public func applyLut(imagePath: String, lutPath: String, intensity: Double) throws -> Promise<String> {
    let amount = Float(min(max(intensity, 0.0), 1.0))
    return Promise<String>.parallel { [self] in
      let renderer: MetalLutRenderer
      switch self.engine {
      case .success(let r):
        renderer = r
      case .failure:
        throw RuntimeError("Failed to create Metal GPU context for LUT")
      }
      do {
        return try renderer.applyLutToFile(
          imagePath: imagePath,
          lutPath: lutPath,
          intensity: amount
        )
      } catch let e as CubeLutError {
        throw RuntimeError(e.description)
      } catch let e as MetalLutError {
        throw RuntimeError(e.description)
      } catch {
        throw RuntimeError(String(describing: error))
      }
    }
  }
}
