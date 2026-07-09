package io.specmatic.gradle.promotion

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Inspects external Docker registries")
abstract class InspectPromotionDockerImagesTask : DefaultTask() {
    @get:Input
    abstract val expectedGitSha: Property<String>

    @get:Input
    abstract val images: ListProperty<String>

    @get:Input
    abstract val version: Property<String>

    @get:Input
    abstract val platforms: ListProperty<String>

    @get:Inject
    protected abstract val execOperations: ExecOperations

    init {
        platforms.convention(listOf("linux/amd64", "linux/arm64"))
    }

    @TaskAction
    fun inspectImages() {
        images.get().forEach { image ->
            val imageRef = "$image:${version.get()}"
            val output = ByteArrayOutputStream()

            logger.lifecycle("Inspecting $imageRef")
            execOperations.exec {
                commandLine("docker", "buildx", "imagetools", "inspect", imageRef, "--raw")
                standardOutput = output
            }

            val manifestList = jacksonObjectMapper().readValue(output.toByteArray(), DockerManifestList::class.java)
            val requiredPlatforms = platforms.get().toSet()
            val platformManifests =
                manifestList.manifests.filter { manifest ->
                    manifest.platform?.let { "${it.os}/${it.architecture}" } in requiredPlatforms
                }
            val availablePlatforms =
                platformManifests
                    .mapNotNull { manifest -> manifest.platform?.let { "${it.os}/${it.architecture}" } }
                    .toSet()

            val missingPlatforms = requiredPlatforms.filterNot(availablePlatforms::contains)
            if (missingPlatforms.isNotEmpty()) {
                throw GradleException(
                    "Docker image $imageRef is missing required platforms: ${missingPlatforms.joinToString(", ")}. " +
                        "Available platforms: ${availablePlatforms.sorted().joinToString(", ")}",
                )
            }

            val expectedVersion = version.get()
            val expectedSha = expectedGitSha.get()
            if (manifestList.annotations["org.opencontainers.image.revision"] != expectedSha) {
                throw GradleException(
                    "Docker image $imageRef index annotation org.opencontainers.image.revision did not match. Expected $expectedSha, " +
                        "found ${manifestList.annotations["org.opencontainers.image.revision"]}.",
                )
            }

            if (manifestList.annotations["org.opencontainers.image.version"] != expectedVersion) {
                throw GradleException(
                    "Docker image $imageRef index annotation org.opencontainers.image.version did not match. Expected $expectedVersion, " +
                        "found ${manifestList.annotations["org.opencontainers.image.version"]}.",
                )
            }

            platformManifests.forEach { manifest ->
                val platform = manifest.platform?.let { "${it.os}/${it.architecture}" } ?: "unknown"

                if (manifest.annotations["org.opencontainers.image.revision"] != expectedSha) {
                    throw GradleException(
                        "Docker image $imageRef manifest annotation org.opencontainers.image.revision did not match for $platform. " +
                            "Expected $expectedSha, found ${manifest.annotations["org.opencontainers.image.revision"]}.",
                    )
                }

                if (manifest.annotations["org.opencontainers.image.version"] != expectedVersion) {
                    throw GradleException(
                        "Docker image $imageRef manifest annotation org.opencontainers.image.version did not match for $platform. " +
                            "Expected $expectedVersion, found ${manifest.annotations["org.opencontainers.image.version"]}.",
                    )
                }
            }
        }
    }
}

private data class DockerManifestList(val manifests: List<DockerManifest> = emptyList(), val annotations: Map<String, String> = emptyMap(),)

private data class DockerManifest(val platform: DockerPlatform? = null, val annotations: Map<String, String> = emptyMap())

private data class DockerPlatform(val architecture: String = "", val os: String = "")
