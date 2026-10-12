package io.specmatic.gradle.license

import io.specmatic.gradle.AbstractFunctionalTest
import java.util.jar.JarOutputStream
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test

class LicenseReportingFunctionalTest : AbstractFunctionalTest() {
    @Test
    fun licenseReportAndAllowlistCheckAreConfigurationCacheCompatible() {
        writeBuild("MIT")

        val firstRun = runWithSuccess("checkLicense")
        assertThat(firstRun.output).contains("Configuration cache entry stored")
        assertThat(firstRun.task(":generateLicenseReport")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(firstRun.task(":checkLicense")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(projectDir.resolve("build/reports/dependency-license/index.html").readText())
            .contains("org.example:manual", "MIT")
        assertThat(projectDir.resolve("build/reports/dependency-license/inventory.html").readText()).contains("MIT")

    }

    @Test
    fun checkRejectsGplLicense() {
        writeBuild("GPL-3.0-only")

        val result = runWithFailure("check")

        assertThat(result.output).contains("GPL-3.0-only")
    }

    @Test
    fun checkRejectsRealAgplLibraryFromMavenCentral() {
        buildFile.writeText(
            """
            plugins {
                java
                id("io.specmatic.gradle")
            }

            repositories { mavenCentral() }
            dependencies { implementation("com.intergral.deep:agent-api:1.2.8") }
            """.trimIndent(),
        )

        val result = runWithFailure("check")

        assertThat(result.output).contains("AGPL-3.0-only")
    }

    @Test
    fun licenseDataDslSuppliesMetadataMissingFromSbom() {
        writeBuild("Apache-2.0", licenseInPom = "")

        val result = runWithSuccess("checkLicense")

        assertThat(result.task(":generateLicenseReport")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        val report = projectDir.resolve("build/reports/dependency-license/index.html").readText()
        assertThat(report).contains("Apache-2.0", "https://example.org/manual", "https://spdx.org/licenses/Apache-2.0.html")
        val dependencyRow = Regex("<tr><td>org\\.example:manual</td>.*?</tr>").find(report)?.value.orEmpty()
        assertThat(Regex(">Apache-2.0</a>").findAll(dependencyRow).count()).isEqualTo(1)
    }

    @Test
    fun licenseReportCheckRejectsDisallowedLicense() {
        writeBuild("Not-Allowed")

        val report = runWithSuccess("generateLicenseReport")
        assertThat(report.task(":generateLicenseReport")?.outcome).isEqualTo(TaskOutcome.SUCCESS)
        assertThat(projectDir.resolve("build/reports/dependency-license/index.html").readText()).contains("Not-Allowed")

        val result = runWithFailure("checkLicense")

        assertThat(result.output).contains("Not-Allowed")
    }

    @Test
    fun licenseReportCheckRejectsMissingLicense() {
        writeBuild("")

        val result = runWithFailure("checkLicense")

        assertThat(result.output).contains("org.example:manual")
    }

    private fun writeBuild(license: String, licenseInPom: String = license) {
        val parentDir = projectDir.resolve("repo/org/example/parent/1.0").apply { mkdirs() }
        parentDir.resolve("parent-1.0.pom").writeText(
            """
            <project><modelVersion>4.0.0</modelVersion><groupId>org.example</groupId><artifactId>parent</artifactId>
            <version>1.0</version><packaging>pom</packaging><build><plugins><plugin><groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId><configuration><source>17</source></configuration>
            </plugin></plugins></build></project>
            """.trimIndent(),
        )
        val artifactDir = projectDir.resolve("repo/org/example/manual/1.0").apply { mkdirs() }
        val licenseUrl = when (licenseInPom) {
            "MIT" -> "https://opensource.org/licenses/MIT"
            "GPL-3.0-only" -> "https://www.gnu.org/licenses/gpl-3.0.html"
            else -> "https://example.org/license"
        }
        val licenseBlock = if (licenseInPom.isBlank()) "" else """
            <licenses><license><name>$licenseInPom</name><url>$licenseUrl</url></license></licenses>
        """.trimIndent()
        artifactDir.resolve("manual-1.0.pom").writeText(
            """
            <project><modelVersion>4.0.0</modelVersion><parent><groupId>org.example</groupId><artifactId>parent</artifactId>
            <version>1.0</version></parent><groupId>org.example</groupId><artifactId>manual</artifactId><version>1.0</version>
            <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId>
            <configuration><target>17</target></configuration></plugin></plugins></build>$licenseBlock</project>
            """.trimIndent(),
        )
        JarOutputStream(artifactDir.resolve("manual-1.0.jar").outputStream()).use { }
        buildFile.writeText(
            """
            plugins {
                java
                id("io.specmatic.gradle")
            }

            repositories { maven { url = uri("repo") } }
            dependencies { implementation("org.example:manual:1.0") }

            specmatic {
                licenseData {
                    name = "org.example:manual"
                    version = "1.0"
                    license = "$license"
                    projectUrl = "https://example.org/manual"
                    licenseUrl = "https://example.org/manual-license"
                }
            }
            """.trimIndent(),
        )
    }
}
