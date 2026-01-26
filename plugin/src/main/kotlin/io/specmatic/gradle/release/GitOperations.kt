package io.specmatic.gradle.release

import io.specmatic.gradle.downstreamprojects.replacePropertyValue
import java.io.File
import java.util.concurrent.TimeUnit
import org.gradle.api.GradleException
import org.slf4j.Logger
import org.zeroturnaround.exec.ProcessExecutor
import org.zeroturnaround.exec.ProcessResult

enum class CommitType(private val messageSubstring: String) {
    PRE_RELEASE("pre-release"),
    POST_RELEASE("post-release");

    fun commitMessage(version: String): String = "chore(release): $messageSubstring bump version $version"
}

fun File.execGit(logger: Logger, vararg args: String, quiet: Boolean = false): ProcessResult {
    val processExecutor =
        ProcessExecutor()
            .command("git", *args)
            .directory(this)
            .readOutput(true)
            .timeout(30, TimeUnit.SECONDS)
            .destroyOnExit()

    return processExecutor.execute().also {
        if (it.exitValue == 0) {
            if (!quiet) {
                logger.warn(
                    "Command '${processExecutor.command}' executed successfully in directory $this. Output was: ${
                        it.outputUTF8().ifEmpty { "(blank)" }
                    } ",
                )
            }
        } else {
            throw RuntimeException(
                "Command '${processExecutor.command}' failed in directory $this with exit code ${it.exitValue}. Output was: ${
                    it.outputUTF8().ifEmpty { "(blank)" }
                }",
            )
        }
    }
}

class GitOperations(private val rootDir: File, private val projectProperties: Map<String, Any?>, private val logger: Logger) {
    fun makeGitTag(newVersion: String) {
        val tagList = localGitTags() + extractRemoteTags()

        if (tagList.contains(newVersion)) {
            throw GradleException("Tag $newVersion already exists. Please delete the tag and try again.")
        }

        rootDir.execGit(logger, "tag", "-a", newVersion, "-m", "Release version $newVersion")
    }

    private fun localGitTags(): List<String> = rootDir
        .execGit(logger, "tag", quiet = true)
        .outputUTF8()
        .lines()
        .map { it.trim() }

    private fun extractRemoteTags(): List<String> =
        rootDir.execGit(logger, "ls-remote", "--tags", quiet = true).outputUTF8().lines().mapNotNull { line ->
            val parts = line.split("\t")
            if (parts.size > 1 && parts[1].startsWith("refs/tags/")) {
                parts[1].removePrefix("refs/tags/")
            } else {
                null
            }
        }

    fun commitGradleProperties(newVersion: String, commitType: CommitType) {
        val commitMessage = commitType.commitMessage(newVersion)

        rootDir.execGit(logger, "add", "gradle.properties")
        rootDir.execGit(logger, "commit", "-m", commitMessage)

        logger.warn("Created commit with message: $commitMessage")
    }

    fun assertMainBranch() {
        if (projectProperties["skipBranchCheck"] == "true") {
            logger.warn("Skipping branch check as per user request.")
            return
        }

        val currentBranch =
            rootDir
                .execGit(logger, "branch", "--no-color", "--show-current")
                .outputUTF8()
                .lines()
                .first()
                .trim()
        logger.warn("Current branch: $currentBranch")

        if (currentBranch != "main") {
            throw GradleException(
                "You are not on the main branch. Please switch to the main branch to create a release. Run with `-PskipBranchCheck=true` to disable."
            )
        }
    }

    fun assertRepoNotDirty() {
//        rootDir.withGit { git ->
        if (projectProperties["skipRepoDirtyCheck"] == "true") {
            logger.warn("Skipping repo check as per user request.")
            return
        }

        logger.warn("Checking if repo is clean...")
        val status = rootDir.execGit(logger, "status", "--porcelain").outputUTF8()
        if (status.isNotEmpty()) {
            logger.warn("Repo is dirty. Status: $status")
            throw GradleException(
                "Repo is dirty. Please commit or stash your changes before creating a release. Run with `-PskipRepoDirtyCheck=true` to disable."
            )
        } else {
            logger.warn("Repo is clean.")
        }

//            val status = git.status().call()
//            if (status.isClean) {
//                logger.warn("Repo is clean")
//            } else {
//                throw GradleException("Repo is dirty. Please commit or stash your changes before creating a release. Run with `-PskipRepoDirtyCheck=true` to disable.")
//            }
//        }
    }

    fun assertNoIncomingOrOutgoingChanges() {
        if (projectProperties["skipIncomingOutgoingCheck"] == "true") {
            logger.warn("Skipping incoming/outgoing changes check as per user request.")
            return
        }

        rootDir.execGit(logger, "remote", "update")

        val status =
            rootDir
                .execGit(logger, "status", "-b", "--porcelain")
                .outputUTF8()
                .lines()
                .first()
        if (status.contains("ahead")) {
            logger.warn("Repo is ahead of remote. Status: $status")
            throw GradleException(
                "Repo is ahead of remote. Please push your changes before creating a release. Run with `-PskipIncomingOutgoingCheck=true` to disable."
            )
        } else if (status.contains("behind")) {
            logger.warn("Repo is behind remote. Status: $status")
            throw GradleException(
                "Repo is behind remote. Please pull the latest changes before creating a release. Run with `-PskipIncomingOutgoingCheck=true` to disable."
            )
        } else {
            logger.warn("No incoming or outgoing changes.")
        }
    }

    fun preReleaseGitCommit(newVersion: String) {
        val currentVersion = projectProperties["version"].toString()

        if (newVersion == currentVersion) {
            logger.warn("Version does not contain -SNAPSHOT, no changes made")
            return
        }

        val gradlePropertiesFile = rootDir.resolve("gradle.properties")
        val newContent = replacePropertyValue(gradlePropertiesFile, "version", newVersion)
        gradlePropertiesFile.writeText(newContent)

        commitGradleProperties(newVersion, CommitType.PRE_RELEASE)
    }

    fun postReleaseBump(newVersion: String) {
        logger.warn("Bumping version to $newVersion")
        val gradlePropertiesFile = rootDir.resolve("gradle.properties")
        val newContent = replacePropertyValue(gradlePropertiesFile, "version", newVersion)
        gradlePropertiesFile.writeText(newContent)
        commitGradleProperties(newVersion, CommitType.POST_RELEASE)
    }

    fun push() {
        logger.warn("Attempting to push branches")
        rootDir.execGit(logger, "push")
        logger.warn("Attempting to push tags")
        rootDir.execGit(logger, "push", "--tags")
    }

    fun resetHardTo(originalGitCommit: String) {
        rootDir.execGit(logger, "reset", "--hard", originalGitCommit)
    }

    fun gitSha(): String = rootDir
        .execGit(logger, "rev-parse", "HEAD", quiet = true)
        .outputUTF8()
        .lines()
        .first()
        .trim()
}
