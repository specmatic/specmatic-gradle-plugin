package io.specmatic.gradle.promotion

import org.assertj.core.api.Assertions.assertThat
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PreparePromotionGithubReleaseArtifactsTaskTest {
    @TempDir
    lateinit var tempDir: java.nio.file.Path

    @Test
    fun `allows missing Maven input directory`() {
        val project = ProjectBuilder.builder().build()
        val task = project.tasks.create("prepare", PreparePromotionGithubReleaseArtifactsTask::class.java)
        val inputDirectory = tempDir.resolve("missing-maven-repository")
        val outputDirectory = tempDir.resolve("github-assets")
        task.inputFiles.from(inputDirectory.toFile())
        task.outputDirectory.set(outputDirectory.toFile())
        task.artifactType.set("obfuscated-fat")

        task.prepare()

        assertThat(outputDirectory).isDirectory().isEmptyDirectory()
    }
}
