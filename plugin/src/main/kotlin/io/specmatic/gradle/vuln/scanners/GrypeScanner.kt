package io.specmatic.gradle.vuln.scanners

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.specmatic.gradle.vuln.scanner.ScanTarget
import io.specmatic.gradle.vuln.scanner.ScanTargetKind
import io.specmatic.gradle.vuln.scanner.ScannerContext
import io.specmatic.gradle.vuln.scanner.VulnerabilitySeverity
import io.specmatic.gradle.vuln.dto.GrypeReport
import java.io.File
import org.kohsuke.github.GHAsset

class GrypeScanner : GithubReleaseBinaryScanner(
    repository = "anchore/grype",
    executableName = "grype",
    homeDirName = ".specmatic-grype",
    lockFileName = "grype-download.lock",
    installDirName = "grype",
    versionFileName = "grype.version",
) {
    override fun commandFor(context: ScannerContext, target: ScanTarget, format: String): List<String> =
        commandPrefix(context, target) +
                listOf(
                    "--quiet",
                    "--only-fixed",
                    "--output",
                    format,
                )

    override fun hasVulnerabilities(
        jsonReportFile: File,
        severities: Set<VulnerabilitySeverity>,
    ): Boolean =
        jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .readValue(jsonReportFile, GrypeReport::class.java)
            .matches
            .asSequence()
            .mapNotNull { it.vulnerability?.severity }
            .mapNotNull { severityFrom(it) }
            .any { it in severities }

    override fun isCompatibleAsset(asset: GHAsset, os: String, arch: String): Boolean {
        val fileName = asset.name.lowercase()
        return fileName.contains("_${os.lowercase()}_$arch") && (fileName.endsWith(".zip") || fileName.endsWith(".tar.gz"))
    }

    private fun grypeSource(target: ScanTarget): String =
        when (target.kind) {
            ScanTargetKind.IMAGE -> target.value
            ScanTargetKind.SBOM -> "sbom:${target.value}"
            ScanTargetKind.ROOTFS -> "dir:${target.value}"
        }

    private fun commandPrefix(context: ScannerContext, target: ScanTarget): List<String> =
        listOf(
            executablePath(context),
            grypeSource(target),
        )

    private fun severityFrom(raw: String): VulnerabilitySeverity? =
        VulnerabilitySeverity.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
}
