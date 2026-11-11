package io.specmatic.gradle.promotion

import org.assertj.core.api.Assertions.assertThat
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Assertions.assertThrows
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

    @Test
    fun `prepares no assets when artifact coordinates are omitted`() {
        val project = ProjectBuilder.builder().build()
        val task = project.tasks.create("prepare", PreparePromotionGithubReleaseArtifactsTask::class.java)
        val inputDirectory = tempDir.resolve("maven-repository").toFile().apply { mkdirs() }
        writeArtifact(inputDirectory, "available", "obfuscated-fat")
        val outputDirectory = tempDir.resolve("github-assets")
        task.inputFiles.from(project.fileTree(inputDirectory))
        task.outputDirectory.set(outputDirectory.toFile())
        task.artifactType.set("obfuscated-fat")

        task.prepare()

        assertThat(outputDirectory).isDirectory().isEmptyDirectory()
    }

    @Test
    fun `prepares only configured release artifact coordinates`() {
        val project = ProjectBuilder.builder().build()
        val task = project.tasks.create("prepare", PreparePromotionGithubReleaseArtifactsTask::class.java)
        val inputDirectory = tempDir.resolve("maven-repository").toFile().apply { mkdirs() }
        val outputDirectory = tempDir.resolve("github-assets")
        val selected = writeArtifact(inputDirectory, "selected", "obfuscated-fat")
        writeArtifact(inputDirectory, "unselected", "obfuscated-fat")
        task.inputFiles.from(project.fileTree(inputDirectory))
        task.outputDirectory.set(outputDirectory.toFile())
        task.artifactType.set("obfuscated-fat")
        task.githubReleaseArtifactCoordinates.set(listOf("io.specmatic:selected"))

        task.prepare()

        assertThat(outputDirectory.toFile().listFiles().orEmpty().map { it.name }).containsExactly(selected.name)
    }

    @Test
    fun `fails when configured release artifact coordinate is unavailable`() {
        val project = ProjectBuilder.builder().build()
        val task = project.tasks.create("prepare", PreparePromotionGithubReleaseArtifactsTask::class.java)
        val inputDirectory = tempDir.resolve("empty-maven-repository").toFile().apply { mkdirs() }
        task.inputFiles.from(project.fileTree(inputDirectory))
        task.outputDirectory.set(tempDir.resolve("github-assets").toFile())
        task.artifactType.set("obfuscated-fat")
        task.githubReleaseArtifactCoordinates.set(listOf("io.specmatic:missing"))

        val error = assertThrows(GradleException::class.java) { task.prepare() }

        assertThat(error).hasMessageContaining("io.specmatic:missing")
    }

    private fun writeArtifact(directory: java.io.File, artifactId: String, artifactType: String): java.io.File {
        val pom = directory.resolve("$artifactId-1.2.3.pom")
        pom.writeText(
            """
            <project>
              <modelVersion>4.0.0</modelVersion>
              <groupId>io.specmatic</groupId>
              <artifactId>$artifactId</artifactId>
              <version>1.2.3</version>
              <properties>
                <x-specmatic-artifact-type>$artifactType</x-specmatic-artifact-type>
              </properties>
            </project>
            """.trimIndent(),
        )
        return directory.resolve("$artifactId-1.2.3.jar").apply { writeText("artifact") }
    }
}
