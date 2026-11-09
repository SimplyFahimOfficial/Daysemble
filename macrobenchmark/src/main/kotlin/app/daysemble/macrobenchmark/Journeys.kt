package app.daysemble.macrobenchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until

const val PACKAGE = "app.daysemble"
private const val WAIT_MS = 10_000L

/** Starts the app and waits until Today is showing its agenda (the benchmark build seeds sample data). */
fun MacrobenchmarkScope.launchToToday() {
    pressHome()
    startActivityAndWait()
    device.wait(Until.hasObject(By.res("today_agenda")), WAIT_MS)
}

/** Taps a bottom tab by its test tag (tab_today, tab_calendar, tab_tasks) and waits for the screen to settle. */
fun MacrobenchmarkScope.openTab(name: String) {
    device.findObject(By.res("tab_$name"))?.click()
    device.waitForIdle()
}

/** Today, Calendar, Tasks and back, twice around. */
fun MacrobenchmarkScope.switchTabs() {
    repeat(2) {
        listOf("calendar", "tasks", "today").forEach { openTab(it) }
    }
}

/** Flings the list with the given test tag down and back up. */
fun MacrobenchmarkScope.scrollList(tag: String) {
    val list = device.wait(Until.findObject(By.res(tag)), WAIT_MS) ?: return
    list.setGestureMargin(device.displayWidth / 5)
    repeat(2) {
        list.fling(Direction.DOWN)
        device.waitForIdle()
        list.fling(Direction.UP)
        device.waitForIdle()
    }
}
