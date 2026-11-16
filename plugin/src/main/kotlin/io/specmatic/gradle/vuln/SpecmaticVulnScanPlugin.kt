package io.specmatic.gradle.vuln

import io.specmatic.gradle.tasks.SpecmaticAggregateTask
import org.cyclonedx.gradle.CyclonedxDirectTask
import org.gradle.api.Plugin
import org.gradle.api.Project

internal fun Project.disableVulnScanTasks() {
    tasks.withType(AbstractVulnScanTask::class.java).configureEach {
        enabled = false
        setDependsOn(emptyList<Any>())
    }
}

class SpecmaticVulnScanPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.updateVulnerabilityDatabaseTask()
        val root = target.rootProject
        val vulnScan =
            if (root.tasks.findByName("vulnScan") == null) {
                root.tasks.register("vulnScan", SpecmaticAggregateTask::class.java) {
                    group = "verification"
                    description = "Runs all vulnerability scans in this build"
                }
            } else {
                root.tasks.named("vulnScan", SpecmaticAggregateTask::class.java)
            }
        target.tasks.withType(AbstractVulnScanTask::class.java).all {
            val scanTask = this
            vulnScan.configure { dependsOn(scanTask) }
        }

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
