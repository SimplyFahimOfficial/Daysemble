package app.daysemble.macrobenchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records the Baseline Profile: start-up, the three tabs, scrolling, Settings and Quick Add. Run it
 * on a device (API 28+) and copy the result into app/src/main/baseline-prof.txt (docs/PERFORMANCE.md).
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test fun generate() = rule.collect(packageName = PACKAGE, includeInStartupProfile = true) {
        launchToToday()
        scrollList("today_agenda")
        switchTabs()
        openTab("tasks")
        scrollList("tasks_list")
        // Settings and back.
        device.findObject(By.desc("Settings"))?.click()
        device.wait(Until.hasObject(By.text("Appearance")), 5_000)
        device.pressBack()
        device.waitForIdle()
        // Quick Add opening and closing.
        device.findObject(By.desc("Add task or event"))?.click()
        device.waitForIdle()
        device.pressBack()
        device.waitForIdle()
        openTab("today")
    }
}
