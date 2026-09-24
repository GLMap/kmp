# glmap-core-kmp

Shared services and types for GLMap Kotlin Multiplatform: initialization, bundled
maps, regional downloads and area downloads. Core does not depend on Compose,
Search, Route or the map renderer.

## Installation

Add the [public Maven repository and native host setup](../README.md#add-a-map-to-your-app),
then add this dependency to `commonMain`:

```kotlin
implementation("globus:glmap-core-kmp:0.1.0-beta.1")
```

Requires Kotlin 2.4.20, Android API 24+ or iOS 16.4+, and native GLMap Core 2.2.0.
On iOS, include the `GLMapCore` SwiftPM product and its resource bundle.

## Initialize and register data

Create `GLMapSdk` with `createGLMapSdk(context)` in Android platform code or
`createGLMapSdk()` on iOS. The following shared helper initializes an instance
and registers a map bundled by your app:

```kotlin
import globus.glmap.core.GLMapSdk

fun initializeOfflineMaps(sdk: GLMapSdk, apiKey: String) {
    sdk.initialize(apiKey)
    sdk.tileDownloading = false
    sdk.addBundledMap("Montenegro.vm")
}
```

The [demo](../example/README.md) bundles this map. In your app, supply your own
map file in Android assets and the iOS app resources, and use its asset name.
Keep `.vm` assets uncompressed on Android. Online services and downloads require
a suitable API key; do not commit it.

## Downloads and shared types

- `regions(parent)` reads the regional catalog; `refreshRegions()` updates it.
- `regionChanges` is a flow of region state/progress changes. Collect it in a
  lifecycle-bound coroutine.
- `downloadRegion`, `cancelRegionDownload` and `deleteRegion` manage regional data.
- `downloadArea(bounds, files, onProgress)` downloads selected `AreaFile` datasets.
  Cancel its coroutine to cancel screen-owned work and handle failures normally.
- `DataSet` selects map, navigation or elevation data. Download the data needed
  for offline search, road routing and terrain separately.
- `GeoPoint`, `GeoBounds`, `Place` and `LocationFix` are shared values. Core's query,
  feature, track and line capabilities connect optional modules without importing
  their implementations.

See the [demo code guide](../example/README.md) for complete download flows.
Native SDK and map-data terms apply in addition to [LICENSE.txt](../LICENSE.txt).
