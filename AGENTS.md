# Repository guidance

## Scope and structure

This repository provides Kotlin Multiplatform bindings for Android and iOS.
Keep changes focused on the public Kotlin API, platform implementations, examples
and tests.

- Core owns initialization, shared types, datasets and downloads.
- Map, Search and Route depend only on Core, not on one another.
- Only Map depends on Compose. Core, Search and Route must remain usable without
  a map view or Compose initialization.
- Keep API documentation and examples in sync with behavior changes.

## Implementation and checks

- Keep common APIs and Android/iOS implementations consistent. Cinterop definitions
  belong to their owning modules and reuse Core bindings.
- Preserve resource ownership, coroutine cancellation and disposal behavior. Test
  late callbacks, repeated cleanup and concurrent operations.
- Use the native versions recorded in `native-sdk.json`; do not silently change
  versions or add machine-specific paths.
- Keep the demo under `example/`: shared Compose code in `shared/`, thin platform
  hosts in `androidApp/` and `iosApp/`, and common data in `assets/`.
- Run `python3 scripts/check-modules.py`, `python3 tests/example_layout.py`,
  `python3 tests/run.py`, `python3 tests/downloads.py` and
  `python3 scripts/check-vector-api.py`. Re-run Android/iOS
  integration suites for API or platform changes, and API/lifecycle suites whenever
  the native SDK pin changes.
- Report checks actually performed. Distinguish host tests, emulator/simulator
  runs, unsigned archives, signed physical-device runs and authenticated services.
  See [VERIFICATION.md](VERIFICATION.md).

## Documentation and repository hygiene

Write documentation for the published SDK using public Maven and Swift Package
Manager dependencies. Keep temporary publication status and release-preparation
workarounds out of user guides. Do not refer to internal projects, private source
checkouts, workstation paths or development history. Record actual test outcomes
without inferring successful checks from publication status.

Do not commit credentials, native SDK binaries or generated platform builds.
Review shared test logs and remove API keys and machine-specific paths.
Do not publish packages, create remotes or change distribution licenses without
approval.
