package io.specmatic.gradle.vuln

import io.specmatic.gradle.tasks.SpecmaticAggregateTask
import io.specmatic.gradle.vuln.scanner.VulnScannerType
import org.cyclonedx.gradle.CyclonedxDirectTask
import org.gradle.api.Plugin
import org.gradle.api.Project

internal fun Project.disableVulnScanTasks() {
    tasks.withType(AbstractVulnScanTask::class.java).configureEach {
        enabled = false
        setDependsOn(emptyList<Any>())
        reportsDir.set(layout.buildDirectory.dir("reports/vulnerabilities/disabled/$name").get().asFile)
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
        val aggregateReport =
            if (root.tasks.findByName("aggregateVulnerabilityReport") == null) {
                root.tasks.register("aggregateVulnerabilityReport", AggregateVulnerabilityReportTask::class.java) {
                    group = "verification"
                    description = "Combines vulnerability reports from all scanners and projects"
                    rootProjectDirectory.set(root.layout.projectDirectory)
                    reportFile.set(root.layout.buildDirectory.file("reports/vulnerabilities/report.txt"))
                    reportFiles.from(
                        root.allprojects.flatMap { project ->
                            VulnScannerType.entries.flatMap { scanner ->
                                listOf("jars", "sbom", "image").map { target ->
                                    project.layout.buildDirectory
                                        .file("reports/vulnerabilities/${scanner.name.lowercase()}/$target/report.txt")
                                        .get()
                                        .asFile
                                }
                            }
                        },
                    )
                }
            } else {
                root.tasks.named("aggregateVulnerabilityReport", AggregateVulnerabilityReportTask::class.java)
            }
        vulnScan.configure { finalizedBy(aggregateReport) }
        target.tasks.withType(AbstractVulnScanTask::class.java).all {
            val scanTask = this
            vulnScan.configure { dependsOn(scanTask) }
            scanTask.finalizedBy(aggregateReport)
            aggregateReport.configure { mustRunAfter(scanTask) }
        }
        target.tasks.named("check") { dependsOn(vulnScan) }

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
