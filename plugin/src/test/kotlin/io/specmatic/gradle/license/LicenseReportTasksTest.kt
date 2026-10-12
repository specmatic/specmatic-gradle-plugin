package io.specmatic.gradle.license

import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LicenseReportTasksTest {
    @TempDir
    lateinit var temporaryDirectory: File

    @Test
    fun generatesHtmlFromSbomComponentData() {
        val sbomJson = temporaryDirectory.resolve("bom.json")
        sbomJson.writeText(
            """{"components":[{"type":"library","group":"org.example","name":"allowed","version":"1.0","description":"Example package description","purl":"pkg:maven/org.example/allowed@1.0","externalReferences":[{"type":"vcs","url":"https://github.com/example/allowed"}],"licenses":[{"license":{"id":"Apache-2.0","url":"https://www.apache.org/licenses/LICENSE-2.0.txt"}}]}]}""",
        )
        val task = ProjectBuilder.builder().withProjectDir(temporaryDirectory).build()
            .tasks.create("generateLicenseReport", GenerateLicenseReportTask::class.java)
        val outputDirectory = temporaryDirectory.resolve("reports")
        task.sbomFiles.from(sbomJson)
        task.reportTitle.set("test-project")
        task.projectVersion.set("1.2.3")
        task.outputDirectory.set(outputDirectory)

        task.generate()

        assertThat(outputDirectory.resolve("index.html").readText())
            .contains("org.example:allowed")
            .contains("Example package description")
            .contains("href=\"https://github.com/example/allowed\">https://github.com/example/allowed</a>")
            .contains("href=\"https://spdx.org/licenses/Apache-2.0.html\">Apache-2.0</a>")
        assertThat(outputDirectory.resolve("inventory.html").readText()).contains("Apache-2.0")
    }

    @Test
    fun mergesRepeatedComponentsAcrossSbomsBeforeReporting() {
        val firstSbom = temporaryDirectory.resolve("first-bom.json").apply {
            writeText(
                """{"components":[{"type":"library","group":"org.example","name":"shared","version":"1.0","licenses":[{"license":{"id":"MIT","url":"https://opensource.org/licenses/MIT"}}]}]}""",
            )
        }
        val secondSbom = temporaryDirectory.resolve("second-bom.json").apply {
            writeText(
                """{"components":[{"type":"library","group":"org.example","name":"shared","version":"1.0","licenses":[{"license":{"id":"Apache-2.0","url":"https://www.apache.org/licenses/LICENSE-2.0"}}]}]}""",
            )
        }
        val task = ProjectBuilder.builder().withProjectDir(temporaryDirectory).build()
            .tasks.create("generateLicenseReport", GenerateLicenseReportTask::class.java)
        val outputDirectory = temporaryDirectory.resolve("merged-reports")
        task.sbomFiles.from(firstSbom, secondSbom)
        task.reportTitle.set("test-project")
        task.projectVersion.set("1.2.3")
        task.outputDirectory.set(outputDirectory)

        task.generate()

        val report = outputDirectory.resolve("index.html").readText()
        assertThat(Regex("<tr><td>org.example:shared</td>").findAll(report).count()).isEqualTo(1)
        assertThat(report).contains("MIT", "Apache-2.0")
    }

    @Test
    fun allowsComponentWhenOneOfItsDeclaredLicensesIsAllowed() {
        val sbomJson = temporaryDirectory.resolve("bom-with-license-options.json")
        sbomJson.writeText(
            """{"components":[{"type":"library","group":"org.example","name":"dual-licensed","version":"1.0","licenses":[{"license":{"id":"LGPL-3.0-or-later"}},{"license":{"id":"Apache-2.0"}}]}]}""",
        )
        val task = ProjectBuilder.builder().withProjectDir(temporaryDirectory).build()
            .tasks.create("generateLicenseReport", GenerateLicenseReportTask::class.java)
        task.sbomFiles.from(sbomJson)
        task.reportTitle.set("test-project")
        task.projectVersion.set("1.2.3")
        task.outputDirectory.set(temporaryDirectory.resolve("reports-with-license-options"))

        task.generate()

        assertThat(temporaryDirectory.resolve("reports-with-license-options/index.html").readText())
            .contains("LGPL-3.0-or-later", "Apache-2.0")
    }

    @Test
    fun reportsComponentsWithoutLicenseMetadataButCheckRejectsThem() {
        val sbomJson = temporaryDirectory.resolve("bom.json")
        sbomJson.writeText("""{"components":[{"type":"library","group":"org.example","name":"unknown","version":"1.0","licenses":[]}]}""")
        val task = ProjectBuilder.builder().withProjectDir(temporaryDirectory).build()
            .tasks.create("generateLicenseReport", GenerateLicenseReportTask::class.java)
        val outputDirectory = temporaryDirectory.resolve("reports")
        task.sbomFiles.from(sbomJson)
        task.reportTitle.set("test-project")
        task.projectVersion.set("1.2.3")
        task.outputDirectory.set(outputDirectory)

        task.generate()

        val checkTask = ProjectBuilder.builder().withProjectDir(temporaryDirectory).build()
            .tasks.create("checkLicense", CheckLicenseTask::class.java)
        checkTask.sbomFiles.from(sbomJson)
        checkTask.allowedLicenses.set(listOf("Apache-2.0"))
        assertThatThrownBy { checkTask.check() }
            .hasMessageContaining("org.example:unknown")
            .hasMessageContaining("Unknown")

        assertThat(outputDirectory.resolve("index.html").readText()).contains("org.example:unknown")
    }

}
