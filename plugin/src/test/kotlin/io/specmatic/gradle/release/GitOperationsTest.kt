package io.specmatic.gradle.release

import java.io.File
import java.io.FileNotFoundException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.gradle.api.GradleException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.CleanupMode
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory

class GitOperationsTest {
    private val logger = LoggerFactory.getLogger("temp")

    @TempDir(cleanup = CleanupMode.ON_SUCCESS)
    lateinit var gitRepo: File

    @TempDir(cleanup = CleanupMode.ON_SUCCESS)
    lateinit var remoteRepoDir: File

    lateinit var initialCommitId: String

    @BeforeEach
    fun setUp() {
        remoteRepoDir.execGit(logger, "init", "--initial-branch=main")
        remoteRepoDir.resolve("README.md").writeText("dummy content")
        remoteRepoDir.execGit(logger, "add", "README.md")
        remoteRepoDir.execGit(logger, "commit", "-m", "Initial commit")
        initialCommitId = remoteRepoDir.execGit(logger, "rev-parse", "HEAD").outputUTF8().trim()
        remoteRepoDir.execGit(logger, "config", "receive.denyCurrentBranch", "updateInstead")

        File(".").execGit(
            logger,
            "clone",
            remoteRepoDir.absolutePath,
            gitRepo.absolutePath,
        )
    }

    @Nested
    inner class MakeGitTag {
        @Test
        fun `makeGitTag should create a new tag when it does not already exist`() {
            val gitOperations = GitOperations(gitRepo, emptyMap(), logger)

            gitOperations.makeGitTag("v1.0.0")
            assertThat(gitRepo.execGit(logger, "tag").outputUTF8()).contains("v1.0.0")
        }

        @Test
        fun `makeGitTag should throw exception when tag already exists`() {
            val gitOperations = GitOperations(gitRepo, emptyMap(), logger)
            gitOperations.makeGitTag("v1.0.0")

            assertThatCode {
                gitOperations.makeGitTag("v1.0.0")
            }.isInstanceOf(GradleException::class.java)
                .hasMessage("Tag v1.0.0 already exists. Please delete the tag and try again.")
        }
    }

    @Nested
    inner class CommitGradleProperties {
        @Test
        fun `commitGradleProperties should create a commit with the correct message`() {
            val gitOperations = GitOperations(gitRepo, emptyMap(), logger)

            val gradlePropertiesFile = gitRepo.resolve("gradle.properties")
            gradlePropertiesFile.writeText("version=1.0.0-SNAPSHOT")

            gitOperations.commitGradleProperties("1.0.0", CommitType.POST_RELEASE)

            gitRepo.execGit(logger, "log", "-2", "--pretty=format:%s").outputUTF8().lines().let { log ->
                assertThat(log).contains("chore(release): post-release bump version 1.0.0")
                assertThat(log).hasSize(2)
            }
        }

        @Test
        fun `commitGradleProperties throws error if gradle properties file is not found`() {
            val gitOperations = GitOperations(gitRepo, emptyMap(), logger)

            assertThatCode {
                gitOperations.commitGradleProperties("1.0.0", CommitType.POST_RELEASE)
            }.isInstanceOf(RuntimeException::class.java)
                .hasMessageContaining("pathspec 'gradle.properties' did not match any files")
        }
    }

    @Nested
    inner class AssertMainBranch {
        @Test
        fun `assertMainBranch should not throw exception when on main branch`() {
            val gitOperations = GitOperations(gitRepo, emptyMap(), logger)

            assertThatCode {
                gitOperations.assertMainBranch()
            }.doesNotThrowAnyException()
        }

        @Test
        fun `assertMainBranch should throw exception when not on main branch`() {
            gitRepo.execGit(logger, "checkout", "-b", "feature-branch")

            val gitOperations = GitOperations(gitRepo, emptyMap(), logger)

            assertThatCode {
                gitOperations.assertMainBranch()
            }.isInstanceOf(GradleException::class.java)
                .hasMessage(
                    "You are not on the main branch. Please switch to the main branch to create a release. Run with `-PskipBranchCheck=true` to disable.",
                )
        }

        @Test
        fun `assertMainBranch should skip check when skipBranchCheck is true`() {
            gitRepo.execGit(logger, "checkout", "-b", "feature-branch")

            val gitOperations =
                GitOperations(gitRepo, mapOf("skipBranchCheck" to "true"), logger)

            assertThatCode {
                gitOperations.assertMainBranch()
            }.doesNotThrowAnyException()
        }
    }

