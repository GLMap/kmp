# Changelog

## Native SDK 2.3.1 release update

- Pin the published GLMap 2.3.1 native and SwiftPM revisions for all modules and
  the shared Android/iOS demo; build the Android demo with NDK 30.
- Hit-test iOS vector layers through the map state with the tolerance in view
  points, as on Android, instead of the per-object test deprecated in 2.3.0.
- Build the Android user-location circle with `GeometryBuilder` and pass typed
  route lines to line arrows instead of APIs deprecated in 2.3.0.
- Kotlin wrapper versions remain `0.1.0-beta.1`, independent of the native SDK.

## Native SDK 2.2.0 release update

- Pin the published GLMap 2.2.0 native and SwiftPM revisions for all modules and
  the shared Android/iOS demo.
- Fix Apple SDK downloads from the release CDN while retaining SHA-256 checks.
- Allow Android Gradle configuration without pre-downloaded Apple frameworks.
- Document matching cinterop/SwiftPM artifacts and native rebuild requirements;
  check dependency pins against `native-sdk.json`.
- Kotlin wrapper versions remain `0.1.0-beta.1`, independent of the native SDK.
