package io.specmatic.gradle.promotion

import java.io.StringReader
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory
import okhttp3.HttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.w3c.dom.Document
import org.xml.sax.InputSource

class PromoteMavenArtifactsTaskTest {
    @TempDir
    lateinit var tempDir: java.nio.file.Path

    @Test
    fun `creates maven metadata when publishing artifact for first time`() {
        val server = MockWebServer()
        server.start()

        try {
            val pomPath = "io/specmatic/example/1.0.0/example-1.0.0.pom"
            val artifactPath = "io/specmatic/example/1.0.0/example-1.0.0.jar"
            val metadataPath = "io/specmatic/example/maven-metadata.xml"
            val stagingDir = tempDir.resolve("staging")
            val pomFile = stagingDir.resolve(pomPath)
            val artifactFile = stagingDir.resolve(artifactPath)
            Files.createDirectories(pomFile.parent)
            Files.writeString(pomFile, "<project/>")
            Files.createDirectories(artifactFile.parent)
            Files.writeString(artifactFile, "artifact-bytes")

            server.dispatcher =
                routeRequests(
                    server.url("/repository/releases/"),
                    mapOf(
                        // allow pom upload
                        "PUT /repository/releases/$pomPath" to MockResponse().setResponseCode(201),
                        // allow artifact upload
                        "PUT /repository/releases/$artifactPath" to MockResponse().setResponseCode(201),
                        // metadata does not exist - no existing package
                        "GET /repository/releases/$metadataPath" to MockResponse().setResponseCode(404),
                        // allow metadata upload (and checksums)
                        "PUT /repository/releases/$metadataPath" to MockResponse().setResponseCode(201),
                        "PUT /repository/releases/$metadataPath.md5" to MockResponse().setResponseCode(201),
                        "PUT /repository/releases/$metadataPath.sha1" to MockResponse().setResponseCode(201),
                        "PUT /repository/releases/$metadataPath.sha256" to MockResponse().setResponseCode(201),
                        "PUT /repository/releases/$metadataPath.sha512" to MockResponse().setResponseCode(201),
                    ),
                )

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

            val requests = requestsByPath(server)

            val pomRequest = requests.singleRequest("PUT", "/repository/releases/$pomPath")
            assertThat(pomRequest.getHeader("Authorization")).isEqualTo("Basic dXNlcjpwYXNz")
            assertThat(pomRequest.body.readUtf8()).isEqualTo("<project/>")

            val jarRequest = requests.singleRequest("PUT", "/repository/releases/$artifactPath")
            assertThat(jarRequest.getHeader("Authorization")).isEqualTo("Basic dXNlcjpwYXNz")
            assertThat(jarRequest.body.readUtf8()).isEqualTo("artifact-bytes")

            requests.singleRequest("GET", "/repository/releases/$metadataPath")

            val metadataPut = requests.singleRequest("PUT", "/repository/releases/$metadataPath")
            val metadataXml = parseXml(metadataPut.body.readUtf8())
            assertThat(metadataXml.singleValue("/metadata/groupId")).isEqualTo("io.specmatic")
            assertThat(metadataXml.singleValue("/metadata/artifactId")).isEqualTo("example")
            assertThat(metadataXml.singleValue("/metadata/versioning/latest")).isEqualTo("1.0.0")
            assertThat(metadataXml.singleValue("/metadata/versioning/release")).isEqualTo("1.0.0")
            assertThat(metadataXml.values("/metadata/versioning/versions/version")).containsExactly("1.0.0")
            assertThat(metadataXml.singleValue("/metadata/versioning/lastUpdated")).matches("\\d{14}")

            assertThat(requests.keys)
                .contains(
                    "PUT /repository/releases/$metadataPath.md5",
                    "PUT /repository/releases/$metadataPath.sha1",
                    "PUT /repository/releases/$metadataPath.sha256",
                    "PUT /repository/releases/$metadataPath.sha512",
                )
            assertThat(requests).hasSize(8)
            assertThat(requests.values.flatten().map { it.path }).containsExactlyInAnyOrder(
                "/repository/releases/$pomPath",
                "/repository/releases/$artifactPath",
                "/repository/releases/$metadataPath",
                "/repository/releases/$metadataPath",
                "/repository/releases/$metadataPath.md5",
                "/repository/releases/$metadataPath.sha1",
                "/repository/releases/$metadataPath.sha256",
                "/repository/releases/$metadataPath.sha512",
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `merges existing maven metadata when publishing new version`() {
        val server = MockWebServer()
        server.start()

        try {
            val pomPath = "io/specmatic/example/1.1.0/example-1.1.0.pom"
            val artifactPath = "io/specmatic/example/1.1.0/example-1.1.0.jar"
            val metadataPath = "io/specmatic/example/maven-metadata.xml"
            val stagingDir = tempDir.resolve("staging-existing")
            val pomFile = stagingDir.resolve(pomPath)
            val artifactFile = stagingDir.resolve(artifactPath)
            Files.createDirectories(pomFile.parent)
            Files.writeString(pomFile, "<project/>")
            Files.createDirectories(artifactFile.parent)
            Files.writeString(artifactFile, "artifact-bytes")

            server.dispatcher =
                routeRequests(
                    server.url("/repository/releases/"),
                    mapOf(
                        // allow pom upload
                        "PUT /repository/releases/$pomPath" to MockResponse().setResponseCode(201),
                        // allow artifact upload
                        "PUT /repository/releases/$artifactPath" to MockResponse().setResponseCode(201),
                        // existing metadata exists
                        "GET /repository/releases/$metadataPath" to
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
                        // allow uploading metadata
                        "PUT /repository/releases/$metadataPath" to MockResponse().setResponseCode(201),
                        "PUT /repository/releases/$metadataPath.md5" to MockResponse().setResponseCode(201),
                        "PUT /repository/releases/$metadataPath.sha1" to MockResponse().setResponseCode(201),
                        "PUT /repository/releases/$metadataPath.sha256" to MockResponse().setResponseCode(201),
                        "PUT /repository/releases/$metadataPath.sha512" to MockResponse().setResponseCode(201),
                    ),
                )

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

            val requests = requestsByPath(server)

            requests.singleRequest("PUT", "/repository/releases/$pomPath")
            requests.singleRequest("PUT", "/repository/releases/$artifactPath")
            requests.singleRequest("GET", "/repository/releases/$metadataPath")

            val metadataPut = requests.singleRequest("PUT", "/repository/releases/$metadataPath")
            val metadataXml = parseXml(metadataPut.body.readUtf8())
            assertThat(metadataXml.singleValue("/metadata/groupId")).isEqualTo("io.specmatic")
            assertThat(metadataXml.singleValue("/metadata/artifactId")).isEqualTo("example")
            assertThat(metadataXml.singleValue("/metadata/versioning/latest")).isEqualTo("1.1.0")
            assertThat(metadataXml.singleValue("/metadata/versioning/release")).isEqualTo("1.1.0")
            assertThat(metadataXml.values("/metadata/versioning/versions/version"))
                .containsExactly("1.0.0", "1.1.0")
            assertThat(metadataXml.singleValue("/metadata/versioning/lastUpdated")).matches("\\d{14}")

            assertThat(requests.keys)
                .contains(
                    "PUT /repository/releases/$metadataPath.md5",
                    "PUT /repository/releases/$metadataPath.sha1",
                    "PUT /repository/releases/$metadataPath.sha256",
                    "PUT /repository/releases/$metadataPath.sha512",
                )
            assertThat(requests).hasSize(8)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `uploads deployment bundle to maven central target and checks status`() {
        val server = MockWebServer()
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

            server.dispatcher =
                routeRequests(
                    server.url("/"),
                    mapOf(
                        "POST /api/v1/publisher/status?id=deployment-123" to
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
                    ),
                ) { request ->
                    when {
                        request.method == "POST" &&
                            request.path?.startsWith("/api/v1/publisher/upload?") == true &&
                            request.path?.contains("publishingType=AUTOMATIC") == true -> {
                            MockResponse().setResponseCode(201).setBody("deployment-123")
                        }

                        else -> {
                            null
                        }
                    }
                }

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

            val requests = requestsByPath(server)

            val uploadRequest =
                requests.entries
                    .single { (key, _) ->
                        key.startsWith("POST /api/v1/publisher/upload?") && key.contains("publishingType=AUTOMATIC")
                    }.value
                    .single()
            assertThat(uploadRequest.getHeader("Authorization")).isEqualTo("Bearer Y2VudHJhbFVzZXI6Y2VudHJhbFBhc3M=")
            val uploadBody = uploadRequest.body.readUtf8()
            assertThat(uploadBody).contains("example-1.0.0.pom")
            assertThat(uploadBody).contains("example-1.0.0.jar")

            val statusRequest = requests.singleRequest("POST", "/api/v1/publisher/status?id=deployment-123")
            assertThat(statusRequest.getHeader("Authorization")).isEqualTo("Bearer Y2VudHJhbFVzZXI6Y2VudHJhbFBhc3M=")
            assertThat(requests).hasSize(2)
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
    ): PromotionMavenTargetInput = task.project.objects.newInstance(PromotionMavenTargetInput::class.java).apply {
        this.repoName.set(repoName)
        this.kind.set(kind.name)
        this.url.set(url)
        this.username.set(username)
        this.password.set(password)
        this.artifactPaths.set(artifactPaths)
    }

    private fun routeRequests(
        baseUrl: HttpUrl,
        responses: Map<String, MockResponse>,
        dynamicResponse: (RecordedRequest) -> MockResponse? = { null },
    ): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val key = "${request.method} ${request.path}"
            return responses[key] ?: dynamicResponse(request) ?: MockResponse()
                .setResponseCode(500)
                .setBody("Unexpected request for ${baseUrl.encodedPath}: $key")
        }
    }

    private fun requestsByPath(server: MockWebServer): Map<String, List<RecordedRequest>> =
        drainRequests(server).groupBy { "${it.method} ${it.path}" }

    private fun Map<String, List<RecordedRequest>>.singleRequest(method: String, path: String): RecordedRequest = get("$method $path")
        .also {
            assertThat(it)
                .withFailMessage("Expected exactly one request for %s %s but found %s", method, path, it?.size ?: 0)
                .hasSize(1)
        }!!
        .single()

    private fun drainRequests(server: MockWebServer): List<RecordedRequest> = generateSequence {
        server.takeRequest(100, TimeUnit.MILLISECONDS)
    }.toList()

    private fun parseXml(xml: String): Document = DocumentBuilderFactory
        .newInstance()
        .apply {
            isNamespaceAware = false
        }.newDocumentBuilder()
        .parse(InputSource(StringReader(xml)))

    private fun Document.singleValue(expression: String): String = values(expression).single()

    private fun Document.values(expression: String): List<String> {
        val nodes = XPathFactory.newInstance().newXPath().evaluate(expression, this, XPathConstants.NODESET) as org.w3c.dom.NodeList
        return (0 until nodes.length).map { index -> nodes.item(index).textContent.trim() }
    }
}
