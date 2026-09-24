# Source guide

## Module layout

| Directory | Gradle project | Responsibility |
| --- | --- | --- |
| `glmap-core/` | `:glmap-core-kmp` | Initialization, shared types, datasets and downloads |
| `glmap/` | `:glmap-kmp` | Compose map, camera, gestures, vectors and drawings |
| `glsearch/` | `:glsearch-kmp` | Search requests and map-object queries |
| `glroute/` | `:glroute-kmp` | Road routing, custom routes and navigation state |
| `example/shared/` | `:example:shared` | Shared Compose demo UI and API/lifecycle checks |
| `example/androidApp/` | `:example:androidApp` | Thin Android demo host |
| `example/iosApp/` | — | Thin SwiftUI host, XcodeGen specification and UI tests |
| `example/assets/` | — | Demo datasets and drawings shared by both hosts |
| `headless-probe/`, `headless-app/` | Matching project names | Independent Core/Search/Route test apps |

Public APIs live in each module's `src/commonMain/kotlin/`; platform implementations
live in `src/androidMain/kotlin/` and `src/iosMain/kotlin/`. Cinterop definitions
are in `src/nativeInterop/cinterop/`. See the [demo code guide](example/README.md)
for the shared UI layout.

Native dependency versions are recorded in [native-sdk.json](native-sdk.json).
See the [requirements](README.md#requirements). Native SDK source and binaries are
not part of the Kotlin workspace.

## Boundaries and resource lifetime

Core is the only shared module dependency. Map alone uses Compose. Search and
Route are extensions on Core's `GLMapSdk` and must work without a renderer.

- Core query, feature, track and line capabilities connect optional modules
  without depending on their implementations.
- Map controllers, layers and drawing handles belong to one composable map.
  Removal disposes the controller and settles pending operations.
- Cancelling an await on a map update does not itself cancel native preparation;
  layer removal and map disposal own that cleanup.
- Service suspending functions propagate coroutine cancellation. Preserve
  protection against late callbacks and replacement requests.
- Close `SearchResults`, routes, trackers and retained Core resources explicitly.
  Trackers and drawings created from a route must not outlive that route.
- Keep Gradle project identities and publication coordinates suffixed with `-kmp`;
  Android namespaces must also remain distinct from the native SDK's namespaces.

## Apple framework integration

Kotlin cinterop uses the public native XCFramework headers. From this checkout,
fetch the pinned release artifacts:

```sh
python3 scripts/fetch-apple-sdk.py
```

The script reads the public GLMapSwift release manifest, verifies the archive
SHA-256 checksums and arm64 device/simulator slices, and extracts them under the
ignored `.local-sdk/ios/` directory. No native source checkout is needed.

Each wrapper module uses its own cinterop definition and reuses Core bindings.
An app linking the Kotlin modules must make the native frameworks visible to the
Kotlin/Native linker as well as to Xcode. In the app's Kotlin framework
configuration, add `-F` for the matching slice directory and `-framework` for Core
and each selected feature. For example, a device Map build uses
`GLMapCore.xcframework/ios-arm64` and `GLMap.xcframework/ios-arm64` under the downloaded
artifact directory. Simulator builds use the simulator slices instead.

The [shared demo Gradle configuration](example/shared/build.gradle.kts) selects
the correct slices for both targets. Adapt that block to your module and artifact
directory; it uses all four native frameworks because the demo uses all four modules.
The [XcodeGen specification](example/iosApp/project.yml) links public SwiftPM products for
the final app, including native frameworks and resource bundles. Keep those
products at the same version as the Kotlin compile/link inputs.

## Change an API

1. Update the owning module's common API and both platform implementations.
2. If needed, update its cinterop definition without duplicating Core types.
3. Update the module README and a demo using the public API.
4. Add tests for ownership, cancellation, repeated cleanup and concurrent calls.
5. Run the checks in [VERIFICATION.md](VERIFICATION.md), including the relevant
   Android/iOS suites. Re-run API and lifecycle checks on both platforms whenever
   the native dependency changes.

Commit Kotlin source and cinterop changes together. Keep generated frameworks,
Xcode projects, native SDK binaries and credentials out of version control.
