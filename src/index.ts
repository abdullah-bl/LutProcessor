import { NitroModules } from 'react-native-nitro-modules'
import type { LutProcessor } from './specs/LutProcessor.nitro'

export type { LutProcessor } from './specs/LutProcessor.nitro'

export function getLutProcessor(): LutProcessor {
  return NitroModules.createHybridObject<LutProcessor>('LutProcessor')
}
