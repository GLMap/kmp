# Modular KMP verification — 2026-09-24

Native baseline: `515481f9f`; frozen Swift package: `04f1a99`.

Completed:

- Four independently published module variants under `globus:*‑kmp`, with Core-only
  inter-product dependencies. Core/Search/Route do **not** depend on Compose.
- Android Release/R8 builds; iOS device and simulator Kotlin frameworks build.
- Combined API suite adds a ninth scenario exercising Search results retained by Map,
  Core-state POI queries, Route track data and maneuver line handoff. Fresh final
  platform results are retained under `tests/results/`.
- `scripts/test-headless.py` builds and runs Core, Search and Route independently on
  both platforms. All **six variants pass**; native renderer absence is checked both
  at runtime and in packaged artifacts.
- Route runtime dependency resolution contains neither Compose nor the Map SDK.
- Ownership and controlled download regressions pass.
- All twelve Android/iOS platform publications plus common metadata are generated in
  the local `build/maven/` verification repository; no remote publishing occurs.
- An unsigned modular device archive succeeds.

Runtime testing caught a distinction between Gradle project identity and publication
artifact ID: projects also need the `-kmp` identity, or conflict resolution can select
native `globus:glmap` and omit the wrapper classes. Android resource namespaces are
also separate from native SDK namespaces. A stale result file was explicitly rejected;
final Android results are from a fresh Release/R8 launch after correcting this.

Swift cinterop declarations are split per framework and reuse Core bindings. Published
shared iOS metadata uses cinterop commonization. `example/` owns Compose conveniences,
catalogs and benchmarks, not the service libraries.

Remaining: clean remote resolution after native release, physical/signed device runs,
full service-catalog and authenticated offline-relaunch tests, publishing/license
approval. Toolchain warnings about duplicate Compose/AndroidX metadata and expect/actual
classes are retained; they did not fail these checks.
