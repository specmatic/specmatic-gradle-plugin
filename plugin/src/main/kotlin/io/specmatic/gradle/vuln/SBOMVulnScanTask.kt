package io.specmatic.gradle.vuln

import io.specmatic.gradle.vuln.scanner.ScanTarget
import io.specmatic.gradle.vuln.scanner.ScanTargetKind
import java.io.File
import javax.inject.Inject
import org.cyclonedx.gradle.BaseCyclonedxTask
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.ExecOperations

abstract class SBOMVulnScanTask
@Inject
constructor(execLauncher: ExecOperations) : AbstractVulnScanTask(execLauncher) {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sbomFile: Property<File>

    override fun scanTarget(): ScanTarget = ScanTarget(ScanTargetKind.SBOM, sbomFile.get().path)
}

internal fun Project.createSBOMVulnScanTask() {
    val scanTaskName = "vulnScanSBOM"
    val scannerType = vulnScannerType()

    val scanTask =
        tasks.register("${scanTaskName}Scan", SBOMVulnScanTask::class.java) {
            dependsOn(tasks.withType(BaseCyclonedxTask::class.java))
            scannerTool.set(scannerType)
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

    project.tasks.named("check") {
        dependsOn(scanTask)
    }
}
