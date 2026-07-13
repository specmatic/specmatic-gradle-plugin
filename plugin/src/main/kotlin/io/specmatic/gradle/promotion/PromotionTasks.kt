package io.specmatic.gradle.promotion

import io.specmatic.gradle.CreateGithubReleaseTask
import io.specmatic.gradle.docker.UpdateDockerHubOverviewTask
import io.specmatic.gradle.extensions.MavenCentral
import io.specmatic.gradle.extensions.MavenInternal
import io.specmatic.gradle.extensions.PublishTarget
import io.specmatic.gradle.extensions.RepoType
import io.specmatic.gradle.license.pluginInfo
import io.specmatic.gradle.release.CreateReleaseTagTask
import io.specmatic.gradle.release.GitPushTask
import io.specmatic.gradle.release.PostReleaseBump
import io.specmatic.gradle.release.PreReleaseCheck
import io.specmatic.gradle.specmaticExtension
import io.specmatic.gradle.versioninfo.versionInfo
import org.gradle.api.Project
import org.gradle.api.model.ObjectFactory
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenArtifact
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.TaskProvider
import org.gradle.jvm.tasks.Jar

private const val INSPECT_PROMOTION_DOCKER_IMAGES_TASK = "inspectPromotionDockerImages"
private const val INSPECT_PROMOTION_TASK = "inspectPromotion"
private const val PROMOTE_DOCKER_TASK = "promoteDocker"
private const val PROMOTE_ARTIFACTS_TASK = "promoteArtifacts"
private const val DOWNLOAD_PROMOTION_MAVEN_ARTIFACTS_TASK = "downloadPromotionMavenArtifacts"
private const val VERIFY_PROMOTION_MAVEN_ARTIFACTS_TASK = "verifyPromotionMavenArtifacts"
private const val PROMOTE_MAVEN_TASK = "promoteMaven"
private const val PROMOTION_PRE_FLIGHT_TASK = "promotionPreFlight"
private const val UPDATE_PROMOTION_DOCKER_HUB_README_TASK = "updatePromotionDockerHubReadme"
private const val PROMOTION_CREATE_RELEASE_TAG_TASK = "promotionCreateReleaseTag"
private const val PROMOTION_POST_RELEASE_BUMP_TASK = "promotionPostReleaseBump"
private const val PROMOTION_GIT_PUSH_TASK = "promotionGitPush"
private const val PROMOTION_CREATE_GITHUB_RELEASE_TASK = "promotionCreateGithubRelease"
private const val PROMOTE_TASK = "promote"
private val CHECKSUM_SUFFIXES = listOf(".md5", ".sha1", ".sha256", ".sha512")

