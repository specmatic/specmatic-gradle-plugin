package io.specmatic.gradle.vuln

import java.io.File
import javax.inject.Inject
import org.cyclonedx.gradle.CycloneDxTask
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.ExecOperations

abstract class SBOMVulnScanTask
    @Inject
    constructor(execLauncher: ExecOperations) : AbstractVulnScanTask(execLauncher) {
        override fun getCommandLine(format: String): List<String> = listOf(
            trivyExecutable(),
            "sbom",
            "--format",
            format,
            *commonArgs,
            sbomFile.get().path,
        )

        @get:InputFile
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val sbomFile: Property<File>
    }

internal fun Project.createSBOMVulnScanTask() {
    val scanTaskName = "vulnScanSBOM"

    val scanTask =
        tasks.register("${scanTaskName}Scan", SBOMVulnScanTask::class.java) {
            dependsOn(tasks.withType(CycloneDxTask::class.java))

            trivyHomeDir.set(trivyHomeDir())
            sbomFile.set(
                project.layout.buildDirectory
                    .get()
                    .asFile
                    .resolve("reports/cyclonedx/bom.json")
            )

            reportsDir.set(
                project.layout.buildDirectory
                    .get()
                    .asFile
                    .resolve("reports/vulnerabilities/sbom")
            )
        }

    val reportTask =
        project.tasks.register(scanTaskName) {
            dependsOn(scanTask)
            group = "vulnerability"
            description = "Scan and print vulnerabilities in SBOM"

            doFirst {
                val reportFile = scanTask.get().getTextTableReportFile()
                printReportFile(this@createSBOMVulnScanTask, reportFile)
            }
        }

    project.tasks.named("check") {
        dependsOn(reportTask)
    }
}
