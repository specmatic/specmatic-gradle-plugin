package io.specmatic.gradle.promotion

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PromoteMavenArtifactsTaskTest {
    @TempDir
    lateinit var tempDir: java.nio.file.Path

    @Test
    fun `creates maven metadata when publishing artifact for first time`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setResponseCode(404))
        repeat(5) {
            server.enqueue(MockResponse().setResponseCode(201))
        }
        server.start()

        try {
            val pomPath = "io/specmatic/example/1.0.0/example-1.0.0.pom"
            val artifactPath = "io/specmatic/example/1.0.0/example-1.0.0.jar"
            val stagingDir = tempDir.resolve("staging")
            val pomFile = stagingDir.resolve(pomPath)
            val artifactFile = stagingDir.resolve(artifactPath)
            Files.createDirectories(pomFile.parent)
            Files.writeString(pomFile, "<project/>")
            Files.createDirectories(artifactFile.parent)
            Files.writeString(artifactFile, "artifact-bytes")

            val task = task()
            task.inputDirectory.set(stagingDir.toFile())
            task.targets.set(
                listOf(
                    target(
                        task = task,
                        repoName = "reposilite",
                        kind = PromotionMavenTargetKind.REPOSITORY,
                        url = server.url("/repository/releases/").toString(),
                        username = "user",
                        password = "pass",
                        artifactPaths = listOf(pomPath, artifactPath),
                    ),
                ),
            )

            task.promoteArtifacts()

            val artifactRequest = nextRequest(server)
            assertThat(artifactRequest.method).isEqualTo("PUT")
            assertThat(artifactRequest.path).isEqualTo("/repository/releases/$pomPath")
            assertThat(artifactRequest.getHeader("Authorization")).isEqualTo("Basic dXNlcjpwYXNz")
            assertThat(artifactRequest.body.readUtf8()).isEqualTo("<project/>")

            val jarRequest = nextRequest(server)
            assertThat(jarRequest.method).isEqualTo("PUT")
            assertThat(jarRequest.path).isEqualTo("/repository/releases/$artifactPath")
            assertThat(jarRequest.getHeader("Authorization")).isEqualTo("Basic dXNlcjpwYXNz")
            assertThat(jarRequest.body.readUtf8()).isEqualTo("artifact-bytes")

            val metadataGet = nextRequest(server)
            assertThat(metadataGet.method).isEqualTo("GET")
            assertThat(metadataGet.path).isEqualTo("/repository/releases/io/specmatic/example/maven-metadata.xml")

            val metadataPut = nextRequest(server)
            assertThat(metadataPut.method).isEqualTo("PUT")
            assertThat(metadataPut.path).isEqualTo("/repository/releases/io/specmatic/example/maven-metadata.xml")
            val metadataBody = metadataPut.body.readUtf8()
            assertThat(metadataBody).contains("<artifactId>example</artifactId>")
            assertThat(metadataBody).contains("<version>1.0.0</version>")
            assertThat(metadataBody).contains("<release>1.0.0</release>")

            val metadataChecksumPaths =
                List(4) { nextRequest(server).path }
            assertThat(metadataChecksumPaths).containsExactlyInAnyOrder(
                "/repository/releases/io/specmatic/example/maven-metadata.xml.md5",
                "/repository/releases/io/specmatic/example/maven-metadata.xml.sha1",
                "/repository/releases/io/specmatic/example/maven-metadata.xml.sha256",
                "/repository/releases/io/specmatic/example/maven-metadata.xml.sha512",
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `merges existing maven metadata when publishing new version`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(MockResponse().setResponseCode(201))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/xml")
                .setBody(
                    """
                    <metadata>
                      <groupId>io.specmatic</groupId>
                      <artifactId>example</artifactId>
                      <versioning>
                        <latest>1.0.0</latest>
                        <release>1.0.0</release>
                        <versions>
                          <version>1.0.0</version>
                        </versions>
                        <lastUpdated>20260710000000</lastUpdated>
                      </versioning>
                    </metadata>
                    """.trimIndent(),
                ),
        )
        repeat(5) {
            server.enqueue(MockResponse().setResponseCode(201))
        }
        server.start()

        try {
            val pomPath = "io/specmatic/example/1.1.0/example-1.1.0.pom"
            val artifactPath = "io/specmatic/example/1.1.0/example-1.1.0.jar"
            val stagingDir = tempDir.resolve("staging-existing")
            val pomFile = stagingDir.resolve(pomPath)
            val artifactFile = stagingDir.resolve(artifactPath)
            Files.createDirectories(pomFile.parent)
            Files.writeString(pomFile, "<project/>")
            Files.createDirectories(artifactFile.parent)
            Files.writeString(artifactFile, "artifact-bytes")

            val task = task()
            task.inputDirectory.set(stagingDir.toFile())
            task.targets.set(
                listOf(
                    target(
                        task = task,
                        repoName = "reposilite",
                        kind = PromotionMavenTargetKind.REPOSITORY,
                        url = server.url("/repository/releases/").toString(),
                        username = "user",
                        password = "pass",
                        artifactPaths = listOf(pomPath, artifactPath),
                    ),
                ),
            )

            task.promoteArtifacts()

            nextRequest(server) // pom PUT
            nextRequest(server) // jar PUT

            val metadataGet = nextRequest(server)
            assertThat(metadataGet.method).isEqualTo("GET")
            assertThat(metadataGet.path).isEqualTo("/repository/releases/io/specmatic/example/maven-metadata.xml")

            val metadataPut = nextRequest(server)
            assertThat(metadataPut.method).isEqualTo("PUT")
            assertThat(metadataPut.path).isEqualTo("/repository/releases/io/specmatic/example/maven-metadata.xml")
            val metadataBody = metadataPut.body.readUtf8()
            assertThat(metadataBody).contains("<version>1.0.0</version>")
            assertThat(metadataBody).contains("<version>1.1.0</version>")
            assertThat(metadataBody).contains("<latest>1.1.0</latest>")
            assertThat(metadataBody).contains("<release>1.1.0</release>")

            val metadataChecksumPaths =
                List(4) { nextRequest(server).path }
            assertThat(metadataChecksumPaths).containsExactlyInAnyOrder(
                "/repository/releases/io/specmatic/example/maven-metadata.xml.md5",
                "/repository/releases/io/specmatic/example/maven-metadata.xml.sha1",
                "/repository/releases/io/specmatic/example/maven-metadata.xml.sha256",
                "/repository/releases/io/specmatic/example/maven-metadata.xml.sha512",
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `uploads deployment bundle to maven central target and checks status`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(201).setBody("deployment-123"))
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "deploymentId": "deployment-123",
                      "deploymentName": "bundle.zip",
                      "deploymentState": "PUBLISHED",
                      "purls": ["pkg:maven/io.specmatic/example@1.0.0"]
                    }
                    """.trimIndent(),
                ),
        )
        server.start()

        try {
            val pomPath = "io/specmatic/example/1.0.0/example-1.0.0.pom"
            val jarPath = "io/specmatic/example/1.0.0/example-1.0.0.jar"
            val stagingDir = tempDir.resolve("staging")
            val pomFile = stagingDir.resolve(pomPath)
            val jarFile = stagingDir.resolve(jarPath)
            Files.createDirectories(pomFile.parent)
            Files.writeString(pomFile, "<project/>")
            Files.writeString(jarFile, "jar-bytes")

            val task = task()
            task.inputDirectory.set(stagingDir.toFile())
            task.targets.set(
                listOf(
                    target(
                        task = task,
                        repoName = "mavenCentral",
                        kind = PromotionMavenTargetKind.MAVEN_CENTRAL,
                        url = server.url("/").toString(),
                        username = "centralUser",
                        password = "centralPass",
                        artifactPaths = listOf(pomPath, jarPath),
                    ),
                ),
            )

            task.promoteArtifacts()

            val uploadRequest = nextRequest(server)
            assertThat(uploadRequest.method).isEqualTo("POST")
            assertThat(uploadRequest.path).contains("/api/v1/publisher/upload")
            assertThat(uploadRequest.path).contains("publishingType=AUTOMATIC")
            assertThat(uploadRequest.getHeader("Authorization")).isEqualTo("Bearer Y2VudHJhbFVzZXI6Y2VudHJhbFBhc3M=")
            val uploadBody = uploadRequest.body.readUtf8()
            assertThat(uploadBody).contains("example-1.0.0.pom")
            assertThat(uploadBody).contains("example-1.0.0.jar")

            val statusRequest = nextRequest(server)
            assertThat(statusRequest.method).isEqualTo("POST")
            assertThat(statusRequest.path).isEqualTo("/api/v1/publisher/status?id=deployment-123")
            assertThat(statusRequest.getHeader("Authorization")).isEqualTo("Bearer Y2VudHJhbFVzZXI6Y2VudHJhbFBhc3M=")
        } finally {
            server.shutdown()
        }
    }

    private fun task(): PromoteMavenArtifactsTask {
        val project = ProjectBuilder.builder().build()
        return project.tasks.create("promoteTest", PromoteMavenArtifactsTask::class.java)
    }

    private fun target(
        task: PromoteMavenArtifactsTask,
        repoName: String,
        kind: PromotionMavenTargetKind,
        url: String,
        username: String,
        password: String,
        artifactPaths: List<String>,
    ): PromotionMavenTargetInput =
        task.project.objects.newInstance(PromotionMavenTargetInput::class.java).apply {
            this.repoName.set(repoName)
            this.kind.set(kind.name)
            this.url.set(url)
            this.username.set(username)
            this.password.set(password)
            this.artifactPaths.set(artifactPaths)
        }

    private fun nextRequest(server: MockWebServer): RecordedRequest =
        requireNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Expected request not received within timeout" }
}
