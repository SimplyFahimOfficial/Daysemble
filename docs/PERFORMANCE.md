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
  - `StartupBenchmark`: cold start to the first Today frame (start-up time and frames), with and without the profile.
  - `InteractionBenchmark`: frame timing for rapid tab taps, completing tasks, fast calendar
    navigation and paging, every Calendar view in turn (each step checks its view by test tag), the Month/Week drag, the Today day swipe, Quick Add with
    the keyboard, search typing, page navigation, Settings to Support, the theme switch, dragging a
    task in List order (fails unless the row moved), a cancelled and a completed back gesture (fails unless Settings stayed, then left), and backgrounding. A step whose
    element is not on screen fails the run (`require` / `click` in `Journeys.kt`); before, it was
    skipped and an idle screen was timed. Run any of them with
    `adb shell settings put global animator_duration_scale 0` for the reduced-motion floor.
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

No timings have been recorded for this module: the passes that touched it had no device. The profile in
the repo is the starter version, and the benchmark numbers are still to be taken. Two open questions
for the first device run: the cost of the cross-fade between tabs (`TabSwitchBenchmark`), and whether
precomposing the other tabs in `TabHost` shortens the first switch more than it delays the frames
around start-up (run `TabSwitchBenchmark` with `startupMode = COLD` and a first switch to each tab,
with and without the preload). Use a Perfetto trace of `InteractionBenchmark.monthWeekDrag` and
`todayDaySwipe` to confirm that the Calendar and Today screens are not recomposed per frame.
