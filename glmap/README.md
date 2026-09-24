# glmap-kmp

Native map views for Compose Multiplatform on Android and iOS: camera control,
gestures, vectors, hit testing and map-owned drawings. Map depends on Core and
Compose, not on Search or Route.

## Installation

Follow the [Maven and native host setup](../README.md#add-a-map-to-your-app), then
add to `commonMain`:

```kotlin
implementation("globus:glmap-kmp:0.1.0-beta.1")
```

Requires Kotlin 2.4.20, Compose Multiplatform 1.12.0, Android API 24+ or iOS 16.4+,
and native GLMap 2.2.0. Core is included transitively. On iOS, link the `GLMapCore`
and `GLMap` SwiftPM products, including Core's resources.

## Show a map

Initialize Core first, as shown in the [quick start](../README.md#3-initialize-the-sdk).
Then call the composable from your shared UI:

```kotlin
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import globus.glmap.Camera
import globus.glmap.GLMap

@Composable
fun MyMap(modifier: Modifier) {
    GLMap(
        modifier = modifier,
        onReady = { it.setCamera(Camera(42.4341, 19.26, 13.0)) },
        onTap = {},
        fixture = false,
    )
}
```

Give the map a nonzero size. Set `fixture = false` for your own map rather than
the built-in sample track and marker. Display requires registered/downloaded map
data or online tiles enabled through Core. Include map-data attribution in your UI.

## Ownership and behavior

- Call map APIs on the UI thread. `onReady` provides the `MapController`.
- The composable owns its controller and disposes it on removal. Do not reuse a
  controller, layer or drawable after its map has been removed.
- `captureState().await()` returns a snapshot, not a live camera binding.
- Vector updates return a `Deferred<UpdateResult>`. Cancelling an await does not
  itself cancel native preparation; layer removal and map disposal own cleanup.
- Search's `objectAt` extension is supplied by `globus.glsearch`, not by Map.
- Route geometry enters through Core's `TrackSource` and `LineSource` capabilities.

See the [demo code guide](../example/README.md) for camera, marker, vector, track
and location examples. Native SDK and map-data terms apply in addition to
[LICENSE.txt](../LICENSE.txt).
