package io.specmatic.gradle.vuln

import org.cyclonedx.gradle.CycloneDxTask
import org.gradle.api.Plugin
import org.gradle.api.Project

class SpecmaticVulnScanPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.tasks.register("cyclonedxBom", CycloneDxTask::class.java) {
            group = "Reporting"
            description = "Generates a CycloneDX compliant Software Bill of Materials (SBOM)"

            includeBomSerialNumber.set(false)
            // https://github.com/CycloneDX/cyclonedx-gradle-plugin/issues/271
            destination.set(
                project.layout.buildDirectory
                    .get()
                    .asFile
                    .resolve("reports/cyclonedx")
            )
            outputName.set("bom")
            outputFormat.set("all")

            includeConfigs.set(listOf("runtimeClasspath", "compileClasspath"))
        }

        target.createSBOMVulnScanTask()
        target.createJarVulnScanTask()
    }
}
