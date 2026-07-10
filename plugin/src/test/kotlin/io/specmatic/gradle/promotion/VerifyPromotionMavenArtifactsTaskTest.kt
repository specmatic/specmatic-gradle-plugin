package io.specmatic.gradle.promotion

import java.nio.file.Files
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class VerifyPromotionMavenArtifactsTaskTest {
    @TempDir
    lateinit var tempDir: java.nio.file.Path

    @Test
    fun `computes stable sha256 digest`() {
        val file = tempDir.resolve("artifact.jar").toFile()
        Files.writeString(file.toPath(), "specmatic")

        assertThat(file.digest("SHA-256"))
            .isEqualTo("cbb913d8c428b4540c3de3e77b8245eae900d0bc5a6377097f85abd5dd80a92f")
    }
}
