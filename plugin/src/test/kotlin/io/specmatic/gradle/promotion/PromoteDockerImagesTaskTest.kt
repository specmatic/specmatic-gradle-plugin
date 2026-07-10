package io.specmatic.gradle.promotion

import org.assertj.core.api.Assertions.assertThat
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test

class PromoteDockerImagesTaskTest {
    @Test
    fun `promotes version and latest tags by default`() {
        val task = task()
        task.version.set("1.2.3")
        task.images.set(
            listOf(
                task.project.objects.promotionDockerImageInput("specmatic/example", "acme/example"),
                task.project.objects.promotionDockerImageInput("specmatic/example-2", "acme/example-2"),
            ),
        )

        task.promoteImages()

        assertThat(task.recordedCommands).containsExactly(
            listOf("docker", "buildx", "imagetools", "create", "--tag", "acme/example:1.2.3", "specmatic/example:1.2.3"),
            listOf("docker", "buildx", "imagetools", "create", "--tag", "acme/example:latest", "specmatic/example:latest"),
            listOf("docker", "buildx", "imagetools", "create", "--tag", "acme/example-2:1.2.3", "specmatic/example-2:1.2.3"),
            listOf("docker", "buildx", "imagetools", "create", "--tag", "acme/example-2:latest", "specmatic/example-2:latest"),
        )
    }

    @Test
    fun `can promote only version tag`() {
        val task = task()
        task.version.set("1.2.3")
        task.promoteLatest.set(false)
        task.images.set(listOf(task.project.objects.promotionDockerImageInput("specmatic/example", "acme/example")))

        task.promoteImages()

        assertThat(task.recordedCommands).containsExactly(
            listOf("docker", "buildx", "imagetools", "create", "--tag", "acme/example:1.2.3", "specmatic/example:1.2.3"),
        )
    }

    private fun task(): RecordingPromoteDockerImagesTask {
        val project = ProjectBuilder.builder().build()
        return project.tasks.create("promoteDockerTest", RecordingPromoteDockerImagesTask::class.java)
    }
}

abstract class RecordingPromoteDockerImagesTask : PromoteDockerImagesTask() {
    val recordedCommands = mutableListOf<List<String>>()

    override fun runCommand(commandLine: List<String>) {
        recordedCommands.add(commandLine)
    }
}
