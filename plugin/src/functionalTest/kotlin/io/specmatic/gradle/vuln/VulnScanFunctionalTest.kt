package io.specmatic.gradle.vuln

import io.specmatic.gradle.AbstractFunctionalTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class VulnScanFunctionalTest : AbstractFunctionalTest() {
    @Test
    fun `vulnScan aggregates jar and SBOM scans`() {
        projectDir.resolve("module-a").mkdirs()
        projectDir.resolve("module-b").mkdirs()
        settingsFile.writeText("rootProject.name = \"example\"\ninclude(\"module-a\", \"module-b\")")
        buildFile.writeText(
            """
            plugins {
                id("java")
                id("io.specmatic.gradle")
            }

            allprojects {
                repositories { mavenCentral() }
            }

            subprojects {
                apply(plugin = "java")
            }
            """.trimIndent(),
        )

        val result = runWithSuccess("vulnScan", "--dry-run", "--configuration-cache")

        assertThat(result.output)
            .contains(":module-a:vulnScanJarScan SKIPPED")
            .contains(":module-a:vulnScanJarTrivyScan SKIPPED")
            .contains(":module-a:vulnScanSBOMScan SKIPPED")
            .contains(":module-a:vulnScanSBOMTrivyScan SKIPPED")
            .contains(":module-b:vulnScanJarScan SKIPPED")
            .contains(":module-b:vulnScanJarTrivyScan SKIPPED")
            .contains(":module-b:vulnScanSBOMScan SKIPPED")
            .contains(":module-b:vulnScanSBOMTrivyScan SKIPPED")
            .contains(":updateVulnerabilityDatabase SKIPPED")
            .contains(":aggregateVulnerabilityReport SKIPPED")

        val update = result.output.indexOf(":updateVulnerabilityDatabase SKIPPED")
        assertThat(update).isLessThan(result.output.indexOf(":module-a:vulnScanJarScan SKIPPED"))
        assertThat(update).isLessThan(result.output.indexOf(":module-b:vulnScanSBOMScan SKIPPED"))
        assertThat(Regex(":updateVulnerabilityDatabase SKIPPED").findAll(result.output).count()).isEqualTo(1)
    }

    @Test
    fun `aggregate vulnerability report combines scanner reports`() {
        val reportsDir = projectDir.resolve("build/reports/vulnerabilities")
        reportsDir.resolve("grype/jars/report.txt").apply {
            parentFile.mkdirs()
            writeText("GRYPE JAR FINDING")
        }
        reportsDir.resolve("trivy/sbom/report.txt").apply {
            parentFile.mkdirs()
            writeText("TRIVY SBOM FINDING")
        }

        buildFile.writeText(
            """
            plugins {
                id("java")
                id("io.specmatic.gradle")
            }
            """.trimIndent(),
        )

        val result = runWithSuccess("aggregateVulnerabilityReport")
        val combinedReport = reportsDir.resolve("report.txt")

        assertThat(combinedReport).exists()
        assertThat(combinedReport.readText())
            .contains("grype/jars/report.txt", "GRYPE JAR FINDING", "trivy/sbom/report.txt", "TRIVY SBOM FINDING")
        assertThat(result.output).contains("Combined vulnerability report file:")
    }

    @Test
    fun `disableVulnScan skips scans and their prerequisite work`() {
        buildFile.writeText(
            """
            plugins {
                id("java")
                id("io.specmatic.gradle")
            }

            specmatic {
                withOSSApplication(rootProject) {
                    mainClass = "example.Main"
                    dockerBuild()
                    disableVulnScan()
                }
            }
            """.trimIndent(),
        )

        val result = runWithSuccess("check", "vulnScan", "--dry-run")

        assertThat(result.output)
            .contains(":vulnScanJarScan SKIPPED")
            .contains(":vulnScanSBOMScan SKIPPED")
            .contains(":vulnScanDockerScan SKIPPED")
            .doesNotContain(":updateVulnerabilityDatabase")
            .doesNotContain(":dockerBuild SKIPPED")
    }
}
