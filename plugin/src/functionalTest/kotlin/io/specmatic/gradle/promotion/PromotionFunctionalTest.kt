package io.specmatic.gradle.promotion

import io.specmatic.gradle.AbstractFunctionalTest
import io.specmatic.gradle.release.execGit
import java.io.File
import java.security.MessageDigest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.CleanupMode
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory

class PromotionFunctionalTest : AbstractFunctionalTest() {
    @TempDir(cleanup = CleanupMode.ON_SUCCESS)
    lateinit var remoteRepoDir: File

    private val logger = LoggerFactory.getLogger(javaClass)

    @BeforeEach
    fun setup() {
        buildFile.writeText(
            """
            plugins {
                id("java")
                kotlin("jvm") version "2.4.21"
                id("io.specmatic.gradle")
            }

            repositories {
                mavenCentral()
            }

            dependencies {
                implementation("org.slf4j:slf4j-api:2.0.20")
            }

            specmatic {
                withOSSLibrary(rootProject) {
                    publishTo("staging", file("build/mvn-repo").toURI(), io.specmatic.gradle.extensions.RepoType.PUBLISH_ALL)
                    promote()
                }

                promotion {
                    sourceMavenRepository("staging", file("build/mvn-repo").toURI().toString())
                    targetMavenRepository("release", file("build/promoted-repo").toURI().toString())
                }
            }
            """.trimIndent(),
        )

        writeMainClass(projectDir, "io.specmatic.example.Main")

        remoteRepoDir.execGit(logger, "init", "--bare", "--initial-branch=main")
        projectDir.execGit(logger, "add", ".")
        projectDir.execGit(logger, "commit", "-m", "Initial commit")
        projectDir.execGit(logger, "remote", "add", "origin", remoteRepoDir.absolutePath)
        projectDir.execGit(logger, "push", "-u", "origin", "main")
    }

    @Test
    fun `promote artifacts fails when published git sha does not match current head`() {
        runWithSuccess("publishAllPublicationsToStagingRepository")
        addSignaturesToPublishedArtifacts()
        val publishedGitSha = projectDir.execGit(logger, "rev-parse", "HEAD").outputUTF8().trim()

        projectDir.resolve("marker.txt").writeText("force a new commit after publish")
        projectDir.execGit(logger, "add", "marker.txt")
        projectDir.execGit(logger, "commit", "-m", "Commit after publish")
        val currentGitSha = projectDir.execGit(logger, "rev-parse", "HEAD").outputUTF8().trim()

        val result =
            runWithFailure(
                "promoteArtifacts",
                "-PskipRepoDirtyCheck=true",
                "-PskipIncomingOutgoingCheck=true",
            )

        assertThat(currentGitSha).isNotEqualTo(publishedGitSha)
        assertThat(result.output).contains("Execution failed for task ':verifyPromotionMavenArtifacts'")
        assertThat(result.output).contains("x-specmatic-git-sha=$publishedGitSha, expected $currentGitSha")
        assertThat(projectDir.resolve("build/promoted-repo")).doesNotExist()
    }

    @Test
    fun `promote artifacts validates signature checksums when present`() {
        runWithSuccess("publishAllPublicationsToStagingRepository")
        addSignaturesToPublishedArtifacts(includeSignatureChecksums = true)
        val signature =
            projectDir.resolve("build/mvn-repo")
                .walkTopDown()
                .first { it.isFile && it.name.endsWith(".jar.asc") }
        File("${signature.absolutePath}.sha256").writeText("incorrect-checksum")

        val result =
            runWithFailure(
                "promoteArtifacts",
                "-PskipRepoDirtyCheck=true",
                "-PskipIncomingOutgoingCheck=true",
            )

        assertThat(result.output)
            .contains("Checksum mismatch for")
            .contains("${signature.name} using SHA-256: expected incorrect-checksum")
    }

    @Test
    fun `publishes all artifacts privately but promotes only obfuscated artifacts`() {
        buildFile.writeText(
            """
            plugins {
                id("java")
                kotlin("jvm") version "2.4.21"
                id("io.specmatic.gradle")
            }

            repositories { mavenCentral() }
            dependencies { implementation("org.slf4j:slf4j-api:2.0.20") }

            specmatic {
                withCommercialLibrary(rootProject) {
                    publishTo("privateArtifacts", file("build/private-repo").toURI(), io.specmatic.gradle.extensions.RepoType.PUBLISH_ALL)
                    promote()
                }
                promotion {
                    sourceMavenRepository("staging", file("build/mvn-repo").toURI().toString())
                    targetMavenRepository("release", file("build/promoted-repo").toURI().toString(), io.specmatic.gradle.extensions.RepoType.PUBLISH_OBFUSCATED_ONLY)
                }
            }
            """.trimIndent(),
        )
        assertCommercialPromotion(
            allArtifactCoordinates = setOf(
                "io.specmatic.example:example-project-min:1.2.3",
                "io.specmatic.example:example-project-dont-use-this-unless-you-know-what-you-are-doing:1.2.3",
                "io.specmatic.example:example-project-all-debug:1.2.3",
                "io.specmatic.example:example-project:1.2.3",
            ),
            obfuscatedArtifactCoordinates = setOf(
                "io.specmatic.example:example-project-min:1.2.3",
                "io.specmatic.example:example-project:1.2.3",
            ),
        )
    }

