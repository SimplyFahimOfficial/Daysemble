package app.daysemble.macrobenchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Frame timing while switching Today / Calendar / Tasks, with and without the Baseline Profile.
 * Read frameDurationCpuMs and frameOverrunMs at P90 / P99: the target is no overrun at 90 and 120 Hz.
 */
@RunWith(AndroidJUnit4::class)
class TabSwitchBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test fun tabsWithoutProfile() = run(CompilationMode.None())

    @Test fun tabsWithBaselineProfile() = run(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun run(mode: CompilationMode) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.WARM,
        iterations = 10,
        setupBlock = { launchToToday() },
    ) { switchTabs() }
}
