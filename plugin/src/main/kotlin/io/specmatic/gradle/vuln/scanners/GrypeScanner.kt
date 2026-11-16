package io.specmatic.gradle.vuln.scanners

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.specmatic.gradle.vuln.dto.GrypeReport
import io.specmatic.gradle.vuln.scanner.ScanTarget
import io.specmatic.gradle.vuln.scanner.ScanTargetKind
import io.specmatic.gradle.vuln.scanner.ScannerContext
import io.specmatic.gradle.vuln.scanner.VulnerabilitySeverity
import java.io.File
import org.apache.commons.lang3.SystemUtils
import org.kohsuke.github.GHAsset

class GrypeScanner :
    GithubReleaseBinaryScanner(
        repository = "anchore/grype",
        executableName = "grype",
        homeDirName = ".specmatic-grype",
        lockFileName = "grype-download.lock",
        installDirName = "grype",
        versionFileName = "grype.version",
    ) {
    override fun databaseCacheDirectory(): File = SystemUtils.getUserHome().resolve(".cache/grype/db")

    override fun versionFile(): File = homeDir().resolve("grype.version")

    override fun databaseFiles(): List<File> = listOf(databaseCacheDirectory())

    override fun databaseUpdateCommands(context: ScannerContext): List<List<String>> = listOf(
        listOf(executablePath(context), "db", "update"),
    )

    override fun databaseUpdateEnvironment(): Map<String, String> = mapOf(
        "GRYPE_DB_CACHE_DIR" to databaseCacheDirectory().path,
        "GRYPE_DB_AUTO_UPDATE" to "false",
    )

    override fun commandFor(context: ScannerContext, target: ScanTarget, format: String, extraArgs: List<String>): List<String> = commandPrefix(context, target) +
        listOf(
            "--quiet",
            "--only-fixed",
            "--output",
            format,
        ) + extraArgs

    override fun renderTextReport(jsonReportFile: File): String {
        val report = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .readValue(jsonReportFile, GrypeReport::class.java)

        return renderTable(
            listOf("NAME", "INSTALLED", "FIXED-IN", "TYPE", "VULNERABILITY", "SEVERITY"),
            report.matches.map { match ->
                listOf(
                    match.artifact?.name.orEmpty(),
                    match.artifact?.version.orEmpty(),
                    match.vulnerability?.fix?.versions?.joinToString(", ").orEmpty(),
                    match.artifact?.type.orEmpty(),
                    match.vulnerability?.id.orEmpty(),
                    match.vulnerability?.severity.orEmpty(),
                )
            },
        )
    }

    override fun hasVulnerabilities(jsonReportFile: File, severities: Set<VulnerabilitySeverity>,): Boolean = jacksonObjectMapper()
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

    private fun grypeSource(target: ScanTarget): String = when (target.kind) {
        ScanTargetKind.IMAGE -> target.value
        ScanTargetKind.SBOM -> "sbom:${target.value}"
        ScanTargetKind.ROOTFS -> "dir:${target.value}"
    }

    private fun commandPrefix(context: ScannerContext, target: ScanTarget): List<String> = listOf(
        executablePath(context),
        grypeSource(target),
    )

    private fun severityFrom(raw: String): VulnerabilitySeverity? =
        VulnerabilitySeverity.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
}
