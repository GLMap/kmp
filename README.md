# GLMap for Kotlin Multiplatform / Compose

Shared Kotlin map/service API backed by the existing Android and Objective-C SDKs.
Targets: Android API 24+, iOS 16.4+ **device arm64 and simulator arm64**.
This is a standalone local beta-preparation repository; nothing is published.
Native baseline: `native-sdk.json`; released GLMap 2.1.0 is not compatible.

## Build

Kotlin 2.4.20, Compose 1.12.0, AGP 9.1.0, Gradle 9.3.1, Java 17 and Xcode.

```sh
python3 scripts/prepare-native-sdk.py --sdk-root /path/to/glmap --output /path/to/prepared-sdk
export GLMAP_SDK_DIR=/path/to/prepared-sdk
./gradlew :androidApp:assembleRelease \
  :example:linkReleaseFrameworkIosArm64 :example:linkReleaseFrameworkIosSimulatorArm64
python3 scripts/generate-ios-project.py
xcodebuild -project iosApp/GLMapKmpLab.xcodeproj -scheme GLMapKmpLab \
  -configuration Release -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO build
```

Local artifacts include the new Android Core dependency and both Apple platforms.
There are no required sibling directories. Source, versions and checksums are recorded
in the prepared SDK's manifest. No keys or native binaries are committed here.

After native 2.2.0 publication, unset `GLMAP_SDK_DIR` and run
`python3 scripts/fetch-apple-sdk.py`: it reads the tagged public SwiftPM manifest,
verifies archive SHA-256 values and extracts cinterop headers/binaries into ignored
`.local-sdk/ios`. The iOS host links the same exact SwiftPM release. Maven resolves
2.2.0 directly. The unpublished version deliberately does not fall back to 2.1.0.

The example Xcode project is generated, not committed. Device and simulator builds
select their matching Kotlin framework. To install on a phone, supply your own Apple
signing configuration; the commands above validate compilation without signing.

The `shared/` module is the publishable library (`software.globus:glmap-kmp`).
The `example/` module owns the catalog, API checks and benchmarks; none of these
are included in the library artifact. `androidApp/` and `iosApp/` are its hosts.

## API and ownership

Call SDK initialization before native operations. Android's central initializer
loads all included modules and reports initialization failures. Map queries use
one retained `GLMapViewState` per operation and close it afterward.

Use `captureState().await()` for coherent current-camera values and `moveCamera`
for partial changes. Angles are degrees; packed geometry is `[longitude, latitude]`.
Vector results reflect native preparation, not a presented frame.

Map composables own controllers. Every drawable mutation checks its lifetime:
removed handles reject with `object_removed`, disposed maps with `map_disposed`.
Repeated removal is harmless. Services are cancellable suspend calls; routes and
search results have explicit ownership. Calls that touch maps belong on the UI thread.

The included examples cover maps, drawings, search, routing and downloads. Foreground
location/replay is not a background navigation service. Bundled example data belongs
to this repository, not an external lab checkout.

## Verification

`python3 tests/run.py` checks the production common drawable lifetime.
The example's native API suite also checks stale drawable mutations across removal
and map recreation. Android Release builds use R8. See `VERIFICATION.md` for actual
results and release gates; `SOURCE.md` records source provenance.
