package io.specmatic.gradle

import io.specmatic.gradle.utils.okHttpConnector
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.apache.commons.codec.digest.DigestUtils
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.kohsuke.github.GHRelease
import org.kohsuke.github.GHReleaseBuilder
import org.kohsuke.github.GHRepository
import org.kohsuke.github.GitHubBuilder

@DisableCachingByDefault(because = "Makes network calls")
abstract class CreateGithubReleaseTask : DefaultTask() {
    @get:Internal
    abstract val releaseVersion: Property<String>

    @get:Internal
    abstract val sourceDir: Property<File>

    @get:Input
    abstract val deleteExistingAssets: Property<Boolean>

    init {
        deleteExistingAssets.convention(true)
    }

    @TaskAction
    fun createGithubRelease() {
        val user = (
            System.getenv("SPECMATIC_GITHUB_USER")
                ?: throw GradleException("SPECMATIC_GITHUB_USER environment variable not set")
        )
        val password = (
            System.getenv("SPECMATIC_GITHUB_TOKEN")
                ?: throw GradleException("SPECMATIC_GITHUB_TOKEN environment variable not set")
        )

        val githubApi =
            GitHubBuilder()
                .withEndpoint(System.getenv("GITHUB_API_URL") ?: "https://api.github.com")
                .withPassword(user, password)
                .withConnector(okHttpConnector)
                .build()

        val githubRepo =
            githubApi.getRepository(
                System.getenv("GITHUB_REPOSITORY")
                    ?: throw GradleException("GITHUB_REPOSITORY environment variable not set"),
            )

        val githubRelease = retryGithubRead { findReleaseByName(githubRepo, releaseVersion.get()) } ?: createRelease(githubRepo)
        logger.warn("Created release ${githubRelease.name} with id ${githubRelease.id} at ${githubRelease.htmlUrl}")

        if (deleteExistingAssets.get()) {
            for (asset in githubRelease.listAssets()) {
                asset.delete()
            }
        }

        if (sourceDir.isPresent && sourceDir.get().exists()) {
            sourceDir.get().listFiles().orEmpty().forEach { eachFile ->
                uploadAsset(githubRelease, eachFile)
                logger.warn("[SGP - $path]: Uploaded asset $eachFile")
            }
        }

        githubRelease.update().draft(false).update()
    }

    private fun createRelease(githubRepo: GHRepository): GHRelease {
        val release =
            githubRepo
                .createRelease(releaseVersion.get())
                .makeLatest(GHReleaseBuilder.MakeLatest.TRUE)
                .draft(true)
                .name(releaseVersion.get())
                .create()
        logger.warn("[SGP - $path]: Created draft release ${release.name} with id ${release.id} at ${release.htmlUrl}")
        return release
    }

    @Throws(IOException::class)
    private fun findReleaseByName(repository: GHRepository, tagOrReleaseName: String?): GHRelease? {
        logger.warn("[SGP - $path]: Looking for release with tag or name $tagOrReleaseName")
        val release = repository.getReleaseByTagName(tagOrReleaseName)
        if (release != null) {
            logger.warn("[SGP - $path]: Found $tagOrReleaseName")
            return release
        }

        for (eachRelease in repository.listReleases()) {
            if (eachRelease.name == tagOrReleaseName) {
                logger.warn("[SGP - $path]: Found $tagOrReleaseName")
                return eachRelease
            }
        }
        logger.warn("[SGP - $path]: Existing release not found")
        return null
    }

    private fun uploadAsset(release: GHRelease, file: File) {
        val mimeType = Files.probeContentType(file.toPath())
        val filename = file.name
        val sha256 = file.inputStream().use { DigestUtils.sha256Hex(it) }

        var lastFailure: Exception? = null
        repeat(5) { attempt ->
            try {
                for (existing in release.listAssets()) {
                    if (existing.name == filename || existing.name == "$filename.SHA256") {
                        logger.info("Replacing existing asset {}", existing.name)
                        existing.delete()
                    }
                }

                logger.info("Uploading asset {} (attempt {}/5)", filename, attempt + 1)
                file.inputStream().use {
                    release.uploadAsset(filename, it, mimeType)
                }
                release.uploadAsset("$filename.SHA256", "$sha256 $filename".byteInputStream(), "text/plain")
                return
            } catch (e: Exception) {
                lastFailure = e
                logger.warn("GitHub asset upload failed for {} (attempt {}/5): {}", filename, attempt + 1, e.message)
                if (attempt < 4) {
                    val delaySeconds = 5L shl attempt
                    logger.info("Retrying GitHub asset upload for {} in {} seconds", filename, delaySeconds)
                    TimeUnit.SECONDS.sleep(delaySeconds)
                }
            }
        }

        throw GradleException("Failed to upload GitHub asset $filename after 5 attempts", lastFailure)
    }

    private fun <T> retryGithubRead(read: () -> T): T {
        var lastFailure: IOException? = null
        repeat(3) { attempt ->
            try {
                return read()
            } catch (e: IOException) {
                lastFailure = e
                if (attempt < 2) {
                    val delaySeconds = 5L shl attempt
                    logger.warn("GitHub read failed; retrying in ${delaySeconds}s")
                    TimeUnit.SECONDS.sleep(delaySeconds)
                }
            }
        }
        throw GradleException("GitHub read failed after 3 attempts", lastFailure)
    }
}
