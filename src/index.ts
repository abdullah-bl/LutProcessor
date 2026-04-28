import { NitroModules } from 'react-native-nitro-modules'
import type { LUTProcessor } from './specs/LUTProcessor.nitro'

export type { LUTProcessor } from './specs/LUTProcessor.nitro'

export function getLUTProcessor(): LUTProcessor {
  return NitroModules.createHybridObject<LUTProcessor>('LUTProcessor')
}
