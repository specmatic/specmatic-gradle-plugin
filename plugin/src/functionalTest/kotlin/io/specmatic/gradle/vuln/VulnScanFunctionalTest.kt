package io.specmatic.gradle.vuln

import io.specmatic.gradle.AbstractFunctionalTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class VulnScanFunctionalTest : AbstractFunctionalTest() {
    @Test
    fun `vulnScan aggregates jar and SBOM scans`() {
        buildFile.writeText(
            """
            plugins {
                id("java")
                id("io.specmatic.gradle")
            }
            """.trimIndent(),
        )

        val result = runWithSuccess("vulnScan", "--dry-run")

        assertThat(result.output)
            .contains(":vulnScanJarScan SKIPPED")
            .contains(":vulnScanSBOMScan SKIPPED")
            .contains(":updateVulnerabilityDatabase SKIPPED")
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