    @Nested
    inner class AssertRepoNotDirty {
        @Test
        fun `assertRepoNotDirty should not throw exception when repo is clean`() {
            val gitOperations = GitOperations(gitRepo, emptyMap(), logger)

            assertThatCode {
                gitOperations.assertRepoNotDirty()
            }.doesNotThrowAnyException()
        }

        @Test
        fun `assertRepoNotDirty should throw exception when repo is dirty`() {
            gitRepo.resolve("dirtyFile.txt").writeText("unsaved changes")

            val gitOperations = GitOperations(gitRepo, emptyMap(), logger)

            assertThatCode {
                gitOperations.assertRepoNotDirty()
            }.isInstanceOf(GradleException::class.java)
                .hasMessage(
                    "Repo is dirty. Please commit or stash your changes before creating a release. Run with `-PskipRepoDirtyCheck=true` to disable.",
                )
        }

        @Test
        fun `assertRepoNotDirty should skip check when skipRepoDirtyCheck is true`() {
            gitRepo.resolve("dirtyFile.txt").writeText("unsaved changes")

            val gitOperations =
                GitOperations(gitRepo, mapOf("skipRepoDirtyCheck" to "true"), logger)

            assertThatCode {
                gitOperations.assertRepoNotDirty()
            }.doesNotThrowAnyException()
        }
    }

    @Nested
    inner class PreReleaseBump {
        @Test
        fun `should bump version before release`() {
            val gradlePropertiesFile = gitRepo.resolve("gradle.properties")
            gradlePropertiesFile.writeText(
                """
                version=1.0.0-SNAPSHOT
                fooVersion=1.2.3
                bar.version=4.5.6
                """.trimIndent(),
            )

            val gitOperations =
                GitOperations(gitRepo, mapOf("version" to "1.0.0-SNAPSHOT"), logger)

            gitOperations.preReleaseGitCommit("1.1.0")

            val updatedContent = gradlePropertiesFile.readText()
            assertThat(updatedContent).isEqualTo(
                """
                version=1.1.0
                fooVersion=1.2.3
                bar.version=4.5.6
                """.trimIndent(),
            )

            val lastCommit =
                gitRepo
                    .execGit(logger, "log", "-1", "--pretty=format:%s")
                    .outputUTF8()
                    .lines()
                    .first()
            assertThat(lastCommit).isEqualTo("chore(release): pre-release bump version 1.1.0")
            assertThat(gitRepo.execGit(logger, "tag").outputUTF8().trim()).isEmpty()
        }

        @Test
        fun `should not bump version before release if version is not changed`() {
            val gradlePropertiesFile = gitRepo.resolve("gradle.properties")
            gradlePropertiesFile.writeText("version=1.0.0")

            gitRepo.execGit(logger, "add", "gradle.properties")
            gitRepo.execGit(logger, "commit", "-m", "Setup version")

            val gitOperations = GitOperations(gitRepo, mapOf("version" to "1.0.0"), logger)

            assertThatCode {
                gitOperations.preReleaseGitCommit("1.0.0")
            }.doesNotThrowAnyException()

            val unchangedContent = gradlePropertiesFile.readText()
            assertThat(unchangedContent).isEqualTo("version=1.0.0")

            assertThat(
                gitRepo
                    .execGit(logger, "log", "--oneline")
                    .outputUTF8()
                    .trim()
                    .lines(),
            ).hasSize(2)
            assertThat(gitRepo.execGit(logger, "tag").outputUTF8().trim()).isEmpty()
        }
    }

    @Nested
    inner class PostReleaseBump {
        @Test
        fun `should update version in gradle properties`() {
            val gradlePropertiesFile = gitRepo.resolve("gradle.properties")
            gradlePropertiesFile.writeText(
                """
                version=1.0.0
                fooVersion=1.2.3
                bar.version=4.5.6
                """.trimIndent(),
            )

            val gitOperations = GitOperations(gitRepo, emptyMap(), logger)

            gitOperations.postReleaseBump("1.1.0-SNAPSHOT")

            val updatedContent = gradlePropertiesFile.readText()
            assertThat(updatedContent).isEqualTo(
                """
                version=1.1.0-SNAPSHOT
                fooVersion=1.2.3
                bar.version=4.5.6
                """.trimIndent(),
            )

            val commits = gitRepo.execGit(logger, "log", "--pretty=format:%s").outputUTF8().lines()
            assertThat(commits).hasSize(2)
            assertThat(commits.first()).isEqualTo("chore(release): post-release bump version 1.1.0-SNAPSHOT")
        }

        @Test
        fun `should throw exception when gradle properties file is missing`() {
            val gitOperations = GitOperations(gitRepo, emptyMap(), logger)

            assertThatCode {
                gitOperations.postReleaseBump("1.1.0-SNAPSHOT")
            }.isInstanceOf(FileNotFoundException::class.java)
                .hasMessageContaining("gradle.properties (No such file or directory)")

            assertThat(
                gitRepo
                    .execGit(logger, "log", "--oneline")
                    .outputUTF8()
                    .trim()
                    .lines(),
            ).hasSize(1)
        }
    }

