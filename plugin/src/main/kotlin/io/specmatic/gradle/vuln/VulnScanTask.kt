package io.specmatic.gradle.vuln

import io.specmatic.gradle.exec.shellEscapedArgs
import io.specmatic.gradle.vuln.scanner.ScanTarget
import io.specmatic.gradle.vuln.scanner.ScannerContext
import io.specmatic.gradle.vuln.scanner.VulnScannerType
import io.specmatic.gradle.vuln.scanner.VulnerabilitySeverity
import io.specmatic.gradle.vuln.scanners.GrypeScanner
import io.specmatic.gradle.vuln.scanners.TrivyScanner
import io.specmatic.gradle.vuln.scanners.VulnerabilityScanner
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Vulnerability databases and scan results can change outside Gradle inputs")
abstract class AbstractVulnScanTask
    @Inject
    constructor(private val execLauncher: ExecOperations) : DefaultTask() {
        init {
            extraArgs.convention(emptyList())
            skipVulnValidation.convention(false)
        }

        @TaskAction
        fun vulnScan() {
            val scanner = scannerStrategy(scannerTool.get())
            val scannerContext = scannerContext(scanner)
            scanner.ensureInstalled(scannerContext)

            reportsDir.get().mkdirs()

            val formats =
                mapOf(
                    "table" to getTextTableReportFile(),
                    "json" to getJsonReportFile(),
                )

            formats.forEach { (format, output) ->
                runScan(scanner.commandFor(scannerContext, scanTarget(), format, extraArgs.get()), output)
            }

            logger.warn(getTextTableReportFile().readText())
            logger.warn("Vulnerability report file: ${getTextTableReportFile().toURI()}")

            validateNoHighOrCriticalVulnerabilities(scanner, getJsonReportFile())
        }

        @get:OutputDirectory
        abstract val reportsDir: Property<File>

        @OutputFile
        fun getJsonReportFile(): File = reportsDir.get().resolve("report.json")

        @OutputFile
        fun getTextTableReportFile(): File = reportsDir.get().resolve("report.txt")

        @get:Input
        abstract val scannerTool: Property<VulnScannerType>

        @get:Input
        abstract val extraArgs: ListProperty<String>

        @get:Input
        abstract val skipVulnValidation: Property<Boolean>

        @get:Inject
        abstract val fileSystemOperations: FileSystemOperations

        @get:Inject
        abstract val archiveOperations: ArchiveOperations

        private fun runScan(cliArgs: List<String>, output: File) {
            try {
                output.outputStream().use { outputStream: FileOutputStream ->
                    logger.warn("[SGP - $path]: $ ${shellEscapedArgs(cliArgs)}")

                    execLauncher.exec {
                        standardOutput = outputStream
                        errorOutput = System.err
                        commandLine = cliArgs
                    }
                }
            } catch (e: Exception) {
                throw GradleException("Failed to run ${scannerTool.get().name.lowercase()} scan", e)
            }
        }

        private fun validateNoHighOrCriticalVulnerabilities(scanner: VulnerabilityScanner, jsonReportFile: File,) {
            if (skipVulnValidation.get()) {
                logger.warn("[SGP - $path]: Skipping vulnerability severity validation as per project properties.")
                return
            }

            val severities = setOf(VulnerabilitySeverity.CRITICAL, VulnerabilitySeverity.HIGH)
            if (scanner.hasVulnerabilities(jsonReportFile = jsonReportFile, severities = severities)) {
                throw GradleException("Vulnerabilities with severity [CRITICAL, HIGH] found in the scan.")
            }
        }

        abstract fun scanTarget(): ScanTarget

        private fun scannerContext(scanner: VulnerabilityScanner): ScannerContext = ScannerContext(
            logger = logger,
            projectPath = path,
            fileSystemOperations = fileSystemOperations,
            archiveOperations = archiveOperations,
            scannerHomeDir = scanner.homeDir(),
            temporaryDir = temporaryDir,
        )

        private fun scannerStrategy(type: VulnScannerType): VulnerabilityScanner = when (type) {
            VulnScannerType.TRIVY -> TrivyScanner()
            VulnScannerType.GRYPE -> GrypeScanner()
        }
    }

internal fun Project.vulnScannerType(): VulnScannerType = VulnScannerType.from(findProperty("vulnScanner")?.toString())
