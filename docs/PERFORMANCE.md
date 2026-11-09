# Performance: Baseline Profile and macrobenchmarks

The goal is steady frames at 90 and 120 Hz. The app does not force a refresh rate; it avoids work
per frame instead (see the rules at the top of `ui/theme/Motion.kt`).

## What is in the repo

- `app/src/main/baseline-prof.txt`: the Baseline Profile that ships in the release APK. It starts as
  wildcard rules for the app's own start-up and UI packages. Replace it with a profile recorded on a
  device (below) for best results.
- `macrobenchmark/`: a test module with
  - `BaselineProfileGenerator`: start-up, the three tabs, scrolling, Settings and Quick Add.
  - `TabSwitchBenchmark`: frame timing while switching Today / Calendar / Tasks.
  - `ScrollBenchmark`: frame timing while scrolling the Today agenda and the Tasks list.
- The app's `benchmark` build type: the release build (R8, not debuggable) signed with the debug key.
  It seeds sample data and 150 extra tasks on first start (`BenchmarkSeed`, guarded by
  `BuildConfig.BENCHMARK_DATA`, which is `false` in debug and release) so lists have content. It is
  never distributed.

## Running them

You need a physical device or emulator on API 28 or newer (use a real device for timings; a
high-refresh-rate one to check 90 / 120 Hz).

```bash
# Frame timing, with and without the profile. Look at frameDurationCpuMs and frameOverrunMs (P90, P99).
./gradlew :macrobenchmark:connectedBenchmarkAndroidTest

# Record a profile.
./gradlew :macrobenchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.daysemble.macrobenchmark.BaselineProfileGenerator
```

The generator writes `...-baseline-prof.txt` to the device's `Download/` folder (the exact path is in
the test output). Copy it to `app/src/main/baseline-prof.txt`, build the release APK, and re-run the
benchmarks to compare `...WithBaselineProfile` against `...WithoutProfile`.

## Not measured yet

No timings have been recorded for this module: the pass that added it had no device. The profile in
the repo is the starter version, and the benchmark numbers are still to be taken.
