package io.specmatic.gradle.vuln.scanners

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.specmatic.gradle.vuln.dto.VulnerabilityReport
import io.specmatic.gradle.vuln.scanner.ScanTarget
import io.specmatic.gradle.vuln.scanner.ScanTargetKind
import io.specmatic.gradle.vuln.scanner.ScannerContext
import io.specmatic.gradle.vuln.scanner.VulnerabilitySeverity
import java.io.File
import org.kohsuke.github.GHAsset

class TrivyScanner : GithubReleaseBinaryScanner(
    repository = "aquasecurity/trivy",
    executableName = "trivy",
    homeDirName = ".specmatic-trivy",
    lockFileName = "trivy-download.lock",
    installDirName = "trivy",
    versionFileName = "trivy.version",
) {
    override fun commandFor(context: ScannerContext, target: ScanTarget, format: String): List<String> =
        commandPrefix(context, target) +
                listOf(
                    "--ignore-unfixed",
                    "--quiet",
                    "--no-progress",
                    "--format",
                    format,
                ) +
                target.value

    override fun hasVulnerabilities(
        jsonReportFile: File,
        severities: Set<VulnerabilitySeverity>,
    ): Boolean {
        val report: VulnerabilityReport =
            jacksonObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .readValue(jsonReportFile, VulnerabilityReport::class.java)

        return report.results
            .asSequence()
            .flatMap { it.vulnerabilities.asSequence() }
            .mapNotNull { it.severity }
            .mapNotNull { severity -> severityFrom(severity) }
            .any { it in severities }
    }

    override fun isCompatibleAsset(asset: GHAsset, os: String, arch: String): Boolean {
        val trivyOs = if (os == "darwin") "macos" else os
        val trivyArch = if (arch == "amd64") "64bit" else arch
        val fileName = asset.name.lowercase()

        return fileName.contains("_${trivyOs.lowercase()}-$trivyArch") &&
                (fileName.endsWith(".zip") || fileName.endsWith(".tar.gz"))
    }

    private fun trivyTargetCommand(kind: ScanTargetKind): String =
        when (kind) {
            ScanTargetKind.IMAGE -> "image"
            ScanTargetKind.SBOM -> "sbom"
            ScanTargetKind.ROOTFS -> "rootfs"
        }

    private fun commandPrefix(context: ScannerContext, target: ScanTarget): List<String> =
        listOf(
            executablePath(context),
            trivyTargetCommand(target.kind),
        )

    private fun severityFrom(raw: String): VulnerabilitySeverity? =
        VulnerabilitySeverity.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
}
