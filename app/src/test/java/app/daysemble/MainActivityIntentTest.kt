package app.daysemble

import android.content.ComponentName
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.daysemble.data.db.OwnerType
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Only the app's own intents (to the non-exported MainActivity) may open items or Quick Add. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MainActivityIntentTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun notificationIntent_isTrusted_andParsed() {
        val intent = MainActivity.openIntent(ctx, OwnerType.TASK, "t1", null)
        assertThat(MainActivity.isOwnIntent(intent)).isTrue()
        assertThat(MainActivity.parse(intent)).isEqualTo(OpenRequest(OwnerType.TASK, "t1", null))
    }

    @Test fun sameExtrasThroughAnExportedLauncherAlias_areIgnored() {
        val viaAlias = MainActivity.openIntent(ctx, OwnerType.TASK, "t1", null)
            .setComponent(ComponentName(ctx.packageName, "app.daysemble.LauncherClassic"))
        assertThat(MainActivity.isOwnIntent(viaAlias)).isFalse()
        val shortcutViaAlias = Intent(AppShortcut.NEW_TASK.action).setComponent(ComponentName(ctx.packageName, "app.daysemble.LauncherOutline"))
        assertThat(MainActivity.isOwnIntent(shortcutViaAlias)).isFalse()
        assertThat(MainActivity.isOwnIntent(null)).isFalse()
    }
}
