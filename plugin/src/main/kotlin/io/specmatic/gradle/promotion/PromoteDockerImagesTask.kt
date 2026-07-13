package io.specmatic.gradle.promotion

import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Nested
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Promotes external Docker images between registries")
abstract class PromoteDockerImagesTask : DefaultTask() {
    @get:Nested
    abstract val images: ListProperty<PromotionDockerImageInput>

    @get:Input
    abstract val version: Property<String>

    @get:Input
    abstract val promoteLatest: Property<Boolean>

    @get:Inject
    protected abstract val execOperations: ExecOperations

    init {
        promoteLatest.convention(true)
    }

    @TaskAction
    fun promoteImages() {
        images.get().forEach { image ->
            promoteTag(image.sourceImage.get(), image.targetImage.get(), version.get())

            if (promoteLatest.get()) {
                promoteTag(image.sourceImage.get(), image.targetImage.get(), "latest")
            }
        }
    }

    private fun promoteTag(sourceImage: String, targetImage: String, tag: String) {
        val sourceRef = "$sourceImage:$tag"
        val targetRef = "$targetImage:$tag"
        logger.lifecycle("Promoting Docker image $sourceRef -> $targetRef")
        runCommand(listOf("docker", "buildx", "imagetools", "create", "--tag", targetRef, sourceRef))
    }

    internal open fun runCommand(commandLine: List<String>) {
        execOperations.exec {
            commandLine(commandLine)
        }
    }
}

abstract class PromotionDockerImageInput {
    @get:Input
    abstract val sourceImage: Property<String>

    @get:Input
    abstract val targetImage: Property<String>
}

internal fun ObjectFactory.promotionDockerImageInput(sourceImage: String, targetImage: String): PromotionDockerImageInput =
    newInstance(PromotionDockerImageInput::class.java).apply {
        this.sourceImage.set(sourceImage)
        this.targetImage.set(targetImage)
    }
