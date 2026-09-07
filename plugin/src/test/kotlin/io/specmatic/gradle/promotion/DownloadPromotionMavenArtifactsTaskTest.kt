package io.specmatic.gradle.promotion

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
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
            task.backoffMillis.set(0)

            task.downloadArtifacts()

            assertThat(server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("Authorization")).isEqualTo("Basic dXNlcjpwYXNz")
            assertThat(tempDir.resolve("artifacts/io/specmatic/example/1.0.0/example-1.0.0.jar").toFile()).hasContent("jar-bytes")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `backs off globally before retrying failed downloads`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(MockResponse().setResponseCode(200).setBody("jar-bytes"))
            val task =
                ProjectBuilder
                    .builder()
                    .build()
                    .tasks
                    .create("downloadTest", DownloadPromotionMavenArtifactsTask::class.java)
            task.canonicalRepository.set(server.url("/").toString())
            task.artifactRelativePaths.set(listOf("artifact.jar"))
            task.outputDirectory.set(tempDir.resolve("artifacts").toFile())
            task.maxRetries.set(2)
            task.backoffMillis.set(0)

            task.downloadArtifacts()

            assertThat(server.requestCount).isEqualTo(2)
            assertThat(tempDir.resolve("artifacts/artifact.jar").toFile()).hasContent("jar-bytes")
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `uses exponential backoff between retries`() {
        val server = MockWebServer()
        val requestTimes = ConcurrentLinkedQueue<Long>()
        server.dispatcher =
            object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    requestTimes.add(System.nanoTime())
                    return when (requestTimes.size) {
                        1, 2 -> MockResponse().setResponseCode(503)
                        else -> MockResponse().setResponseCode(200).setBody("jar-bytes")
                    }
                }
            }
        server.start()
        try {
            val task =
                ProjectBuilder
                    .builder()
                    .build()
                    .tasks
                    .create("downloadTest", DownloadPromotionMavenArtifactsTask::class.java)
            task.canonicalRepository.set(server.url("/").toString())
            task.artifactRelativePaths.set(listOf("artifact.jar"))
            task.outputDirectory.set(tempDir.resolve("artifacts").toFile())
            task.maxRetries.set(3)
            task.backoffMillis.set(100)

            task.downloadArtifacts()

            val times = requestTimes.toList()
            assertThat(times).hasSize(3)
            assertThat((times[1] - times[0]) / 1_000_000).isGreaterThanOrEqualTo(80)
            assertThat((times[2] - times[1]) / 1_000_000).isGreaterThanOrEqualTo(160)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `shares backoff across concurrent downloads`() {
        val server = MockWebServer()
        val attempts = ConcurrentHashMap<String, Int>()
        val retryTimes = ConcurrentLinkedQueue<Long>()
        server.dispatcher =
            object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    val path = request.path!!
                    val attempt = attempts.merge(path, 1) { count, _ -> count + 1 }!!
                    if (attempt == 2) retryTimes.add(System.nanoTime())
                    return if (attempt ==
                        1
                    ) {
                        MockResponse().setResponseCode(503)
                    } else {
                        MockResponse().setResponseCode(200).setBody("jar-bytes")
                    }
                }
            }
        server.start()
        try {
            val task =
                ProjectBuilder
                    .builder()
                    .build()
                    .tasks
                    .create("downloadTest", DownloadPromotionMavenArtifactsTask::class.java)
            task.canonicalRepository.set(server.url("/").toString())
            task.artifactRelativePaths.set(listOf("one.jar", "two.jar"))
            task.outputDirectory.set(tempDir.resolve("artifacts").toFile())
            task.maxParallelDownloads.set(2)
            task.maxRetries.set(2)
            task.backoffMillis.set(100)

            task.downloadArtifacts()

            val times = retryTimes.toList().sorted()
            assertThat(times).hasSize(2)
            assertThat((times[1] - times[0]) / 1_000_000).isGreaterThanOrEqualTo(80)
        } finally {
            server.shutdown()
        }
    }
}
