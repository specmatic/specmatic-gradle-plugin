package io.specmatic.gradle.license

import groovy.json.JsonOutput
import io.specmatic.gradle.extensions.SpecmaticGradleExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

class SpecmaticLicenseReportingPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginInfo("Configuring license reporting")
        val reportDirectory = project.layout.buildDirectory.dir("reports/dependency-license")
        val generateReportTask =
            project.tasks.register("generateLicenseReport", GenerateLicenseReportTask::class.java) {
                group = "Reporting"
                description = "Generates license reports for project dependencies and subprojects"
                outputDirectory.set(reportDirectory)
            }
        val checkLicenseTask =
            project.tasks.register("checkLicense", CheckLicenseTask::class.java) {
                group = "verification"
                description = "Checks that dependency licenses are allowed"
                dependsOn(generateReportTask)
                this.allowedLicenses.set(allowedLicenses)
            }

        project.rootProject.allprojects {
            val module = this
            plugins.withId("java") {
                checkLicenseTask.configure {
                    dependsOn(module.tasks.named("cyclonedxBom"))
                    sbomFiles.from(module.layout.buildDirectory.file("reports/cyclonedx/bom.json"))
                }
                generateReportTask.configure {
                    dependsOn(module.tasks.named("cyclonedxBom"))
                    sbomFiles.from(module.layout.buildDirectory.file("reports/cyclonedx/bom.json"))
                }
            }
        }

        project.plugins.withId("base") {
            project.tasks.named("check") {
                dependsOn(checkLicenseTask)
            }
        }

        project.afterEvaluate {
            val extension = project.extensions.getByType(SpecmaticGradleExtension::class.java)
            val manualLicenseData = extension.licenseData
                .filter { it.name.isNotBlank() && it.version.isNotBlank() && it.license.isNotBlank() }
                .map {
                    JsonOutput.toJson(
                        mapOf(
                            "name" to it.name,
                            "version" to it.version,
                            "projectUrl" to it.projectUrl,
                            "license" to it.license,
                            "licenseUrl" to it.licenseUrl,
                        ),
                    )
                }
            generateReportTask.configure {
                reportTitle.set(project.rootProject.name)
                projectVersion.set(project.rootProject.version.toString())
                this.manualLicenseData.set(manualLicenseData)
            }
            checkLicenseTask.configure {
                this.allowedLicenses.set(ALLOWED_LICENSES + extension.additionalAllowedLicenses)
                this.manualLicenseData.set(manualLicenseData)
            }
        }
    }

    companion object {
        private val ALLOWED_LICENSES =
            setOf(
                "0BSD",
                "Apache-2.0",
                "BSD-2-Clause",
                "BSD-3-Clause",
                "BSD-4-Clause",
                "Bouncy Castle Licence",
                "CC0-1.0",
                "CDDL-1.0",
                "CDDL-1.1",
                "EDL-1.0",
                "EPL-1.0",
                "EPL-2.0",
                "LGPL-2.1-only",
                "LGPL-3.0-only",
                "MIT",
                "MIT-0",
                "MPL-2.0",
                "Public-Domain",
                "Go License",
                "Similar to Apache License but with the acknowledgment clause removed",
                "Public Domain"
            )
    }
}

fun Project.pluginInfo(string: String) {
    logger.warn("[SGP - " + this.path + "]: " + string)
}

fun Project.pluginWarn(string: String) {
    logger.warn("[SGP - " + this.path + "]: " + string)
}
