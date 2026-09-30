# Download lifecycle regression tests

From the repository root, run:

```sh
python3 tests/downloads.py
```

Resolve the Gradle dependencies first so Kotlin 2.4.20 and coroutines 1.10.2 are
available in the Gradle cache. The test extracts current production download
methods from both platform implementations and compiles them with controlled
native callbacks and filesystem doubles on the JVM.

Coverage includes cancellation, late success/error callbacks, replacement
requests, partial-file ownership, retry, cache reuse and start failure. The iOS
method is tested with JVM doubles, not in an iOS process.

These are not HTTP, process-kill, authenticated-service or native file-format
tests. Generated test files remain under ignored `build/`.
