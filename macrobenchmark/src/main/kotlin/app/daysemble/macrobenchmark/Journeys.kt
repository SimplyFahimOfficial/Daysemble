package app.daysemble.macrobenchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

const val PACKAGE = "app.daysemble"
internal const val WAIT_MS = 10_000L

/**
 * Waits for [selector] and returns it. A journey whose element is missing fails the run: it used to
 * skip the step silently, and the benchmark then timed an idle screen and reported it as a result.
 */
fun MacrobenchmarkScope.require(selector: BySelector): UiObject2 =
    device.wait(Until.findObject(selector), WAIT_MS) ?: error("Not found within $WAIT_MS ms: $selector")

/** Taps [selector] and, unless [idle] is false (rapid taps), waits for the screen to settle. */
fun MacrobenchmarkScope.click(selector: BySelector, idle: Boolean = true) {
    require(selector).click()
    if (idle) device.waitForIdle()
}

/** Types [text] into the one text field on screen. */
fun MacrobenchmarkScope.type(text: String) {
    require(By.clazz("android.widget.EditText")).text = text
    device.waitForIdle()
}

/** Starts the app and waits until Today is showing its agenda (the benchmark build seeds sample data). */
fun MacrobenchmarkScope.launchToToday() {
    pressHome()
    startActivityAndWait()
    require(By.res("today_agenda"))
}

/** Taps a bottom tab by its test tag (tab_today, tab_calendar, tab_tasks) and waits for the screen to settle. */
fun MacrobenchmarkScope.openTab(name: String) = click(By.res("tab_$name"))

/** Today, Calendar, Tasks and back, twice around. */
fun MacrobenchmarkScope.switchTabs() {
    repeat(2) {
        listOf("calendar", "tasks", "today").forEach { openTab(it) }
    }
}

/** Flings the list with the given test tag down and back up. */
fun MacrobenchmarkScope.scrollList(tag: String) {
    val list = require(By.res(tag))
    list.setGestureMargin(device.displayWidth / 5)
    repeat(2) {
        list.fling(Direction.DOWN)
        device.waitForIdle()
        list.fling(Direction.UP)
        device.waitForIdle()
    }
}
