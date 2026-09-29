# Verification evidence

See [VERIFICATION.md](../../VERIFICATION.md) for commands, recorded outcomes and
test coverage. Native dependency versions are in
[native-sdk.json](../../native-sdk.json).

## Contents

- [release-2.2.0.json](release-2.2.0.json): public GLMap 2.2.0 dependency resolution,
  archive extraction, artifact identities and Android/iOS API/lifecycle validation.
- [vector-status.json](vector-status.json): earlier dev SDK vector completion API,
  source identities and Android/iOS API/lifecycle validation.

- [demo-layout.json](demo-layout.json): shared-demo layout, build, API, UI and
  headless results, with source fingerprint, toolchain and explicit target types.
- `android-api.json` and `ios-api.json`: earlier combined API/lifecycle results.
- `headless.json`: independent Core/Search/Route runtime and library checks.
- `artifacts.json` and `source-sha256.json`: saved artifact/source identities.

Each record describes its own tested build. Regenerate artifact identities when
recording a new build; a framework version string alone does not identify a
binary. Do not infer target-device type or service coverage from a pass count.

## Adding results

Record the source revision, command, toolchain, configuration, target type and
outcome. Distinguish host regressions, emulator/simulator runs, unsigned device
archives, signed physical-device tests and authenticated services. Explain skipped
checks rather than counting them as passes.

Keep generated apps and working logs under ignored `build/`. Review summaries
before sharing them and remove keys, personal paths and inaccessible log links.
Do not commit credentials or unreviewed authenticated network output.
