package app.daysemble.support

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.file.Files

class SupportIntentsTest {

    @Test
    fun eachQrShareGetsAnIndependentCacheFile() {
        val cacheDirectory = Files.createTempDirectory("support-qr-share-test").toFile()
        try {
            val first = SupportIntents.createShareQrFile(cacheDirectory)
            val second = SupportIntents.createShareQrFile(cacheDirectory)

            assertThat(first).isNotEqualTo(second)
            assertThat(first.parentFile).isEqualTo(second.parentFile)
            assertThat(first.exists()).isTrue()
            assertThat(second.exists()).isTrue()
        } finally {
            cacheDirectory.deleteRecursively()
        }
    }
}
