package io.specmatic.gradle.vuln

import javax.inject.Inject
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.tasks.Input
import org.gradle.process.ExecOperations

abstract class ImageVulnScanTask
    @Inject
    constructor(execLauncher: ExecOperations) : AbstractVulnScanTask(execLauncher) {
        @get:Input
        var image: String? = null

        override fun getCommandLine(format: String): List<String> {
            if (image == null) {
                throw GradleException("image property not set")
            }
            return listOf(trivyExecutable(), "image", "--format", format, *commonArgs, image!!)
        }
    }

internal fun Project.createDockerVulnScanTask(imageName: String) {
    val scanTaskName = "vulnScanDocker"

    val scanTask =
        tasks.register("${scanTaskName}Scan", ImageVulnScanTask::class.java) {
            dependsOn("dockerBuild")
            image = imageName

            trivyHomeDir.set(trivyHomeDir())
            reportsDir.set(
                project.layout.buildDirectory
                    .get()
                    .asFile
                    .resolve("reports/vulnerabilities/image")
            )
        }

    tasks.named("check") {
        dependsOn(scanTask)
    }
}
