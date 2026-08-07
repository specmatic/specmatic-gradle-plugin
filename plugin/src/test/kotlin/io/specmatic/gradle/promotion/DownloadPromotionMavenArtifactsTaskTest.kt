package io.specmatic.gradle.promotion

import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DownloadPromotionMavenArtifactsTaskTest {
    @TempDir
    lateinit var tempDir: java.nio.file.Path

    @Test
    fun `downloads artifacts with basic authentication when credentials are configured`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("jar-bytes"))
            val task =
                ProjectBuilder
                    .builder()
                    .build()
                    .tasks
                    .create("downloadTest", DownloadPromotionMavenArtifactsTask::class.java)
            task.canonicalRepository.set(server.url("/").toString())
            task.artifactRelativePaths.set(listOf("io/specmatic/example/1.0.0/example-1.0.0.jar"))
            task.outputDirectory.set(tempDir.resolve("artifacts").toFile())
            task.username.set("user")
            task.password.set("pass")

            task.downloadArtifacts()

            assertThat(server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("Authorization")).isEqualTo("Basic dXNlcjpwYXNz")
            assertThat(tempDir.resolve("artifacts/io/specmatic/example/1.0.0/example-1.0.0.jar").toFile()).hasContent("jar-bytes")
        } finally {
            server.shutdown()
        }
    }
}