    @Test
    fun `promotes commercial application artifacts in single module builds`() {
        buildFile.writeText(
            """
            plugins {
                id("java")
                kotlin("jvm") version "2.4.21"
                id("io.specmatic.gradle")
            }

            repositories { mavenCentral() }
            dependencies { implementation("org.slf4j:slf4j-api:2.0.20") }

            specmatic {
                withCommercialApplication(rootProject) {
                    mainClass = "io.specmatic.example.Main"
                    publishTo("privateArtifacts", file("build/private-repo").toURI(), io.specmatic.gradle.extensions.RepoType.PUBLISH_ALL)
                    promote()
                }
                promotion {
                    sourceMavenRepository("staging", file("build/mvn-repo").toURI().toString())
                    targetMavenRepository("release", file("build/promoted-repo").toURI().toString(), io.specmatic.gradle.extensions.RepoType.PUBLISH_OBFUSCATED_ONLY)
                }
            }
            """.trimIndent(),
        )
        assertCommercialPromotion(
            allArtifactCoordinates = setOf(
                "io.specmatic.example:example-project:1.2.3",
                "io.specmatic.example:example-project-all-debug:1.2.3",
            ),
            obfuscatedArtifactCoordinates = setOf("io.specmatic.example:example-project:1.2.3"),
        )
    }

    @Test
    fun `promotes commercial application artifacts in multi module builds`() {
        settingsFile.writeText("rootProject.name = \"example-project\"\ninclude(\"core\", \"executable\")")
        projectDir.resolve(".gitignore").appendText("\n**/src/main/gen-kt/\n**/src/main/gen-resources/\n")
        buildFile.writeText(
            """
            plugins {
                id("java")
                kotlin("jvm") version "2.4.21"
                id("io.specmatic.gradle")
            }

            subprojects {
                apply(plugin = "java")
                apply(plugin = "org.jetbrains.kotlin.jvm")
                repositories { mavenCentral() }
                dependencies { implementation("org.slf4j:slf4j-api:2.0.20") }
            }

            specmatic {
                withCommercialLibrary(project(":core")) {
                    publishTo("privateArtifacts", rootProject.file("build/private-repo").toURI(), io.specmatic.gradle.extensions.RepoType.PUBLISH_ALL)
                    promote()
                }
                withCommercialApplication(project(":executable")) {
                    mainClass = "io.specmatic.example.executable.Main"
                    publishTo("privateArtifacts", rootProject.file("build/private-repo").toURI(), io.specmatic.gradle.extensions.RepoType.PUBLISH_ALL)
                    promote()
                }
                promotion {
                    sourceMavenRepository("staging", rootProject.file("build/mvn-repo").toURI().toString())
                    targetMavenRepository("release", rootProject.file("build/promoted-repo").toURI().toString(), io.specmatic.gradle.extensions.RepoType.PUBLISH_OBFUSCATED_ONLY)
                }
            }

            project(":executable") { dependencies { implementation(project(":core")) } }
            """.trimIndent(),
        )
        writeRandomClasses(projectDir.resolve("core"), "io.specmatic.example.core.internal")
        writeRandomClasses(projectDir.resolve("executable"), "io.specmatic.example.executable.internal")
        writeMainClass(projectDir.resolve("executable"), "io.specmatic.example.executable.Main")
        assertCommercialPromotion(
            allArtifactCoordinates = setOf(
                "io.specmatic.example:core-min:1.2.3",
                "io.specmatic.example:core-dont-use-this-unless-you-know-what-you-are-doing:1.2.3",
                "io.specmatic.example:core-all-debug:1.2.3",
                "io.specmatic.example:core:1.2.3",
                "io.specmatic.example:executable:1.2.3",
                "io.specmatic.example:executable-all-debug:1.2.3",
            ),
            obfuscatedArtifactCoordinates = setOf(
                "io.specmatic.example:core-min:1.2.3",
                "io.specmatic.example:core:1.2.3",
                "io.specmatic.example:executable:1.2.3",
            ),
        )
    }