    @Nested
    inner class AssertNoIncomingOrOutgoingChanges {
        @Test
        fun `should not throw exception when there are no incoming or outgoing changes`() {
            val gitOperations = GitOperations(gitRepo, emptyMap(), logger)

            assertThatCode {
                gitOperations.assertNoIncomingOrOutgoingChanges()
            }.doesNotThrowAnyException()
        }

        @Test
        fun `should throw exception when there are incoming changes`() {
//            remoteRepoDir.withGit {
            remoteRepoDir.resolve("newFile.txt").writeText("new content")
            remoteRepoDir.execGit(logger, "add", "newFile.txt")
            remoteRepoDir.execGit(logger, "commit", "-m", "Incoming commit")
//            it.add().addFilepattern("newFile.txt").call()
//            it.commit().setMessage("Incoming commit").call()
//            }

            val gitOperations = GitOperations(gitRepo, emptyMap(), logger)

            assertThatCode {
                gitOperations.assertNoIncomingOrOutgoingChanges()
            }.isInstanceOf(GradleException::class.java)
                .hasMessageContaining("Repo is behind remote")
        }

        @Test
        fun `should throw exception when there are outgoing changes`() {
            gitRepo.resolve("newFile.txt").writeText("new content")
            gitRepo.execGit(logger, "add", "newFile.txt")
            gitRepo.execGit(logger, "commit", "-m", "Outgoing commit")

            val gitOperations = GitOperations(gitRepo, emptyMap(), logger)

            assertThatCode {
                gitOperations.assertNoIncomingOrOutgoingChanges()
            }.isInstanceOf(GradleException::class.java)
                .hasMessageContaining("Repo is ahead of remote")
        }

        @Test
        fun `should skip check when skipIncomingOutgoingCheck is true`() {
            gitRepo.resolve("newFile.txt").writeText("new content")
            gitRepo.execGit(logger, "add", "newFile.txt")
            gitRepo.execGit(logger, "commit", "-m", "Outgoing commit")
            gitRepo.execGit(logger, "fetch", "origin")

            val gitOperations =
                GitOperations(gitRepo, mapOf("skipIncomingOutgoingCheck" to "true"), logger)

            assertThatCode {
                gitOperations.assertNoIncomingOrOutgoingChanges()
            }.doesNotThrowAnyException()
        }
    }

    @Nested
    inner class Push {
        @Test
        fun `should push changes to remote repository`() {
            gitRepo.resolve("newFile.txt").writeText("new content")
            gitRepo.execGit(logger, "add", "newFile.txt")
            gitRepo.execGit(logger, "commit", "-m", "New commit")
            gitRepo.execGit(logger, "tag", "-a", "1.0.0", "-m", "bump to 1.0.0")

            val gitOperations = GitOperations(gitRepo, emptyMap(), logger)

            assertThatCode {
                gitOperations.push()
            }.doesNotThrowAnyException()

            assertThat(remoteRepoDir.execGit(logger, "log", "--pretty=format:%s").outputUTF8().lines())
                .hasSize(2)
                .element(0)
                .isEqualTo("New commit")

            assertThat(
                remoteRepoDir
                    .execGit(logger, "tag")
                    .outputUTF8()
                    .trim()
                    .lines(),
            ).contains("1.0.0")
        }

        @Test
        fun `should not throw exception when there are no changes to push`() {
            val gitOperations = GitOperations(gitRepo, emptyMap(), logger)

            gitOperations.push()

            assertThat(
                remoteRepoDir
                    .execGit(logger, "log", "--oneline")
                    .outputUTF8()
                    .trim()
                    .lines(),
            ).hasSize(1)
        }
    }

    @Nested
    inner class ResetHardTo {
        @Test
        fun `should reset to the specified commit`() {
            gitRepo.resolve("newFile.txt").writeText("New content")
            gitRepo.execGit(logger, "add", "newFile.txt")
            gitRepo.execGit(logger, "commit", "-m", "New commit")

            val gitOperations = GitOperations(gitRepo, emptyMap(), logger)
            gitOperations.resetHardTo(initialCommitId)

            val log =
                gitRepo
                    .execGit(logger, "log", "--oneline")
                    .outputUTF8()
                    .trim()
                    .lines()
            assertThat(log).hasSize(1)
        }

        @Test
        fun `should not throw exception when resetting to the current commit`() {
            val gitOperations = GitOperations(gitRepo, emptyMap(), logger)

            assertThatCode {
                gitOperations.resetHardTo(initialCommitId)
            }.doesNotThrowAnyException()

            val logLines =
                gitRepo
                    .execGit(logger, "log", "--oneline", "--no-abbrev-commit")
                    .outputUTF8()
                    .trim()
                    .lines()
            assertThat(logLines)
                .hasSize(1)
            assertThat(logLines.first()).startsWith(initialCommitId)
        }
    }
}
