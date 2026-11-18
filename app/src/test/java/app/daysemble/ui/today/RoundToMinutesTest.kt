package app.daysemble.ui.today

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Duration

/** The sleep line compares last night with the plan, so a deficit must round like a surplus. */
class RoundToMinutesTest {

    @Test
    fun `a deficit keeps its magnitude`() {
        assertThat(Duration.ofMinutes(-60).roundToMinutes()).isEqualTo(-60)
        assertThat(Duration.ofSeconds(-90).roundToMinutes()).isEqualTo(-2)
        assertThat(Duration.ofSeconds(-1770).roundToMinutes()).isEqualTo(-30) // 29 min 30 s short → 30
        assertThat(Duration.ofMinutes(-1).roundToMinutes()).isEqualTo(-1)
    }

    @Test
    fun `a surplus rounds as before`() {
        assertThat(Duration.ofMinutes(60).roundToMinutes()).isEqualTo(60)
        assertThat(Duration.ofSeconds(90).roundToMinutes()).isEqualTo(2)
        assertThat(Duration.ofSeconds(1770).roundToMinutes()).isEqualTo(30)
        assertThat(Duration.ZERO.roundToMinutes()).isEqualTo(0)
    }
}
