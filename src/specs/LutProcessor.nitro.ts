import type { HybridObject } from 'react-native-nitro-modules'

export interface LUTProcessor extends HybridObject<{
  ios: 'swift'
  android: 'kotlin'
}> {
  applyLut(
    imagePath: string,
    lutPath: string,
    intensity: number
  ): Promise<string>
  applyLutToVideo(
    videoPath: string,
    lutPath: string,
    intensity: number
  ): Promise<string>
}
