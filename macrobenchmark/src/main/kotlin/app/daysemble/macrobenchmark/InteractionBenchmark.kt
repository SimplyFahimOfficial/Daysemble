package app.daysemble.macrobenchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import android.graphics.Point
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

    /** Completes four tasks of the Tasks tab one after the other; each leaves the list as the next is tapped. */
    @Test fun taskCompleting() = run(tab = "tasks") {
        repeat(4) { click(By.descContains("complete")) }
    }

    /** The arrows step through months quickly; the grid pages each time. */
    @Test fun calendarFastNavigation() = run(tab = "calendar") { repeat(8) { click(By.desc("Next"), idle = false) }
        device.waitForIdle() }

    /**
     * Every Calendar view in turn, then quick back-and-forth taps that interrupt the transition before it ends.
     * After each full step the view that was asked for must be on screen (found by its test tag), so a tap that
     * did nothing fails the run instead of being timed.
     */
    @Test fun calendarModeSwitching() = run(tab = "calendar") {
        val tagOf = mapOf("Week" to TIME_GRID, "Day" to TIME_GRID, "Agenda" to AGENDA, "Month" to DAY_LIST)
        listOf("Week", "Day", "Agenda", "Month", "Agenda", "Week", "Month", "Day", "Month").forEach { name ->
            click(By.text(name))
            require(By.res(tagOf.getValue(name)))
        }
        repeat(3) { listOf("Week", "Month").forEach { name -> click(By.text(name), idle = false) } }
        device.waitForIdle()
        require(By.res(DAY_LIST))
    }

    /** Month to Week and back by dragging the grid vertically; each drag must change the view under it. */
    @Test fun monthWeekDrag() = run(tab = "calendar") {
        val w = device.displayWidth
        repeat(2) {
            val grid = require(By.res(MONTH_GRID)).visibleBounds
            device.swipe(w / 2, grid.bottom - grid.height() / 8, w / 2, grid.top, 24)
            device.waitForIdle()
            require(By.res(TIME_GRID))
            device.swipe(w / 2, grid.top, w / 2, grid.bottom - grid.height() / 8, 24)
            device.waitForIdle()
            require(By.res(DAY_LIST))
        }
    }

    /** Pages the month grid back and forth with quick swipes: the day list stays, the title changes. */
    @Test fun calendarFastPaging() = run(tab = "calendar") {
        val w = device.displayWidth
        repeat(3) {
            val y = require(By.res(MONTH_GRID)).visibleBounds.centerY()
            device.swipe(w * 9 / 10, y, w / 10, y, 8)
            device.swipe(w * 9 / 10, y, w / 10, y, 8)
            device.swipe(w / 10, y, w * 9 / 10, y, 8)
            device.waitForIdle()
        }
        require(By.res(DAY_LIST))
    }

    /** Swipe the Today agenda to the next and previous day. */
    @Test fun todayDaySwipe() = run {
        // Found again for each swipe: another day replaces the list, so an earlier handle goes stale.
        repeat(3) { require(By.res("today_agenda")).swipe(Direction.LEFT, 0.8f, 3000); device.waitForIdle() }
        repeat(3) { require(By.res("today_agenda")).swipe(Direction.RIGHT, 0.8f, 3000); device.waitForIdle() }
    }

    /** Quick Add opens (container growth, keyboard), a title is typed and the sheet is closed with Back. */
    @Test fun quickAddAndKeyboard() = run {
        repeat(2) {
            click(By.desc("Add task or event"))
            type("Benchmark")
            // The first Back hides the keyboard, the second closes the sheet (only if it is still there).
            device.pressBack()
            if (device.hasObject(By.clazz("android.widget.EditText"))) device.pressBack()
            device.waitForIdle()
        }
    }

    /** The header search opens, a query is typed in one go and the results update, then the search closes. */
    @Test fun searchTyping() = run {
        click(By.desc("Search"))
        type("task")
        click(By.desc("Close search"))
    }

    /** Settings and one of its pages open over the main screen and close again (the page transition). */
    @Test fun pageNavigation() = run {
        repeat(2) {
            click(By.desc("Settings"))
            click(By.text("Appearance"))
            device.pressBack()
            device.waitForIdle()
            device.pressBack()
            device.waitForIdle()
        }
    }

    /** Light to Dark and back (Settings, Appearance): the theme cross-fade and the recomposition under it. */
    @Test fun themeSwitching() = run {
        click(By.desc("Settings"))
        click(By.text("Appearance"))
        repeat(2) {
            click(By.text("Dark"))
            // The cross-fade runs for about 250 ms; wait it out so each switch is measured whole.
            device.waitForIdle()
            click(By.text("Light"))
        }
        device.pressBack()
        device.pressBack()
        device.waitForIdle()
    }

    /** Settings, Help & Support (the QR page) and back: the entry frames of the Support screen. */
    @Test fun settingsAndSupportNavigation() = run {
        repeat(2) {
            click(By.desc("Settings"))
            click(By.text("Help & Support"))
            click(By.text("Support My Work"))
            // Back out of Support, Help and Settings.
            repeat(3) { device.pressBack(); device.waitForIdle() }
        }
    }

    /**
     * Drags a row two rows down in List order (the benchmark build seeds 150 tasks) and fails unless the
     * order changed. `UiObject2.drag` long-presses first, which Compose's reorder handle needs; the
     * earlier plain `UiDevice.drag` never started the drag and timed an idle list.
     */
    @Test fun taskDragging() = run(tab = "tasks") {
        click(By.desc("Sort tasks"))
        click(By.text("List order"))
        val titles = { device.findObjects(By.textStartsWith("Benchmark task")).map { it.text } }
        require(By.desc("Reorder"))
        val before = titles()
        val c = require(By.desc("Reorder")).visibleCenter
        require(By.desc("Reorder")).drag(Point(c.x, c.y + device.displayHeight / 5), DRAG_SPEED)
        device.waitForIdle()
        check(titles() != before) { "The dragged row did not move: $before" }
    }

    /**
     * The predictive back gesture on Settings, released half way (cancelled: Settings must still be
     * there) and then taken to the end (committed: Settings must be gone).
     */
    @Test fun canceledAndCompletedBackGesture() = run {
        click(By.desc("Settings"))
        require(By.text("Appearance"))
        val h = device.displayHeight / 2
        // From just inside the edge, slowly (40 steps) so the system treats it as a back gesture, and
        // back again before lifting.
        device.swipe(intArrayOf(EDGE, h, device.displayWidth / 3, h, EDGE, h).toPoints(), 40)
        device.waitForIdle()
        check(device.hasObject(By.text("Appearance"))) { "Settings closed although the back gesture was cancelled" }
        device.swipe(EDGE, h, device.displayWidth - EDGE, h, 40)
        check(device.wait(Until.gone(By.text("Appearance")), WAIT_MS)) { "Settings stayed open after the back gesture" }
    }

    /** The app goes to the background and comes back: the screens are restored without rebuilding. */
    @Test fun backgroundAndRestore() = run {
        repeat(3) {
            device.pressHome()
            device.waitForIdle()
            startActivityAndWait()
        }
    }

    private fun MacrobenchmarkScope.tap(tag: String) = click(By.res(tag), idle = false)

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

    private companion object {
        /** px from the screen edge where a back swipe starts, and the drag speed (px/s). */
        const val EDGE = 4
        const val TIME_GRID = "calendar_time_grid"
        const val DAY_LIST = "calendar_day_list"
        const val AGENDA = "calendar_agenda"
        const val MONTH_GRID = "calendar_month_grid"
        const val DRAG_SPEED = 400
    }

    private fun IntArray.toPoints(): Array<Point> = Array(size / 2) { Point(this[it * 2], this[it * 2 + 1]) }
}
