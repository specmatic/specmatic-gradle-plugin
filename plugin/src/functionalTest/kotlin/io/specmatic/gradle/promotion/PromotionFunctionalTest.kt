package io.specmatic.gradle.promotion

import io.specmatic.gradle.AbstractFunctionalTest
import io.specmatic.gradle.release.execGit
import java.io.File
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
                kotlin("jvm") version "2.3.20"
                id("io.specmatic.gradle")
            }

            repositories {
                mavenCentral()
            }

            dependencies {
                implementation("org.slf4j:slf4j-api:2.0.17")
            }

            specmatic {
                withOSSLibrary(rootProject) {
                    publishTo("staging", file("build/mvn-repo").toURI(), io.specmatic.gradle.extensions.RepoType.PUBLISH_ALL)
                }

                promotion {
                    canonicalMavenRepository(file("build/mvn-repo").toURI().toString())
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
        val publishedGitSha = projectDir.execGit(logger, "rev-parse", "HEAD").outputUTF8().trim()

        projectDir.resolve("marker.txt").writeText("force a new commit after publish")
        projectDir.execGit(logger, "add", "marker.txt")
        projectDir.execGit(logger, "commit", "-m", "Commit after publish")
        val currentGitSha = projectDir.execGit(logger, "rev-parse", "HEAD").outputUTF8().trim()

        val result = runWithFailure(
            "promoteArtifacts",
            "-PskipRepoDirtyCheck=true",
            "-PskipIncomingOutgoingCheck=true",
        )

        assertThat(currentGitSha).isNotEqualTo(publishedGitSha)
        assertThat(result.output).contains("Execution failed for task ':verifyPromotionMavenArtifacts'")
        assertThat(result.output).contains("x-specmatic-git-sha=$publishedGitSha, expected $currentGitSha")
        assertThat(projectDir.resolve("build/promoted-repo")).doesNotExist()
    }
}
