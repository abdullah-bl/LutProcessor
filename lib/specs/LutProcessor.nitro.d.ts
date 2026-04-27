import type { HybridObject } from 'react-native-nitro-modules';
export interface LutProcessor extends HybridObject<{
    ios: 'swift';
    android: 'kotlin';
}> {
    applyLut(imagePath: string, lutPath: string, intensity: number): Promise<string>;
}
