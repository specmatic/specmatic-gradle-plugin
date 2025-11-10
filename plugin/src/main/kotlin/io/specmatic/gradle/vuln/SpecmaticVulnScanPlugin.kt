package io.specmatic.gradle.vuln

import org.cyclonedx.gradle.CyclonedxDirectTask
import org.gradle.api.Plugin
import org.gradle.api.Project

class SpecmaticVulnScanPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.tasks.register("cyclonedxBom", CyclonedxDirectTask::class.java) {
            group = "Reporting"
            description = "Generates a CycloneDX compliant Software Bill of Materials (SBOM)"

            includeBomSerialNumber.set(false)
            jsonOutput.set(
                project.layout.buildDirectory
                    .get()
                    .asFile
                    .resolve("reports/cyclonedx/bom.json"),
            )
            xmlOutput.set(
                project.layout.buildDirectory
                    .get()
                    .asFile
                    .resolve("reports/cyclonedx/bom.xml"),
            )

            includeConfigs.set(listOf("runtimeClasspath"))
        }

        target.createSBOMVulnScanTask()
        target.createJarVulnScanTask()
    }
}