    @Test
    fun `promotes commercial library artifacts in multi module builds`() {
        settingsFile.writeText("rootProject.name = \"example-project\"\ninclude(\"core\", \"executable\")")
        projectDir.resolve(".gitignore").appendText("\n**/src/main/gen-kt/\n**/src/main/gen-resources/\n")
        buildFile.writeText(
            """
            plugins {
                id("java")
                kotlin("jvm") version "2.4.21"
                id("io.specmatic.gradle")
            }

            subprojects {
                apply(plugin = "java")
                apply(plugin = "org.jetbrains.kotlin.jvm")
                repositories { mavenCentral() }
                dependencies { implementation("org.slf4j:slf4j-api:2.0.20") }
            }

            specmatic {
                withCommercialLibrary(project(":core")) {
                    publishTo("privateArtifacts", rootProject.file("build/private-repo").toURI(), io.specmatic.gradle.extensions.RepoType.PUBLISH_ALL)
                    promote()
                }
                withCommercialLibrary(project(":executable")) {
                    publishTo("privateArtifacts", rootProject.file("build/private-repo").toURI(), io.specmatic.gradle.extensions.RepoType.PUBLISH_ALL)
                    promote()
                }
                promotion {
                    sourceMavenRepository("staging", rootProject.file("build/mvn-repo").toURI().toString())
                    targetMavenRepository("release", rootProject.file("build/promoted-repo").toURI().toString(), io.specmatic.gradle.extensions.RepoType.PUBLISH_OBFUSCATED_ONLY)
                }
            }

            project(":executable") { dependencies { implementation(project(":core")) } }
            """.trimIndent(),
        )
        writeRandomClasses(projectDir.resolve("core"), "io.specmatic.example.core.internal")
        writeRandomClasses(projectDir.resolve("executable"), "io.specmatic.example.executable.internal")
        assertCommercialPromotion(
            allArtifactCoordinates = setOf(
                "io.specmatic.example:core-min:1.2.3",
                "io.specmatic.example:core-dont-use-this-unless-you-know-what-you-are-doing:1.2.3",
                "io.specmatic.example:core-all-debug:1.2.3",
                "io.specmatic.example:core:1.2.3",
                "io.specmatic.example:executable-min:1.2.3",
                "io.specmatic.example:executable-dont-use-this-unless-you-know-what-you-are-doing:1.2.3",
                "io.specmatic.example:executable-all-debug:1.2.3",
                "io.specmatic.example:executable:1.2.3",
            ),
            obfuscatedArtifactCoordinates = setOf(
                "io.specmatic.example:core-min:1.2.3",
                "io.specmatic.example:core:1.2.3",
                "io.specmatic.example:executable-min:1.2.3",
                "io.specmatic.example:executable:1.2.3",
            ),
        )
    }

    @Test
    fun `promotes commercial application library artifacts in single module builds`() {
        buildFile.writeText(
            """
            plugins {
                id("java")
                kotlin("jvm") version "2.4.21"
                id("io.specmatic.gradle")
            }

            repositories { mavenCentral() }
            dependencies { implementation("org.slf4j:slf4j-api:2.0.20") }

            specmatic {
                withCommercialApplicationLibrary(rootProject) {
                    mainClass = "io.specmatic.example.Main"
                    publishTo("privateArtifacts", file("build/private-repo").toURI(), io.specmatic.gradle.extensions.RepoType.PUBLISH_ALL)
                    promote()
                }
                promotion {
                    sourceMavenRepository("staging", file("build/mvn-repo").toURI().toString())
                    targetMavenRepository("release", file("build/promoted-repo").toURI().toString(), io.specmatic.gradle.extensions.RepoType.PUBLISH_OBFUSCATED_ONLY)
                }
            }
            """.trimIndent(),
        )
        assertCommercialPromotion(
            allArtifactCoordinates = setOf(
                "io.specmatic.example:example-project-all:1.2.3",
                "io.specmatic.example:example-project:1.2.3",
                "io.specmatic.example:example-project-dont-use-this-unless-you-know-what-you-are-doing:1.2.3",
                "io.specmatic.example:example-project-all-debug:1.2.3",
            ),
            obfuscatedArtifactCoordinates = setOf(
                "io.specmatic.example:example-project-all:1.2.3",
                "io.specmatic.example:example-project:1.2.3",
            ),
        )
    }

