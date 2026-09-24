# Standalone extraction verification — 2026-09-24

Native baseline: **b5ed76b9b68d5651f1eb78f317dcc6fcc93cceb3**, current GLMap `dev`.
Local native version: `2.2.0-dev.b5ed76b9b`. All four native ELF IDs/Apple UUIDs are
verified in `tests/results/artifacts.json`.

| Check | Actual result |
| --- | --- |
| Android Release/R8 APK | Pass |
| iOS arm64 **device** Kotlin framework | Pass |
| iOS arm64 simulator Kotlin framework | Pass |
| iOS simulator host | Pass |
| iOS device archive, no signing | Pass |
| Android emulator API suite | **8/8** |
| iPhone 17 / iOS 27 simulator API suite | **8/8** |
| Common drawable lifetime regression | Pass |
| Controlled Android/iOS download lifecycle | Pass |
| All KMP publications to a local build-directory Maven repository | Pass |
| Android consumer resolving published module metadata/AAR | Pass |

The API suite now exercises late drawable mutations after removal and map disposal,
in addition to cameras, vector outcomes, input copying, two maps and recreation.
Final API runs use the split library/example modules, not the original monolithic
lab module. Test reports are retained under `tests/results/`.

`shared/` is the publishable library; `example/` owns the catalog, test runner and
benchmarks. The produced Android library AAR is checked not to contain those sample
classes. Cinterop commonization is enabled so shared `iosMain` publication metadata
contains its Objective-C bindings; concrete framework builds alone did not catch
that publication requirement. Compose/AndroidX metadata emits existing duplicate
KLIB-name warnings, without failing these builds.

The verification repository is **only** `build/maven/`, not Maven Central, the vendor
Maven server or the user's global Maven cache. Logs remain in `build/verification/`.

## Remaining release gates

- Native 2.2.0 publication and verification of `fetch-apple-sdk.py` against that tag.
- Clean published iOS consumer resolution/linking, beyond the local example host.
- Signed device deployment and physical-device gesture/memory/performance checks.
- Full KMP service-catalog E2E and authenticated download/offline-relaunch checks.
- Public coordinate/version/license approval and remote publication.

Only arm64 KMP Apple targets are supported. The example selects the matching device
or simulator framework; it no longer hardcodes a simulator binary for device builds.
No keys, native SDK binaries or generated Xcode projects are committed.
