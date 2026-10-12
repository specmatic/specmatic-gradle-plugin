package io.specmatic.gradle.autogen

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class GeneratedFileTasksTest {
    @TempDir
    lateinit var temporaryDirectory: File

    @Test
    fun `writes configured text to output file`() {
        val project = ProjectBuilder.builder().withProjectDir(temporaryDirectory).build()
        val task = project.tasks.create("write", WriteTextFileTask::class.java)
        val output = temporaryDirectory.resolve("generated/output.txt")
        task.content.set("generated content")
        task.outputFile.set(output)

        task.write()

        assertThat(output).hasContent("generated content")
    }

    @Test
    fun `copies classpath resource to output file`() {
        val project = ProjectBuilder.builder().withProjectDir(temporaryDirectory).build()
        val task = project.tasks.create("copy", CopyClasspathResourceTask::class.java)
        val output = temporaryDirectory.resolve("generated/editorconfig")
        task.resourceName.set("editorconfig")
        task.outputFile.set(output)

        task.copy()

        assertThat(output).hasContent(File("src/main/resources/editorconfig").readText())
    }
}