    @Test
    fun `promotes commercial application library artifacts in multi module builds`() {
        settingsFile.writeText("rootProject.name = \"example-project\"\ninclude(\"core\", \"executable\")")
        projectDir.resolve(".gitignore").appendText("\n**/src/main/gen-kt/\n**/src/main/gen-resources/\n")
        buildFile.writeText(
            """
            plugins {
                id("java")
                kotlin("jvm") version "2.4.21"
                id("io.specmatic.gradle")
            }

            subprojects {
                apply(plugin = "java")
                apply(plugin = "org.jetbrains.kotlin.jvm")
                repositories { mavenCentral() }
                dependencies { implementation("org.slf4j:slf4j-api:2.0.20") }
            }

            specmatic {
                withCommercialLibrary(project(":core")) {
                    publishTo("privateArtifacts", rootProject.file("build/private-repo").toURI(), io.specmatic.gradle.extensions.RepoType.PUBLISH_ALL)
                    promote()
                }
                withCommercialApplicationLibrary(project(":executable")) {
                    mainClass = "io.specmatic.example.executable.Main"
                    publishTo("privateArtifacts", rootProject.file("build/private-repo").toURI(), io.specmatic.gradle.extensions.RepoType.PUBLISH_ALL)
                    promote()
                }
                promotion {
                    sourceMavenRepository("staging", rootProject.file("build/mvn-repo").toURI().toString())
                    targetMavenRepository("release", rootProject.file("build/promoted-repo").toURI().toString(), io.specmatic.gradle.extensions.RepoType.PUBLISH_OBFUSCATED_ONLY)
                }
            }

            project(":executable") { dependencies { implementation(project(":core")) } }
            """.trimIndent(),
        )
        writeRandomClasses(projectDir.resolve("core"), "io.specmatic.example.core.internal")
        writeRandomClasses(projectDir.resolve("executable"), "io.specmatic.example.executable.internal")
        writeMainClass(projectDir.resolve("executable"), "io.specmatic.example.executable.Main")
        assertCommercialPromotion(
            allArtifactCoordinates = setOf(
                "io.specmatic.example:core-min:1.2.3",
                "io.specmatic.example:core-dont-use-this-unless-you-know-what-you-are-doing:1.2.3",
                "io.specmatic.example:core-all-debug:1.2.3",
                "io.specmatic.example:core:1.2.3",
                "io.specmatic.example:executable-all:1.2.3",
                "io.specmatic.example:executable:1.2.3",
                "io.specmatic.example:executable-dont-use-this-unless-you-know-what-you-are-doing:1.2.3",
                "io.specmatic.example:executable-all-debug:1.2.3",
            ),
            obfuscatedArtifactCoordinates = setOf(
                "io.specmatic.example:core-min:1.2.3",
                "io.specmatic.example:core:1.2.3",
                "io.specmatic.example:executable-all:1.2.3",
                "io.specmatic.example:executable:1.2.3",
            ),
        )
    }

    private fun assertCommercialPromotion(
        allArtifactCoordinates: Set<String>,
        obfuscatedArtifactCoordinates: Set<String>,
    ) {
        projectDir.execGit(logger, "add", ".")
        projectDir.execGit(logger, "commit", "-m", "Configure commercial promotion fixture")
        projectDir.execGit(logger, "push", "origin", "main")

        runWithSuccess(
            "publishAllPublicationsToStagingRepository",
            "publishAllPublicationsToPrivateArtifactsRepository",
            "-PgenerateAllJars=true",
        )
        addSignaturesToPublishedArtifacts()
        runWithSuccess(
            "promoteArtifacts",
            "-PgenerateAllJars=true",
        )

        assertThat(projectDir.resolve("build/private-repo").getPublishedArtifactCoordinates())
            .containsExactlyInAnyOrderElementsOf(allArtifactCoordinates)
        assertThat(projectDir.resolve("build/promoted-repo").getPublishedArtifactCoordinates())
            .containsExactlyInAnyOrderElementsOf(obfuscatedArtifactCoordinates)
    }

    private fun addSignaturesToPublishedArtifacts(includeSignatureChecksums: Boolean = false) {
        val repository = projectDir.resolve("build/mvn-repo")
        val artifacts =
            repository
                .walkTopDown()
                .filter { file ->
                    file.isFile && (file.extension == "jar" || file.extension == "pom")
                }.toList()

        artifacts.forEach { artifact ->
            val signature = File("${artifact.absolutePath}.asc")
            signature.writeText("test signature for ${artifact.name}")
        }

        val filesToChecksum = if (includeSignatureChecksums) artifacts + artifacts.map { File("${it.absolutePath}.asc") } else artifacts
        filesToChecksum.forEach { artifact ->
            TEST_CHECKSUMS.forEach { (extension, algorithm) ->
                File("${artifact.absolutePath}.$extension").writeText(artifact.checksum(algorithm))
            }
        }
    }

    companion object {
        private val TEST_CHECKSUMS =
            listOf(
                "md5" to "MD5",
                "sha1" to "SHA-1",
                "sha256" to "SHA-256",
                "sha512" to "SHA-512",
            )
    }
}

private fun File.checksum(algorithm: String): String {
    val digest = MessageDigest.getInstance(algorithm)
    inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}
