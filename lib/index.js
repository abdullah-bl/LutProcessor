import { NitroModules } from 'react-native-nitro-modules';
export function getLUTProcessor() {
    return NitroModules.createHybridObject('LUTProcessor');
}
