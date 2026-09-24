# Globus Kotlin Multiplatform modules

Planned repository: `GLMap/kmp`. Public Maven coordinates:

- `globus:glmap-core-kmp`
- `globus:glmap-kmp`
- `globus:glsearch-kmp`
- `globus:glroute-kmp`

Map, Search and Route depend only on Core. **Only Map depends on Compose.** Core,
Search and Route work in headless applications on Android and iOS.

The Gradle project identities include `-kmp`, not only their published artifact IDs:
otherwise `globus:glmap` conflicted with the native Android SDK and could replace
wrapper runtime classes during resolution. Android resource namespaces are also distinct.

Source directories are `glmap-core/`, `glmap/`, `glsearch/`, `glroute/`. Kotlin API
packages follow `globus.glmap.core`, `globus.glmap`, `globus.glsearch`, `globus.glroute`.
Search and routing are extensions on the Core `GLMapSdk`, imported from their modules.
Map consumes Core query/feature/track/line capabilities, never a service implementation.

## Use

```kotlin
commonMain.dependencies {
    implementation("globus:glmap-kmp:0.1.0-beta.1")
    implementation("globus:glsearch-kmp:0.1.0-beta.1")
}
```

Create Core with `createGLMapSdk(context)` on Android or `createGLMapSdk()` on iOS,
then call `initialize` before native operations. The iOS host must select the
`GLMapCore` SwiftPM product, including its resource bundle. Map UI calls belong on
the UI thread. Headless services have no Compose initialization requirement.

The example owns its `rememberSdk` Compose convenience, catalog, benchmarks and
verification runner; none of these are shipped in the headless modules.

## Build and verify

Kotlin 2.4.20, Compose 1.12.0, AGP 9.1.0, Java 17, Android API 24+, iOS 16.4+.
Both device arm64 and simulator arm64 are supported.

```sh
python3 scripts/prepare-native-sdk.py --sdk-root /path/to/glmap --output /path/to/sdk
export GLMAP_SDK_DIR=/path/to/sdk
./gradlew :androidApp:assembleRelease \
  :example:linkReleaseFrameworkIosArm64 :example:linkReleaseFrameworkIosSimulatorArm64
python3 scripts/generate-ios-project.py
python3 scripts/test-headless.py --simulator <uuid>
```

The headless runner builds and launches Core/Search/Route separately, rejects a loaded
renderer and checks the packaged libraries. It needs an Android emulator and iOS
simulator, but no key. `python3 tests/run.py` and `python3 tests/downloads.py` retain
the ownership/download regressions. See `VERIFICATION.md` for actual evidence.

Local publication tests write only to `build/maven/`. No remote repository is
configured. After native 2.2.0 publication, `scripts/fetch-apple-sdk.py` verifies its
SwiftPM archive checksums for cinterop; until then use the explicit local override.
Native pins, including the frozen Swift package, are in `native-sdk.json`.
