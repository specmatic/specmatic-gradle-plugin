package io.specmatic.gradle.vuln

import io.specmatic.gradle.release.PreReleaseCheck
import io.specmatic.gradle.vuln.scanner.ScanTarget
import io.specmatic.gradle.vuln.scanner.ScanTargetKind
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

        override fun scanTarget(): ScanTarget {
            if (image == null) {
                throw GradleException("image property not set")
            }
            return ScanTarget(ScanTargetKind.IMAGE, image!!)
        }
    }

internal fun Project.createDockerVulnScanTask(imageName: String) {
    val scanTaskName = "vulnScanDocker"
    val scannerType = vulnScannerType()

    val scanTask =
        tasks.register("${scanTaskName}Scan", ImageVulnScanTask::class.java) {
            dependsOn("dockerBuild")
            image = imageName
            scannerTool.set(scannerType)
            reportsDir.set(
                project.layout.buildDirectory
                    .get()
                    .asFile
                    .resolve("reports/vulnerabilities/image")
            )
        }

    rootProject.tasks.withType(PreReleaseCheck::class.java) {
        dependsOn(scanTask)
    }

    tasks.named("check") {
        dependsOn(scanTask)
    }
}
