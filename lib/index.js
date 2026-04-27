import { NitroModules } from 'react-native-nitro-modules';
export function getLutProcessor() {
    return NitroModules.createHybridObject('LutProcessor');
}
