# GLMap Kotlin demo code guide

The demo is **one Compose Multiplatform application** with **20 API examples**
for Android and iOS. Its screens and SDK logic live in `shared/`; `androidApp/`
and `iosApp/` only provide the platform entry points, resources and build settings.
For installation, launch commands and API keys, see
[Run the demo](../README.md#run-the-demo).

## Native SDK release

Both hosts use the published GLMap **2.2.0** artifacts. Android resolves them
through the shared modules' Maven dependencies; the iOS host pins exact SwiftPM
version `2.2.0`. Run `python3 scripts/fetch-apple-sdk.py` from the repository root
before rebuilding the Kotlin/Native frameworks, then regenerate the host with
`python3 scripts/generate-ios-project.py`. The fetched headers and the frameworks
linked by Xcode must be from the same release. See
[VERIFICATION.md](../VERIFICATION.md) for API and lifecycle checks.

## Directory structure

```text
example/
├── shared/                          # The single shared demo implementation
│   ├── src/commonMain/kotlin/…/
│   │   ├── DemoApp.kt                # Startup, lifecycle sample and navigation
│   │   ├── SdkFactory.kt             # Example-only rememberSdk declaration
│   │   ├── ExampleFixture.kt         # Shared camera and geometry
│   │   ├── ApiChecks.kt              # API/lifecycle scenarios
│   │   ├── Benchmark.kt              # Transport benchmark
│   │   ├── Reports.kt                # Test-result output interface
│   │   └── demo/
│   │       ├── Catalog.kt            # Catalog, screen layout and errors
│   │       ├── MapScreens.kt         # Map display and camera
│   │       ├── DrawScreens.kt        # Drawings, vectors and location
│   │       ├── SearchScreens.kt      # Search and POI picking
│   │       ├── RoutingScreens.kt     # Road routes and navigation tracking
│   │       └── OfflineScreens.kt     # Regional and bounding-box downloads
│   ├── src/androidMain/kotlin/…/     # Android SDK factory and report output
│   ├── src/iosMain/kotlin/…/         # iOS factory, view controller and reports
│   └── build.gradle.kts             # Shared module and GLMapDemoShared framework
├── androidApp/                      # Activity, manifest and Android UI tests
├── iosApp/                          # SwiftUI host and GLMapDemo project spec
│   └── tests/                       # GLMapDemoUITests project and UI tests
├── assets/                          # Datasets and drawings used by both hosts
└── README.md
```

Shared Kotlin sources use `software.globus.glmap.example`; these are example
sources, not public `globus.*` SDK packages. The Gradle projects are
`:example:shared` and `:example:androidApp`. Both native apps use the identifier
`software.globus.glmap.demo` and display name **GLMap Demo**.

The Android activity calls the shared `DemoApp` composable. The SwiftUI host
embeds `MainViewController` from `GLMapDemoShared.framework`, which calls that
same composable. Adding a screen does not require implementing it twice.

## Startup and lifecycle

1. Both hosts enter
   [DemoApp.kt](shared/src/commonMain/kotlin/software/globus/glmap/example/DemoApp.kt),
   which opens the focused map/lifecycle sample first. Press **Demos** to open
   the catalog; **Checks** returns to the lifecycle sample.
2. [Catalog.kt](shared/src/commonMain/kotlin/software/globus/glmap/example/demo/Catalog.kt)
   creates Core through the example's `rememberSdk` helper and calls
   `sdk.initialize(sessionKey)` before enabling the catalog.
3. The `demos` list groups `Demo` entries and selects a composable screen. Keys
   may come from the host build configuration or the session-only key dialog.
4. Most map screens use `DemoScaffold`, which hosts a `GLMap` with
   `fixture = false`, updates tile-downloading policy and forwards `onReady`.
5. Screens store their controller and handles in Compose state. `LaunchedEffect`
   scopes asynchronous work; `DisposableEffect` releases screen-owned resources.
   The map composable disposes its controller when removed.

`attempt` reports operation failures but rethrows coroutine cancellation. Do not
reuse controllers after disposal. Close retained search results, routes and
trackers, and stop location/flow collection when leaving a screen. Trackers and
route-derived drawings must not outlive their route.

`rememberSdk` is an example helper, not a dependency of Core, Search or Route.
Headless applications create Core directly with the platform factory.

## Where to find each feature

Paths below are relative to the shared `demo/` source directory.

| Source | Catalog screens |
| --- | --- |
| `MapScreens.kt` | Online Map, Dark Theme, 3D Terrain, Fly To, Zoom to BBox |
| `DrawScreens.kt` | Image, Image Group, Markers & Clustering, Balloon, Track Arrows, User Location, Lines & Polygons, GeoJSON, GPS Track |
| `SearchScreens.kt` | Search, POI Tap |
| `RoutingScreens.kt` | Route Building, Turn-by-Turn Navigation |
| `OfflineScreens.kt` | Download Maps, Download BBox |

- **Search** registers the bundled Montenegro map and supports online/offline
  queries, autocomplete, cancellation, markers and result selection.
- **POI Tap** uses Search's `objectAt` extension on Core's map-query capability.
- **Route Building** selects endpoints on the map and supports car, bicycle and
  pedestrian routing. Offline road routing needs navigation data.
- **Turn-by-Turn Navigation** demonstrates route tracking, foreground location
  and a custom sample route. It is not a complete navigation app: there is no
  voice-guidance or background-navigation service.
- **Download Maps / Download BBox** show progress, cancellation and data management.
  Map, navigation and elevation are separate datasets.
- **GeoJSON** loads the postcode fixture and uses native vector picking.

## Assets and configuration

[assets/](assets/) contains `Montenegro.vm` for offline map display and search,
not navigation or elevation data. `valhalla.json` supplies offline-routing
configuration; keep it compatible with the native SDK. Other assets provide SVGs,
markers and GeoJSON. Bundled map data is © OpenStreetMap contributors.

Android packages these assets through its application source set; iOS copies
them into the app resources. Both hosts read the optional key from the ignored
`example/config/local.json` (relative to the repository root), or `GLMAP_API_KEY`.
Build-time keys are embedded in the app, while keys entered in the catalog are
not persisted. Do not commit keys or authenticated logs.

Run Gradle commands from the repository root. The generated iOS project is
`example/iosApp/GLMapDemo.xcodeproj`; generated projects and builds are ignored.

## Add or change an example

1. Add the composable to the matching shared `demo/*Screens.kt` file.
2. Add a `Demo` entry in `Catalog.kt`; use `DemoScaffold` for standard map screens.
3. Keep public API calls in the screen and add any required assets under `assets/`.
4. Cancel screen-owned work and release retained resources during cleanup.
5. Update the catalog count, this guide and relevant tests.

API, lifecycle, native input and headless checks are documented in
[VERIFICATION.md](../VERIFICATION.md). A host-only regression test is not a
substitute for exercising the native app on each platform.
