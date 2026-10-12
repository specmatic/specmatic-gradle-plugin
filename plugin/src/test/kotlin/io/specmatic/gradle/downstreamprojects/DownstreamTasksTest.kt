package io.specmatic.gradle.downstreamprojects

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DownstreamTasksTest {
    @TempDir
    lateinit var temporaryDirectory: File

    @Test
    fun `bump task updates configured version and preserves comments`() {
        val project = ProjectBuilder.builder().withProjectDir(temporaryDirectory).build()
        val propertiesFile = temporaryDirectory.resolve("downstream/gradle.properties").apply {
            parentFile.mkdirs()
            writeText("# module version\ncoreVersion=1.0.0\nother=value\n")
        }
        val task = project.tasks.register("bump", BumpDownstreamVersionTask::class.java).get()
        task.propertyKey.set("coreVersion")
        task.moduleVersion.set("2.0.0")
        task.gradlePropertiesFile.set(propertiesFile)

        task.bump()

        assertThat(propertiesFile).hasContent("# module version\ncoreVersion=2.0.0\nother=value")
    }

    @Test
    fun `bump task adds missing property`() {
        val project = ProjectBuilder.builder().withProjectDir(temporaryDirectory).build()
        val propertiesFile = temporaryDirectory.resolve("downstream/gradle.properties").apply {
            parentFile.mkdirs()
            writeText("other=value")
        }
        val task = project.tasks.register("bump", BumpDownstreamVersionTask::class.java).get()
        task.propertyKey.set("coreVersion")
        task.moduleVersion.set("2.0.0")
        task.gradlePropertiesFile.set(propertiesFile)

        task.bump()

        assertThat(propertiesFile).hasContent("other=value\ncoreVersion=2.0.0\n")
    }

    @Test
    fun `selects gradle wrapper for gradle downstream project`() {
        val projectDir = temporaryDirectory.apply { resolve("build.gradle.kts").createNewFile() }

        val (command, _) = downstreamValidationCommand(projectDir, "coreVersion", "2.0.0", "Linux")

        assertThat(command).containsExactly("./gradlew", "check", "-PcoreVersion=2.0.0")
    }

    @Test
    fun `selects maven wrapper when present`() {
        val projectDir = temporaryDirectory.apply {
            resolve("pom.xml").createNewFile()
            resolve("mvnw").createNewFile()
        }

        val (command, _) = downstreamValidationCommand(projectDir, "coreVersion", "2.0.0", "Linux")

        assertThat(command).containsExactly("./mvnw", "test", "-DcoreVersion=2.0.0")
    }

    @Test
    fun `fails when downstream project has no build definition`() {
        assertThatThrownBy { downstreamValidationCommand(temporaryDirectory, "coreVersion", "2.0.0", "Linux") }
            .hasMessageContaining("No build file found")
    }
}
