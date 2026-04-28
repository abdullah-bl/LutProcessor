# LUTProcessor

Native React Native LUT processing powered by Nitro Modules.

`LUTProcessor` applies Hald CLUT PNG files or Iridas/Adobe `.cube` LUT files to images. On iOS it can also export LUT-processed videos.

## Features

- Image LUT processing on iOS and Android.
- Hald CLUT PNG support.
- `.cube` 3D LUT support.
- Intensity blending from `0` to `1`.
- iOS video LUT export to MP4.
- Nitro Modules hybrid object API.

## Platform Support

| Feature | iOS | Android |
| --- | --- | --- |
| Image + Hald PNG LUT | Yes, Metal | Yes, OpenGL ES 3 |
| Image + `.cube` LUT | Yes, Metal | Yes, OpenGL ES 3 |
| Video + Hald PNG LUT | Yes, AVFoundation/CoreImage | Not implemented |
| Video + `.cube` LUT | Yes, AVFoundation/CoreImage | Not implemented |

Android video calls currently reject with a clear unsupported-operation error.

## Installation

Install this package in a React Native app that uses `react-native-nitro-modules`.

```sh
npm install LUTProcessor react-native-nitro-modules
```

After installing or changing native code, refresh native dependencies:

```sh
cd ios && pod install
```

For Android, sync Gradle or rebuild the app.

## Usage

```ts
import { getLUTProcessor } from 'LUTProcessor'

const lut = getLUTProcessor()

const outputImagePath = await lut.applyLut(
  imagePath,
  lutPath,
  0.8
)
```

`imagePath` and `lutPath` can be absolute file paths or `file://` URIs. The returned value is the processed image path in the app cache directory.

## Video Processing

Video processing is available on iOS.

```ts
import { getLUTProcessor } from 'LUTProcessor'

const lut = getLUTProcessor()

const outputVideoPath = await lut.applyLutToVideo(
  videoPath,
  lutPath,
  1
)
```

The output is an MP4 file in the app cache directory.

## API

### `getLUTProcessor()`

Creates the native Nitro hybrid object.

```ts
function getLUTProcessor(): LUTProcessor
```

### `applyLut(imagePath, lutPath, intensity)`

Applies a LUT to an image and returns the output PNG path.

```ts
applyLut(
  imagePath: string,
  lutPath: string,
  intensity: number
): Promise<string>
```

### `applyLutToVideo(videoPath, lutPath, intensity)`

Applies a LUT to a video and returns the output MP4 path.

```ts
applyLutToVideo(
  videoPath: string,
  lutPath: string,
  intensity: number
): Promise<string>
```

`intensity` is clamped between `0` and `1`.

## LUT Formats

### Hald CLUT PNG

Hald LUT images must be square with side length `L^3`, such as `64x64` or `512x512`.

### `.cube`

`.cube` files must include `LUT_3D_SIZE` and the expected `N^3` RGB rows. `DOMAIN_MIN` and `DOMAIN_MAX` are supported.

## Development

Regenerate Nitro bindings after changing `src/specs/LUTProcessor.nitro.ts`:

```sh
npm run specs
```

Run TypeScript checks:

```sh
npm run typecheck
```

The generated `nitrogen/` files are committed and should stay in sync with the Nitro spec.
