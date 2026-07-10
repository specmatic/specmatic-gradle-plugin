package io.specmatic.gradle.promotion

import io.specmatic.gradle.extensions.MavenInternal
import io.specmatic.gradle.extensions.PublishTarget
import io.specmatic.gradle.extensions.RepoType
import io.specmatic.gradle.license.pluginInfo
import io.specmatic.gradle.specmaticExtension
import io.specmatic.gradle.versioninfo.versionInfo
import org.gradle.api.Project
import org.gradle.api.model.ObjectFactory
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenArtifact
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.jvm.tasks.Jar

private const val INSPECT_PROMOTION_DOCKER_IMAGES_TASK = "inspectPromotionDockerImages"
private const val DOWNLOAD_PROMOTION_MAVEN_ARTIFACTS_TASK = "downloadPromotionMavenArtifacts"
private const val VERIFY_PROMOTION_MAVEN_ARTIFACTS_TASK = "verifyPromotionMavenArtifacts"
private const val PROMOTE_MAVEN_TASK = "promoteMaven"
private val CHECKSUM_SUFFIXES = listOf(".md5", ".sha1", ".sha256", ".sha512")

internal fun Project.configurePromotionTasks() {
    project.pluginInfo("Applying promotion tasks to ${this.path}")
    val promotionConfig = specmaticExtension().promotion
    val sourceImages = promotionConfig.dockerImagePromotions.map { it.sourceImage }.distinct()
    val mavenArtifactPaths = promotionMavenArtifactPaths()

    if (sourceImages.isNotEmpty()) {
        tasks.register(INSPECT_PROMOTION_DOCKER_IMAGES_TASK, InspectPromotionDockerImagesTask::class.java) {
            group = "promotion"
            description = "Inspects configured Docker images and verifies the registry contains the required platforms"
            expectedGitSha.set(provider { project.versionInfo().gitCommit })
            images.set(sourceImages)
            version.set(provider { project.version.toString() })
        }
    }

    val canonicalRepositoryUri = promotionConfig.canonicalMavenRepository
    if (canonicalRepositoryUri != null && mavenArtifactPaths.isNotEmpty()) {
        val targetRepositories = promotionConfig.targetMavenRepositories.filterIsInstance<MavenInternal>()
        val downloadTask =
            tasks.register(DOWNLOAD_PROMOTION_MAVEN_ARTIFACTS_TASK, DownloadPromotionMavenArtifactsTask::class.java) {
                group = "promotion"
                description = "Downloads published Maven artifacts for configured modules from the canonical repository"
                canonicalRepository.set(canonicalRepositoryUri.toString())
                artifactRelativePaths.set(mavenArtifactPaths)
                outputDirectory.set(layout.buildDirectory.dir("promotion/maven"))
            }

        tasks.register(VERIFY_PROMOTION_MAVEN_ARTIFACTS_TASK, VerifyPromotionMavenArtifactsTask::class.java) {
            group = "promotion"
            description = "Verifies downloaded Maven artifacts match the current version and git sha"
            dependsOn(downloadTask)
            inputDirectory.set(downloadTask.flatMap { it.outputDirectory })
            expectedGitSha.set(provider { project.versionInfo().gitCommit })
            expectedVersion.set(provider { project.version.toString() })
        }

        if (targetRepositories.isNotEmpty()) {
            tasks.register(PROMOTE_MAVEN_TASK, PromoteMavenArtifactsTask::class.java) {
                group = "promotion"
                description = "Promotes verified Maven artifacts to configured target repositories"
                dependsOn(VERIFY_PROMOTION_MAVEN_ARTIFACTS_TASK)
                inputDirectory.set(layout.buildDirectory.dir("promotion/maven"))
                targets.set(
                    targetRepositories.map { repo ->
                        objects.promotionTarget(
                            repo = repo,
                            artifactPaths = promotionMavenArtifactPaths(listOf(repo)),
                            username = providers.gradleProperty("${repo.repoName}Username").orNull.orEmpty(),
                            password = providers.gradleProperty("${repo.repoName}Password").orNull.orEmpty(),
                        )
                    },
                )
            }
        }
    }
}

private fun ObjectFactory.promotionTarget(
    repo: MavenInternal,
    artifactPaths: List<String>,
    username: String,
    password: String,
): PromotionMavenTargetInput = newInstance(PromotionMavenTargetInput::class.java).apply {
    repoName.set(repo.repoName)
    url.set(repo.url.toString())
    this.username.set(username)
    this.password.set(password)
    this.artifactPaths.set(artifactPaths)
}

private fun Project.promotionMavenArtifactPaths(): List<String> {
    val extension = specmaticExtension()
    return extension.projectConfigurations
        .filter { (_, distribution) -> distribution.publishTo.isNotEmpty() }
        .flatMap { (configuredProject, distribution) ->
            configuredProject.publishedMavenRelativePaths(distribution.publishTo)
        }.distinct()
        .sorted()
}

private fun Project.promotionMavenArtifactPaths(targets: List<PublishTarget>): List<String> {
    val extension = specmaticExtension()
    return extension.projectConfigurations
        .filter { (_, distribution) -> distribution.publishTo.isNotEmpty() }
        .flatMap { (configuredProject, _) ->
            configuredProject.publishedMavenRelativePaths(targets)
        }.distinct()
        .sorted()
}

private fun Project.publishedMavenRelativePaths(publishTargets: List<PublishTarget>): List<String> {
    val publishing = extensions.findByType(PublishingExtension::class.java) ?: return emptyList()
    val publications = publishing.publications.withType(MavenPublication::class.java).toList()
    if (publications.isEmpty()) {
        return emptyList()
    }

    val includeNonObfuscatedArtifacts = publishTargets.any { target -> target !is MavenInternal || target.type == RepoType.PUBLISH_ALL }

    return publications
        .filter { publication -> includeNonObfuscatedArtifacts || publication.isObfuscatedPublication() }
        .flatMap { publication -> publication.remoteFiles() }
}

private fun MavenPublication.remoteFiles(): List<String> {
    val baseDir = gavPath(groupId, artifactId, version)
    val primaryFiles =
        buildList {
            add("$baseDir/$artifactId-$version.pom")
            addAll(artifacts.map { artifact -> "$baseDir/${artifact.fileName(artifactId, version)}" })
        }.distinct()
    val signedFiles = primaryFiles.map { "$it.asc" }
    val checksumFiles = (primaryFiles + signedFiles).flatMap { file -> CHECKSUM_SUFFIXES.map { suffix -> "$file$suffix" } }

    return (primaryFiles + signedFiles + checksumFiles).distinct()
}

private fun MavenPublication.isObfuscatedPublication(): Boolean = artifacts
    .flatMap { artifact -> artifact.buildDependencies.getDependencies(null) }
    .filterIsInstance<Jar>()
    .any { jar ->
        val classifier = jar.archiveClassifier.orNull
        classifier == "obfuscated" || classifier == "all-obfuscated"
    }

private fun gavPath(groupId: String, artifactId: String, version: String): String = "${groupId.replace('.', '/')}/$artifactId/$version"

private fun MavenArtifact.fileName(artifactId: String, version: String): String {
    val classifierSuffix = if (classifier.isNullOrBlank()) "" else "-$classifier"
    return "$artifactId-$version$classifierSuffix.$extension"
}