internal fun Project.configurePromotionTasks() {
    project.pluginInfo("Applying promotion tasks to ${this.path}")
    val promotionConfig = specmaticExtension().promotion
    val sourceImages = promotionConfig.dockerImagePromotions.map { it.sourceImage }.distinct()
    val targetImages = promotionConfig.dockerImagePromotions.map { it.targetImage }.distinct()
    val mavenArtifactPaths = promotionMavenArtifactPaths()
    val inspectPromotionTask =
        tasks.register(INSPECT_PROMOTION_TASK) {
            group = "promotion"
            description = "Validates Maven and Docker artifacts before promotion"
        }
    var hasInspectPhase = false
    var hasPromotePhase = false

    if (sourceImages.isNotEmpty()) {
        val inspectTask =
            tasks.register(INSPECT_PROMOTION_DOCKER_IMAGES_TASK, InspectPromotionDockerImagesTask::class.java) {
                group = "promotion"
                description = "Inspects configured Docker images and verifies the registry contains the required platforms"
                expectedGitSha.set(provider { project.versionInfo().gitCommit })
                images.set(sourceImages)
                version.set(provider { project.version.toString() })
            }
        inspectPromotionTask.configure { dependsOn(inspectTask) }
        hasInspectPhase = true

        tasks.register(PROMOTE_DOCKER_TASK, PromoteDockerImagesTask::class.java) {
            group = "promotion"
            description = "Promotes verified Docker images to configured target repositories"
            dependsOn(inspectPromotionTask)
            images.set(
                promotionConfig.dockerImagePromotions.map { image ->
                    objects.promotionDockerImageInput(sourceImage = image.sourceImage, targetImage = image.targetImage)
                },
            )
            version.set(provider { project.version.toString() })
        }
        hasPromotePhase = true
    }

    val canonicalRepositoryUri = promotionConfig.canonicalMavenRepository
    if (canonicalRepositoryUri != null && mavenArtifactPaths.isNotEmpty()) {
        val targetRepositories = promotionConfig.targetMavenRepositories
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
        inspectPromotionTask.configure { dependsOn(VERIFY_PROMOTION_MAVEN_ARTIFACTS_TASK) }
        hasInspectPhase = true

        if (targetRepositories.isNotEmpty()) {
            tasks.register(PROMOTE_MAVEN_TASK, PromoteMavenArtifactsTask::class.java) {
                group = "promotion"
                description = "Promotes verified Maven artifacts to configured target repositories"
                dependsOn(inspectPromotionTask)
                automaticMavenCentralRelease.set(provider { project.properties["disableMavenCentralAutoPublish"] != "true" })
                inputDirectory.set(layout.buildDirectory.dir("promotion/maven"))
                targets.set(
                    targetRepositories.map { repo ->
                        objects.promotionTarget(
                            repo = repo,
                            artifactPaths = promotionMavenArtifactPaths(listOf(repo)),
                            username = providers.gradleProperty("${repo.repoName()}Username").orNull.orEmpty(),
                            password = providers.gradleProperty("${repo.repoName()}Password").orNull.orEmpty(),
                        )
                    },
                )
            }
            hasPromotePhase = true
        }
    }

    if (!hasInspectPhase) {
        tasks.named(INSPECT_PROMOTION_TASK).configure {
            enabled = false
        }
    }

    if (hasPromotePhase) {
        val promoteArtifactsTask =
            tasks.register(PROMOTE_ARTIFACTS_TASK) {
                group = "promotion"
                description = "Runs promotion inspection and promotes all configured artifacts"
                dependsOn(inspectPromotionTask)
                if (tasks.names.contains(PROMOTE_MAVEN_TASK)) {
                    dependsOn(PROMOTE_MAVEN_TASK)
                }
                if (tasks.names.contains(PROMOTE_DOCKER_TASK)) {
                    dependsOn(PROMOTE_DOCKER_TASK)
                }
            }

        val preFlightTask =
            tasks.register(PROMOTION_PRE_FLIGHT_TASK, PreReleaseCheck::class.java) {
                group = "promotion"
                description = "Runs git safety checks before promotion"
                rootDir.set(rootProject.rootDir)
            }
        inspectPromotionTask.configure { dependsOn(preFlightTask) }

        val updateDockerReadmeTask = registerPromotionDockerReadmeTasks(targetImages, promoteArtifactsTask)
        val createReleaseTagTask = registerPromotionCreateReleaseTagTask(updateDockerReadmeTask)
        val postReleaseBumpTask = registerPromotionPostReleaseBumpTask(createReleaseTagTask)
        val gitPushTask = registerPromotionGitPushTask(postReleaseBumpTask)
        val createGithubReleaseTask = registerPromotionCreateGithubReleaseTask(gitPushTask, updateDockerReadmeTask)

        tasks.register(PROMOTE_TASK) {
            group = "promotion"
            description = "Runs inspection, promotes configured artifacts, updates release metadata, and bumps to the next version"
            dependsOn(createGithubReleaseTask)
        }
    }
}

private fun Project.registerPromotionDockerReadmeTasks(targetImages: List<String>, dependentTask: TaskProvider<*>,): TaskProvider<*> {
    val readmeFile = file("readme.docker.md")
    val readmeTasks =
        targetImages.map { targetImage ->
            tasks.register("updatePromotionDockerHubReadme${targetImage.toTaskNameSuffix()}", UpdateDockerHubOverviewTask::class.java) {
                dependsOn(dependentTask)
                group = "promotion"
                description = "Publishes Docker Hub README content for $targetImage after promotion"
                onlyIf { readmeFile.exists() }
                dockerHubUsername.set(
                    provider {
                        System.getenv("SPECMATIC_DOCKER_HUB_USERNAME")
                            ?: error("SPECMATIC_DOCKER_HUB_USERNAME environment variable is not set")
                    },
                )
                dockerHubApiToken.set(
                    provider {
                        System.getenv("SPECMATIC_DOCKER_HUB_TOKEN")
                            ?: error("SPECMATIC_DOCKER_HUB_TOKEN environment variable is not set")
                    },
                )
                repositoryName.set(targetImage)
                readmeContent.set(provider { readmeFile.readText() })
            }
        }

    return tasks.register(UPDATE_PROMOTION_DOCKER_HUB_README_TASK) {
        group = "promotion"
        description = "Updates Docker Hub README content for promoted target images"
        dependsOn(dependentTask)
        readmeTasks.forEach { dependsOn(it) }
    }
}

