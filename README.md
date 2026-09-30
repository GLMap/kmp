# GLMap Kotlin Multiplatform

GLMap brings native maps, search and routing to Kotlin Multiplatform apps on
**Android and iOS**. Embed a map with Compose Multiplatform, draw markers and
routes, search for places, and use downloaded data offline. Search and routing
also work without a map view or Compose.

This repository contains four Kotlin modules and a demo catalog with **20 API
examples**.

- [Add a map to your app](#add-a-map-to-your-app)
- [Run the demo](#run-the-demo)
- [Explore the modules](#modules)

## Requirements

- Kotlin 2.4.20 and Java 17; the example uses Android Gradle Plugin 9.1.0.
- Compose Multiplatform 1.12.0 for Map UI. Core, Search and Route do not require it.
- Android API 24 or later; the Android example builds with SDK 37 and NDK 29.
- iOS 16.4 or later, with Xcode on macOS. Kotlin targets are `iosArm64` and
  `iosSimulatorArm64`.
- A suitable GLMap API key and network access for online maps, search, routing
  and downloads. Offline operations need data covering the requested area.

The modules and demo pin the released native **GLMap SDK 2.2.0** from the public
Maven repository and [GLMapSwift](https://github.com/GLMap/GLMapSwift). The Kotlin
wrappers have their own version (`0.1.0-beta.1`); it is not the native SDK version.

### Updating to native SDK 2.2.0

Rebuild the Android app and Kotlin/Native frameworks. For an iOS source build,
run `python3 scripts/fetch-apple-sdk.py` again before rebuilding so cinterop uses
the 2.2.0 headers, and resolve exact SwiftPM version `2.2.0` in the host. Keep
Core, Map, Search and Route on the same native release. Initialize Core before
any native API, including headless Search/Route calls. Vector updates retain all
four `UpdateResult` outcomes; `Ready` means geometry is ready to draw, not that a
frame has been presented.

## Add a map to your app

### 1. Add the Kotlin dependency

Add the public Maven repository to `settings.gradle.kts`, alongside the
repositories your project already uses:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://maven.globus.software/artifactory/libs") }
    }
}
```

In your Compose Multiplatform shared module's `build.gradle.kts`, add Map and the
Compose layout dependency used by the example below:

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("globus:glmap-kmp:0.1.0-beta.1")
            implementation(compose.foundation)
        }
    }
}
```

Map includes Core as a dependency. Add Search or Route only when their APIs are
needed. Use the `-kmp` coordinates for the Kotlin wrappers; the native Android
artifacts have separate coordinates.

### 2. Configure the native host

**Android:** set the minimum SDK to at least 24 and keep map/font assets
uncompressed in your application module:

```kotlin
android {
    defaultConfig { minSdk = 24 }
    androidResources { noCompress += listOf("vm", "ttf", "otf") }
}
```

Enable Compose in the host and use Java 17. See the
[Android example configuration](example/androidApp/build.gradle.kts).

**iOS:** set the deployment target to iOS 16.4 or later. Add
`https://github.com/GLMap/GLMapSwift.git` to your Xcode project with exact version
`2.2.0`. Link the `GLMapCore` product, including its resource bundle, and the
`GLMap` product. Add `GLSearch` or `GLRoute` only when using those modules.

The Kotlin framework link step also needs the matching native XCFramework slice
on its framework search path. Use `ios-arm64` for devices and the arm64 simulator
slice for `iosSimulatorArm64`; do not mix device and simulator frameworks. See
[Apple framework integration](SOURCE.md#apple-framework-integration) for the
public artifact download and Gradle configuration. The SwiftPM products supply
the native frameworks and resources to the final app.

### 3. Initialize the SDK

Create Core in your platform startup code and initialize it before rendering a
map or calling a service. Use your Android application context in `androidMain`:

```kotlin
import android.content.Context
import globus.glmap.core.GLMapSdk
import globus.glmap.core.createGLMapSdk

fun initializeMaps(context: Context, apiKey: String): GLMapSdk =
    createGLMapSdk(context.applicationContext).apply {
        initialize(apiKey)
        tileDownloading = true
    }
```

The equivalent in `iosMain` does not take a context:

```kotlin
import globus.glmap.core.GLMapSdk
import globus.glmap.core.createGLMapSdk

fun initializeMaps(apiKey: String): GLMapSdk = createGLMapSdk().apply {
    initialize(apiKey)
    tileDownloading = true
}
```

Keep initialization in your app's startup flow rather than repeating it during
Compose recomposition. Supply `apiKey` from your app configuration and keep it
out of version control. Keys embedded in an app are not secret from its users.

### 4. Show the map

After initialization, call this composable from your shared UI:

```kotlin
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import globus.glmap.Camera
import globus.glmap.GLMap

@Composable
fun MapScreen() {
    GLMap(
        modifier = Modifier.fillMaxSize(),
        onReady = { controller ->
            controller.setCamera(Camera(42.4341, 19.26, 13.0))
        },
        onTap = {},
        fixture = false,
    )
}
```

`fixture = false` disables the sample track and marker. `onReady` provides a
`MapController` for camera, tap and drawing operations. Call map APIs on the UI
thread. The composable owns the controller and disposes it when removed; do not
reuse it or its drawing handles after removal. Include the applicable map-data
attribution in your UI, including © OpenStreetMap contributors.

For offline display, register a bundled map with `sdk.addBundledMap(...)` or
use Core's downloads. See the [Core guide](glmap-core/README.md).

## Run the demo

Clone the repository:

```sh
git clone https://github.com/GLMap/kmp.git glmap_kmp
cd glmap_kmp
```

Android-only builds resolve Maven dependencies directly and do not need Apple
frameworks. Fetch the Apple artifacts before an iOS build as shown below.

The demo is one shared Compose application in `example/shared/`.
`example/androidApp/` and `example/iosApp/` are thin platform launchers, not separate
implementations of the screens. Both package the same `example/assets/` data.

**Android:** connect an arm64 device or start an arm64 emulator, then install the
example and open **GLMap Demo** from the launcher:

```sh
./gradlew :example:androidApp:installDebug
```

**iOS:** install [XcodeGen](https://github.com/yonaskolb/XcodeGen), fetch the pinned
Apple artifacts, build the shared Kotlin frameworks and generate the Xcode project:

```sh
python3 scripts/fetch-apple-sdk.py
./gradlew :example:shared:linkReleaseFrameworkIosArm64 \
  :example:shared:linkReleaseFrameworkIosSimulatorArm64
python3 scripts/generate-ios-project.py
open example/iosApp/GLMapDemo.xcodeproj
```

Select the **GLMapDemo** scheme and an arm64 simulator or device in Xcode, then
Run. Physical-device installation needs your own signing configuration.

The initial screen is a focused map/lifecycle sample with an API check runner.
Press **Demos** to open the 20-screen catalog. Use **Search** with its offline
option to explore the bundled Montenegro map. Online features and downloads
require a suitable key: enter one with the catalog's **API key** button for the
current session, or create the ignored `example/config/local.json` before building:

```json
{"GLMAP_API_KEY":"your-demo-key"}
```

Build-time keys are embedded in the app; session keys are not persisted. Do not
commit either. The bundled map supports display and search, not offline road
routing or terrain; download navigation/elevation data for those features.

See the [demo code guide](example/README.md) for the source tree, startup flow,
feature screens and lifecycle conventions.

## Modules

Map, Search and Route depend only on Core, not on one another.

| Maven artifact | Kotlin API package | Purpose |
| --- | --- | --- |
| [globus:glmap-core-kmp](glmap-core/README.md) | `globus.glmap.core` | Initialization, shared types, datasets and downloads |
| [globus:glmap-kmp](glmap/README.md) | `globus.glmap` | Compose map, camera, gestures, vectors and drawings |
| [globus:glsearch-kmp](glsearch/README.md) | `globus.glsearch` | Search, autocomplete and map-object queries |
| [globus:glroute-kmp](glroute/README.md) | `globus.glroute` | Road routing, custom routes and tracking |

## Contributing and licensing

See [SOURCE.md](SOURCE.md) for module structure and API development.

The wrapper's terms are in [LICENSE.txt](LICENSE.txt). Native SDK and map-data
terms also apply; bundled map data is © OpenStreetMap contributors.
