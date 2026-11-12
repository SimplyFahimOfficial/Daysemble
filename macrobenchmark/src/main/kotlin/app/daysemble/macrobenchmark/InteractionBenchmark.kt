package app.daysemble.macrobenchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Frame timing for the interactions that are not scrolling: the journeys of the animation pass
 * (rapid tab taps, task check and uncheck, calendar navigation and the Month/Week drag, the Today
 * day swipe, Quick Add with the keyboard, search typing, a detail page and Back, backgrounding).
 * Each runs with the Baseline Profile (the shipped configuration); read frameDurationCpuMs and
 * frameOverrunMs at P90 / P99. Not measured yet: the pass that added these had no device.
 *
 * Reduced motion: run any of them after `adb shell settings put global animator_duration_scale 0`
 * (and `transition_animation_scale 0`); the app then skips all movement and the frame cost is the
 * floor to compare the animated run against.
 */
@RunWith(AndroidJUnit4::class)
class InteractionBenchmark {
    @get:Rule val rule = MacrobenchmarkRule()

    /** Three taps on the tabs in under a second: each interrupts the cross-fade before it ends. */
    @Test fun rapidTabTaps() = run { repeat(4) { listOf("tasks", "calendar", "today").forEach { tab -> tap("tab_$tab") } } }

    /** The check circle of the first task, checked and unchecked again quickly. */
    @Test fun taskCheckUncheck() = run(tab = "tasks") {
        repeat(4) { device.findObject(By.descContains("complete"))?.click() }
        device.waitForIdle()
    }

    /** The arrows step through months quickly; the grid pages each time. */
    @Test fun calendarFastNavigation() = run(tab = "calendar") { repeat(8) { device.findObject(By.desc("Next"))?.click() } }

    /** Every Calendar view in turn, then quick back-and-forth taps that interrupt the transition before it ends. */
    @Test fun calendarModeSwitching() = run(tab = "calendar") {
        listOf("Week", "Day", "Agenda", "Month", "Agenda", "Week", "Month", "Day", "Month").forEach { name ->
            device.findObject(By.text(name))?.click()
            device.waitForIdle()
        }
        repeat(3) { listOf("Week", "Month").forEach { name -> device.findObject(By.text(name))?.click() } }
        device.waitForIdle()
    }

    /** Month to Week and back by dragging the grid vertically. */
    @Test fun monthWeekDrag() = run(tab = "calendar") {
        val w = device.displayWidth
        val h = device.displayHeight
        repeat(2) {
            device.swipe(w / 2, h * 3 / 10, w / 2, h / 10, 24)
            device.waitForIdle()
            device.swipe(w / 2, h / 10, w / 2, h * 3 / 10, 24)
            device.waitForIdle()
        }
    }

    /** Swipe the Today agenda to the next and previous day. */
    @Test fun todayDaySwipe() = run {
        val agenda = device.wait(Until.findObject(By.res("today_agenda")), WAIT_MS) ?: return@run
        repeat(3) { agenda.swipe(Direction.LEFT, 0.8f, 3000); device.waitForIdle() }
        repeat(3) { agenda.swipe(Direction.RIGHT, 0.8f, 3000); device.waitForIdle() }
    }

    /** Quick Add opens (container growth, keyboard), a title is typed and the sheet is closed with Back. */
    @Test fun quickAddAndKeyboard() = run {
        repeat(2) {
            device.findObject(By.desc("Add task or event"))?.click()
            device.wait(Until.hasObject(By.clazz("android.widget.EditText")), WAIT_MS)
            device.findObject(By.clazz("android.widget.EditText"))?.text = "Benchmark"
            device.waitForIdle()
            // The first Back hides the keyboard, the second closes the sheet (only if it is still there).
            device.pressBack()
            if (device.hasObject(By.clazz("android.widget.EditText"))) device.pressBack()
            device.waitForIdle()
        }
    }

    /** The header search opens, a query is typed in one go and the results update, then the search closes. */
    @Test fun searchTyping() = run {
        device.findObject(By.desc("Search"))?.click()
        device.wait(Until.hasObject(By.clazz("android.widget.EditText")), WAIT_MS)
        device.findObject(By.clazz("android.widget.EditText"))?.text = "task"
        device.waitForIdle()
        device.findObject(By.desc("Close search"))?.click()
        device.waitForIdle()
    }

    /** Settings and one of its pages open over the main screen and close again (the page transition). */
    @Test fun pageNavigation() = run {
        repeat(2) {
            device.findObject(By.desc("Settings"))?.click()
            device.wait(Until.hasObject(By.text("Appearance")), WAIT_MS)
            device.findObject(By.text("Appearance"))?.click()
            device.waitForIdle()
            device.pressBack()
            device.waitForIdle()
            device.pressBack()
            device.waitForIdle()
        }
    }

    /** A back swipe from the left edge that is released half way (cancelled), then one that completes. */
    @Test fun canceledAndCompletedBackGesture() = run {
        device.findObject(By.desc("Settings"))?.click()
        device.wait(Until.hasObject(By.text("Appearance")), WAIT_MS)
        val h = device.displayHeight / 2
        // 40 steps over ~0.4 s: slow enough for the system to treat it as a gesture, then back to the edge.
        device.swipe(0, h, device.displayWidth / 3, h, 40)
        device.waitForIdle()
        device.swipe(0, h, device.displayWidth, h, 40)
        device.waitForIdle()
    }

    /** The app goes to the background and comes back: the screens are restored without rebuilding. */
    @Test fun backgroundAndRestore() = run {
        repeat(3) {
            device.pressHome()
            device.waitForIdle()
            startActivityAndWait()
        }
    }

    private fun MacrobenchmarkScope.tap(tag: String) { device.findObject(By.res(tag))?.click() }

    private fun run(tab: String? = null, block: MacrobenchmarkScope.() -> Unit) = rule.measureRepeated(
        packageName = PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
        startupMode = StartupMode.WARM,
        iterations = 10,
        setupBlock = {
            launchToToday()
            tab?.let { openTab(it) }
        },
        measureBlock = block,
    )
}
