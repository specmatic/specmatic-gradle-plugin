package io.specmatic.gradle.docker

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CreateDockerFilesTaskTest {
    @TempDir
    lateinit var temporaryDirectory: File

    @Test
    fun `generates docker assets from declared inputs`() {
        val project = ProjectBuilder.builder().withProjectDir(temporaryDirectory).build()
        val task = project.tasks.register("generate", CreateDockerFilesTask::class.java).get()
        val sourceJar = temporaryDirectory.resolve("input.jar").apply { writeText("jar") }
        val dockerfile = temporaryDirectory.resolve("build/Dockerfile")
        val shellScript = temporaryDirectory.resolve("build/specmatic")
        task.sourceJar.set(sourceJar)
        task.sourceJarPath.set("libs/app.jar")
        task.sourceSbomPath.set("reports/cyclonedx/bom.json")
        task.targetJarPath.set("/usr/local/share/specmatic/specmatic.jar")
        task.targetSbomPath.set("/usr/local/share/specmatic/sbom.cyclonedx.json")
        task.dockerOrg.set("specmatic")
        task.imageName.set("specmatic")
        task.imageVersion.set("1.2.3")
        task.gitSha.set("deadbeef")
        task.extraApkDependencies.set(listOf("curl", "bash"))
        task.extraExecutableNames.set(listOf("specmatic-cli"))
        task.dockerfile.set(dockerfile)
        task.shellScript.set(shellScript)

        task.generate()

        assertThat(dockerfile).exists().content().contains(
            "libs/app.jar",
            "reports/cyclonedx/bom.json",
            "deadbeef",
            "1.2.3",
            "curl bash",
            "ln -sf /usr/local/bin/specmatic /usr/local/bin/specmatic-cli",
        )
        assertThat(shellScript).exists().isExecutable().content().contains("/usr/local/share/specmatic/specmatic.jar")
    }
}