private fun Project.registerPromotionCreateReleaseTagTask(dependentTask: TaskProvider<*>): TaskProvider<*> =
    tasks.register(PROMOTION_CREATE_RELEASE_TAG_TASK, CreateReleaseTagTask::class.java) {
        dependsOn(dependentTask)
        group = "promotion"
        description = "Creates a git tag for the promoted version"
        rootDir.set(rootProject.rootDir)
        releaseVersion.set(provider { version.toString() })
    }

private fun Project.registerPromotionPostReleaseBumpTask(dependentTask: TaskProvider<*>): TaskProvider<*> =
    tasks.register(PROMOTION_POST_RELEASE_BUMP_TASK, PostReleaseBump::class.java) {
        dependsOn(dependentTask)
        group = "promotion"
        description = "Bumps the repo to the next development version after promotion"
        rootDir.set(rootProject.rootDir)
        postReleaseVersion.set(provider { property("release.newVersion").toString() })
    }

private fun Project.registerPromotionGitPushTask(dependentTask: TaskProvider<*>): TaskProvider<*> =
    tasks.register(PROMOTION_GIT_PUSH_TASK, GitPushTask::class.java) {
        dependsOn(dependentTask)
        group = "promotion"
        description = "Pushes promotion git commits and tags"
        rootDir.set(rootProject.rootDir)
    }

private fun Project.registerPromotionCreateGithubReleaseTask(
    dependentTask: TaskProvider<*>,
    dockerReadmeTask: TaskProvider<*>,
): TaskProvider<*> = tasks.register(PROMOTION_CREATE_GITHUB_RELEASE_TASK, CreateGithubReleaseTask::class.java) {
    dependsOn(dependentTask, dockerReadmeTask)
    group = "promotion"
    description = "Creates the GitHub release for the promoted version"
    sourceDir.set(
        layout.buildDirectory
            .dir("githubAssets")
            .get()
            .asFile
    )
    releaseVersion.set(provider { version.toString() })
}

private fun ObjectFactory.promotionTarget(
    repo: PublishTarget,
    artifactPaths: List<String>,
    username: String,
    password: String,
): PromotionMavenTargetInput = newInstance(PromotionMavenTargetInput::class.java).apply {
    repoName.set(repo.repoName())
    kind.set(repo.promotionKind().name)
    url.set(repo.urlOrBlank())
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

    val includeNonObfuscatedArtifacts = publishTargets.any { target -> target.repoType() == RepoType.PUBLISH_ALL }

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

private fun PublishTarget.repoType(): RepoType = when (this) {
    is MavenInternal -> type
    is MavenCentral -> type
    else -> RepoType.PUBLISH_ALL
}

private fun PublishTarget.repoName(): String = when (this) {
    is MavenInternal -> repoName
    is MavenCentral -> repoName
    else -> throw IllegalArgumentException("Unsupported publish target $this")
}

private fun PublishTarget.urlOrBlank(): String = when (this) {
    is MavenInternal -> url.toString()
    is MavenCentral -> "https://central.sonatype.com"
    else -> ""
}

private fun PublishTarget.promotionKind(): PromotionMavenTargetKind = when (this) {
    is MavenInternal -> PromotionMavenTargetKind.REPOSITORY
    is MavenCentral -> PromotionMavenTargetKind.MAVEN_CENTRAL
    else -> throw IllegalArgumentException("Unsupported publish target $this")
}

private fun MavenArtifact.fileName(artifactId: String, version: String): String {
    val classifierSuffix = if (classifier.isNullOrBlank()) "" else "-$classifier"
    return "$artifactId-$version$classifierSuffix.$extension"
}

private fun String.toTaskNameSuffix(): String = split(Regex("[^A-Za-z0-9]+"))
    .filter { it.isNotBlank() }
    .joinToString("") { part ->
        part.replaceFirstChar { char -> char.uppercase() }
    }
