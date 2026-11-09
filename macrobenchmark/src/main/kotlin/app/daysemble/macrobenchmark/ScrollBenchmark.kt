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

/** Frame timing while scrolling the Today agenda and the Tasks list (150 seeded tasks), with and without the Baseline Profile. */
@RunWith(AndroidJUnit4::class)
class ScrollBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    @Test fun todayWithoutProfile() = run(CompilationMode.None(), "today_agenda", tab = null)

    @Test fun todayWithBaselineProfile() = run(CompilationMode.Partial(BaselineProfileMode.Require), "today_agenda", tab = null)

    @Test fun tasksWithoutProfile() = run(CompilationMode.None(), "tasks_list", tab = "tasks")

    @Test fun tasksWithBaselineProfile() = run(CompilationMode.Partial(BaselineProfileMode.Require), "tasks_list", tab = "tasks")

    private fun run(mode: CompilationMode, list: String, tab: String?) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = mode,
        startupMode = StartupMode.WARM,
        iterations = 10,
        setupBlock = {
            launchToToday()
            tab?.let { openTab(it) }
        },
    ) { scrollList(list) }
}
