# Kotlin Multiplatform SDK verification

## Host checks

Use the toolchain in [README.md](README.md#requirements). From the repository root:

```sh
python3 scripts/check-modules.py
python3 tests/example_layout.py
python3 tests/fetch_apple_sdk.py
python3 tests/run.py
python3 tests/downloads.py
python3 scripts/check-vector-api.py
```

The module check enforces Core-only dependencies and excludes Compose from
Core/Search/Route. The controlled tests compile current Kotlin production code
against small platform doubles. They use Kotlin 2.4.20 and coroutines 1.10.2 from
the Gradle cache; resolve the project's dependencies first on a fresh machine.

`tests/example_layout.py` checks the shared entry points, Gradle module paths,
platform identifiers, shared assets and documented build commands without a device.
`tests/fetch_apple_sdk.py` checks release-archive extraction: iOS slice selection,
symlinked temporary roots, rejection of traversal/iOS symlinks, missing slices
and downloader request headers. `scripts/check-modules.py` also checks all native
version pins, including the demo's SwiftPM requirement.
`tests/run.py` covers drawable ownership, removal, disposal and repeated cleanup.
The [download regressions](tests/downloads/README.md) cover cancellation, late
callbacks, file ownership and retry. These are host tests, not emulator/simulator
runs or authenticated download tests.

## Native API and lifecycle checks

Build and launch the [demo](README.md#run-the-demo) on each platform. The initial
screen runs the API checks; **Run tests** repeats them. Keep a fresh result for
each run rather than reusing a result from an older installation.

The suite in `example/` covers camera/state operations, vector updates, drawable
ownership, removal/recreation, concurrent work and cross-module geometry/query
handoff. The native input suites add gestures, keyboard interaction, navigation,
rotation and background/resume cycles.

For Android, build a Release/R8 app as well as running the Debug input suite on a
connected arm64 target:

```sh
./gradlew :example:androidApp:assembleRelease \
  :example:androidApp:connectedDebugAndroidTest
```

Install and launch the Release app separately to exercise the optimized API path;
a successful build alone is not a runtime result. The example Release build uses
the debug signing configuration, not distribution signing.

For iOS, build the Kotlin frameworks and Xcode host as described in the README,
install the app, then run the input suite on that simulator:

```sh
xcodegen generate --spec example/iosApp/tests/project.yml
xcodebuild -project example/iosApp/tests/GLMapDemoUITests.xcodeproj \
  -scheme GLMapDemoUITests \
  -destination 'platform=iOS Simulator,id=<simulator-uuid>' \
  -derivedDataPath build/ios-ui-tests CODE_SIGNING_ALLOWED=NO test
```

Replace the placeholder with the target simulator UUID. Native input tests use the
focused startup sample, not a catalog screen. Record device/simulator type, OS,
build configuration and command with every result.

## Headless module isolation

Build Core, Search and Route separately. None should include Compose or the map
renderer. Android probes can be selected through `-Pprobe`:

```sh
./gradlew -Pprobe=core :headless-app:installRelease
./gradlew -Pprobe=search :headless-app:installRelease
./gradlew -Pprobe=route :headless-app:installRelease
```

Open each installed probe and check its result. For iOS, use the same property
with `:headless-probe:linkReleaseFrameworkIosSimulatorArm64`, then integrate the
probe framework and `headless-probe/iosApp/App.swift` into a simulator host with
the matching public SwiftPM products. Core needs only `GLMapCore`; Search and
Route add only their selected product. The Search probe also needs the bundled
Montenegro map. Follow the [Apple integration guide](SOURCE.md#apple-framework-integration).

Inspect APK libraries and app frameworks in addition to the runtime result.
Absence of a map view in source is not proof that the renderer was excluded.

## Published GLMap 2.2.0 validation — 2026-09-29

The native release is pinned to `36a343f9275d76734466ecae1f39b9c0e0655a8b` and
SwiftPM tag `2.2.0` to `b07267c4bdd7cfcde5e001708c95d897eaa2f19a`. These checks
used public Maven/SwiftPM artifacts with `GLMAP_SDK_DIR` unset. See
[release-2.2.0.json](tests/results/release-2.2.0.json) for the run summary.

- Module/native-pin, vector-call-site, ownership and controlled download checks
  passed. Shared-layout tests passed **6/6**, and downloader regressions **6/6**.
- `python3 scripts/fetch-apple-sdk.py` downloaded all four public archives,
  verified their release-manifest SHA-256 values and extracted iOS slices.
- Android Debug, instrumentation and Release/R8 builds passed. Android Gradle
  configuration also worked before the Apple SDK download was available.
- Android 17 arm64 emulator: Debug catalog/Checks navigation passed (**1/1**).
  The separately installed Release/R8 app produced a fresh **9/9** API/lifecycle
  report, including supersession, cancellation on removal and ten remounts.
- Kotlin Release simulator framework, XcodeGen host generation and iOS Release
  simulator app built successfully. On iPhone 17 / iOS 27.0 arm64, API/lifecycle
  checks passed **9/9** and catalog/Checks navigation passed **1/1**.
- Packaged Android ELF build IDs and iOS simulator UUIDs matched public 2.2.0
  artifacts. Android `world.vm` remained uncompressed; published vector
  headers/classes expose the required status-bearing completion API.

The first public Apple download exposed default-user-agent HTTP 403, symlinked
macOS temporary paths and non-iOS framework symlinks in the release archives.
The downloader now identifies itself, resolves its temporary root and extracts
only the required iOS slices without weakening checksum/path validation. Host
Kotlin tests initially needed Gradle to populate their compiler cache; final
runs passed after dependency resolution.

These are workspace builds and emulator/simulator runs. Device frameworks/apps,
physical devices, full gesture suites, headless runtime, authenticated services,
benchmarks and offline restoration were not revalidated. Existing toolchain
warnings remain. Earlier results below retain their original dev SDK scope;
references to matching pins describe the pins at the time of those runs.

## Recorded results

The verification summary dated **2026-09-24** and the saved
[evidence](tests/results/README.md) record:

| Check | Recorded outcome |
| --- | --- |
| Android Release/R8 API suite | 9/9 checks passed |
| iOS API suite | 9/9 checks passed |
| Headless Core/Search/Route | All six Android/iOS variants passed; runtime and packaged-library checks excluded the renderer |
| Route dependency inspection | No Compose or Map SDK dependency |
| Controlled ownership/download tests | Passed |
| Android Release build | Built with R8 |
| Kotlin iOS frameworks | Device arm64 and simulator arm64 built |
| iOS device archive | Built without signing; not a signed installation or device test |
| Maven packaging check | Platform variants and common metadata generated in the local verification repository |

Retained results describe specific runs, not every device or service combination.
They do not establish physical-device, authenticated service-catalog or fresh-process
offline-restoration coverage. The earlier build summary records non-fatal warnings
about Compose/AndroidX metadata and expect/actual classes.

### Shared demo layout validation

The `example/shared`, `example/androidApp` and `example/iosApp` layout and the
`GLMapDemo` application were checked on **2026-09-24**. The
[run summary](tests/results/demo-layout.json) records the tested source fingerprint,
toolchain, commands, target types and outcomes.

| Check | Outcome |
| --- | --- |
| Host module boundaries and controlled lifetime/download regressions | Passed |
| Shared entry points, build paths, identifiers and asset layout | 6/6 host checks passed |
| Android Debug, instrumented-test and Release/R8 builds | Passed |
| Android Release/R8 API suite | 9/9 on an arm64 Android 17 emulator |
| Android Debug UI suite | 4/4 on the emulator, including catalog/Checks navigation |
| Shared Kotlin Release frameworks | Both `iosArm64` and `iosSimulatorArm64` built |
| iOS Release app and API suite | Built; 9/9 on an arm64 iOS 27.0 simulator |
| iOS UI suite | 8/8 on the simulator, including catalog/Checks navigation |
| iOS device Release app | Built without signing; not installed or run on a physical device |
| Headless Core/Search/Route | All six variants passed on the Android 17 emulator and iOS 27.1 simulator; no renderer in runtime/package checks |
| Native artifact identity and resources | Android ELF build IDs, iOS simulator/device framework UUIDs and Core resources matched the selected SDK |
| Public XcodeGen configuration | Generated successfully without a native SDK override |
| README snippets | Six common/Android snippets and one iOS initializer compiled against local module artifacts |
| Documentation and moved assets | Relative links/anchors, fences, JSON/shell syntax and whitespace passed; asset contents were unchanged |

Native builds and runs used prebuilt `2.2.0-dev.515481f9f` artifacts through an
explicit local override. Their native and Swift package revisions matched
`native-sdk.json`; the dependency pins were not changed. The public Apple release
manifest request returned HTTP 404, so these runs do **not** establish clean public
Maven/SwiftPM release resolution. Project generation alone does not resolve or
validate its native dependencies.

Builds retained non-fatal expect/actual, interop opt-in and unchecked-cast warnings,
a simulator ICU deployment-target warning, and an unsigned device-build orientation
warning. The controlled download double emitted a redundant-cast warning.
There were no signed physical-device, authenticated-service or fresh-process
offline-restoration runs. Runtime checks on these OS versions do not establish
coverage of older Android/iOS releases.

### Unified native vector completions

The status-bearing `setVectorObject(s)` API was validated on **2026-09-25** with
native SDK `2.2.0-dev.05553b111`. See [vector-status.json](tests/results/vector-status.json)
for source/artifact provenance and target types. Android now calls
`setVectorObjects(..., UpdateCompletion)` and Kotlin/Native uses `completion =`.
The existing `Deferred<UpdateResult>` continues to preserve Ready, Superseded,
Cancelled and Failed; Ready does not certify presentation of a rendered frame.

- Android Debug, instrumented-test and Release/R8 builds passed.
- Kotlin Release frameworks built for device arm64 and simulator arm64; the iOS
  Release simulator host also built against the selected SDK.
- Android 14 arm64 emulator, Release/R8: **9/9** API/lifecycle scenarios passed.
- iPhone 17 / iOS 27.0 arm64 simulator, Release: **9/9** API/lifecycle scenarios and
  the catalog/Checks native input smoke test (**1/1**) passed.
- Host ownership/download regressions, six layout tests, module checks and
  packaged Android/Apple vector API checks passed.
- Android/iOS native IDs and Core resources matched the newly built SDK.

The SDK's pre-existing working-tree delta is identified by manifest hash in the
result; no native source was edited. Pins were intentionally updated in all three
bindings. These local builds do not establish public release resolution, signed
physical-device or authenticated-service coverage. Full gesture, benchmark and
headless runtime suites were not re-run for this migration.

## Release validation and reporting

For each SDK release, check public dependency resolution and package metadata,
then run API, lifecycle and isolation tests against the selected release artifacts.
Test signed physical-device installation separately from simulator execution.
Test authenticated services and fresh-process offline restoration separately from
bundled-data and controlled-callback tests.

Record the source revision, artifact identities, command, toolchain, target type,
outcome and limitations. Remove keys and machine-specific paths from shared logs.
Do not infer runtime coverage from a successful build or package publication.
