package io.specmatic.gradle

import io.specmatic.gradle.license.pluginInfo
import io.specmatic.gradle.utils.okHttpConnector
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.apache.commons.codec.digest.DigestUtils
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import org.kohsuke.github.GHRelease
import org.kohsuke.github.GHReleaseBuilder
import org.kohsuke.github.GHRepository
import org.kohsuke.github.GitHubBuilder
import org.kohsuke.github.extras.okhttp3.OkHttpGitHubConnector

@DisableCachingByDefault(because = "Makes network calls")
abstract class CreateGithubReleaseTask : DefaultTask() {
    @get:Internal
    abstract val releaseVersion: Property<String>

    @get:Internal
    abstract val sourceDir: Property<File>

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

        val githubRelease = findReleaseByName(githubRepo, releaseVersion.get()) ?: createRelease(githubRepo)
        logger.warn("Created release ${githubRelease.name} with id ${githubRelease.id} at ${githubRelease.htmlUrl}")

        for (asset in githubRelease.listAssets()) {
            asset.delete()
        }

        if (sourceDir.isPresent && sourceDir.get().exists()) {
            sourceDir.get().listFiles().orEmpty().forEach { eachFile ->
                uploadAsset(githubRelease, eachFile)
                project.pluginInfo("Uploaded asset $eachFile")
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
        project.pluginInfo("Created draft release ${release.name} with id ${release.id} at ${release.htmlUrl}")
        return release
    }

    @Throws(IOException::class)
    private fun findReleaseByName(repository: GHRepository, tagOrReleaseName: String?): GHRelease? {
        project.pluginInfo("Looking for release with tag or name $tagOrReleaseName")
        val release = repository.getReleaseByTagName(tagOrReleaseName)
        if (release != null) {
            project.pluginInfo("Found $tagOrReleaseName")
            return release
        }

        for (eachRelease in repository.listReleases()) {
            if (eachRelease.name == tagOrReleaseName) {
                project.pluginInfo("Found $tagOrReleaseName")
                return eachRelease
            }
        }
        project.pluginInfo("Existing release not found")
        return null
    }

    private fun uploadAsset(release: GHRelease, file: File) {
        val mimeType = Files.probeContentType(file.toPath())
        val filename = file.name

        for (existing in release.listAssets()) {
            if (existing.name.startsWith(filename)) {
                logger.info("Replacing existing asset {}", existing.name)
                existing.delete()
            }
        }

        val sha256 = file.inputStream().use { DigestUtils.sha256Hex(it) }

        logger.info("Uploading asset {}", filename)
        val releaseFileName = file.name

        file.inputStream().use {
            release.uploadAsset(releaseFileName, it, mimeType)
        }
        release.uploadAsset("$releaseFileName.SHA256", "$sha256 $releaseFileName".byteInputStream(), "text/plain")
    }
}
