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
            .isEqualTo("79775f8db9f00f11de528c4946472be8f52d4e9632e425263550a8bb78d0acfd")
    }
}
