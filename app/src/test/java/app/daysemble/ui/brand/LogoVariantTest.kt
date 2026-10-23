package app.daysemble.ui.brand

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LogoVariantTest {

    @Test
    fun `default is the first supplied logo (CLASSIC) by explicit mapping`() {
        assertThat(LogoVariant.DEFAULT).isEqualTo(LogoVariant.CLASSIC)
        assertThat(LogoVariant.DEFAULT.key).isEqualTo("classic")
    }

    @Test
    fun `unknown or missing keys fall back to the default`() {
        assertThat(LogoVariant.fromKey(null)).isEqualTo(LogoVariant.CLASSIC)
        assertThat(LogoVariant.fromKey("")).isEqualTo(LogoVariant.CLASSIC)
        assertThat(LogoVariant.fromKey("removed-variant")).isEqualTo(LogoVariant.CLASSIC)
    }

    @Test
    fun `keys round-trip and are stable`() {
        assertThat(LogoVariant.entries.map { it.key }).containsExactly("classic", "midnight", "outline")
        LogoVariant.entries.forEach { assertThat(LogoVariant.fromKey(it.key)).isEqualTo(it) }
    }

    @Test
    fun `each variant has its own launcher alias`() {
        assertThat(LogoVariant.entries.map { it.launcherAlias }.toSet()).hasSize(3)
        assertThat(LogoVariant.CLASSIC.launcherAlias).isEqualTo("app.daysemble.LauncherClassic")
    }
}
