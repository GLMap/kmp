# glroute-kmp

Road routing, custom-route construction, maneuvers and route tracking for Kotlin
Multiplatform on Android and iOS. Route depends only on Core, not on Compose or
the map renderer.

## Installation

Follow the [Maven and native host setup](../README.md#add-a-map-to-your-app), then
add to `commonMain`:

```kotlin
implementation("globus:glroute-kmp:0.1.0-beta.1")
```

Requires Kotlin 2.4.20, Android API 24+ or iOS 16.4+, and native GLRoute 2.2.0.
On iOS, link `GLMapCore` with its resources and the `GLRoute` SwiftPM product.

## Build a road route

Initialize Core, then call the suspending extension from a lifecycle-bound
coroutine. This example uses online routing:

```kotlin
import globus.glmap.core.GLMapSdk
import globus.glmap.core.GeoPoint
import globus.glroute.RouteQuery
import globus.glroute.route

suspend fun routeLength(sdk: GLMapSdk): Double {
    val route = sdk.route(RouteQuery(
        start = GeoPoint(42.43, 19.25),
        end = GeoPoint(42.44, 19.27),
        offline = false,
    ))
    try {
        return route.length
    } finally {
        route.close()
    }
}
```

Online routing requires a suitable API key and network access. `RouteMode` selects
`Auto`, `Bicycle` or `Pedestrian`. Offline routing requires navigation data for the
area and a compatible `valhalla.json` in your application's native resources.

## Custom routes and lifetime

- `sdk.buildRoute(steps)` constructs a route from supplied geometry without a road
  routing request. `RouteStep.lonLat` contains longitude/latitude pairs.
- A `Route` owns native resources until `close()`. Close it when finished,
  including on screen disposal or replacement.
- `route.tracker()` creates a `RouteTracker`; feed it `LocationFix` values through
  `update()` to get maneuver, distance, duration and progress information.
- Close trackers and remove route-derived drawings before closing their route.
- Routes implement Core's `TrackSource`; maneuvers implement `LineSource`, so an
  optional Map module can draw native geometry without a Route dependency.

This package does not provide voice guidance or a background navigation service.
See the [demo code guide](../example/README.md) for road routes, custom routes and
tracking. Native SDK and map-data terms apply in addition to
[LICENSE.txt](../LICENSE.txt).
