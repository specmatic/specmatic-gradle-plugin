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
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskProvider
import org.gradle.process.ExecOperations
import org.gradle.api.tasks.CacheableTask
import org.gradle.work.DisableCachingByDefault

@CacheableTask
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

            reportsDir.get().mkdirs()

            val formats =
                mapOf(
                    "table" to getTextTableReportFile(),
                    "json" to getJsonReportFile(),
                )

            formats.forEach { (format, output) ->
                runScan(scanner, scanner.commandFor(scannerContext, scanTarget(), format, extraArgs.get()), output)
            }

            logger.warn(getTextTableReportFile().readText())
            logger.warn("Vulnerability report file: ${getTextTableReportFile().toURI()}")

            validateNoHighOrCriticalVulnerabilities(scanner, getJsonReportFile())
        }

        @get:OutputDirectory
        abstract val reportsDir: Property<File>

        @get:InputFiles
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val scannerStateFiles: ConfigurableFileCollection

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

        private fun runScan(scanner: VulnerabilityScanner, cliArgs: List<String>, output: File) {
            try {
                output.outputStream().use { outputStream: FileOutputStream ->
                    logger.warn("[SGP - $path]: $ ${shellEscapedArgs(cliArgs)}")

                    execLauncher.exec {
                        standardOutput = outputStream
                        errorOutput = System.err
                        environment(scanner.databaseUpdateEnvironment())
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

        private fun scannerStrategy(type: VulnScannerType): VulnerabilityScanner = scannerFor(type)
    }

internal fun scannerFor(type: VulnScannerType): VulnerabilityScanner = when (type) {
    VulnScannerType.TRIVY -> TrivyScanner()
    VulnScannerType.GRYPE -> GrypeScanner()
}

internal fun AbstractVulnScanTask.configureScannerState(type: VulnScannerType) {
    val scanner = scannerFor(type)
    scannerStateFiles.from(scanner.databaseFiles(), scanner.versionFile(), scanner.executableFile())
}

@DisableCachingByDefault(because = "Updates an external vulnerability database")
abstract class UpdateVulnerabilityDatabaseTask
    @Inject
    constructor(
        private val execLauncher: ExecOperations,
        private val fileSystemOperations: FileSystemOperations,
        private val archiveOperations: ArchiveOperations,
    ) : DefaultTask() {
        @get:Input
        abstract val scannerTool: Property<VulnScannerType>

        @TaskAction
        fun update() {
            val scanner = scannerFor(scannerTool.get())
            val context = ScannerContext(
                logger = logger,
                projectPath = path,
                fileSystemOperations = fileSystemOperations,
                archiveOperations = archiveOperations,
                scannerHomeDir = scanner.homeDir(),
                temporaryDir = temporaryDir,
            )
            scanner.ensureInstalled(context)
            scanner.databaseUpdateCommands(context).forEach { command ->
                execLauncher.exec {
                    commandLine(command)
                    environment(scanner.databaseUpdateEnvironment())
                }
            }
        }
    }

internal fun Project.updateVulnerabilityDatabaseTask(): TaskProvider<UpdateVulnerabilityDatabaseTask> {
    val root = rootProject
    return if (root.tasks.findByName("updateVulnerabilityDatabase") == null) {
        root.tasks.register("updateVulnerabilityDatabase", UpdateVulnerabilityDatabaseTask::class.java) {
            group = "verification"
            description = "Updates the vulnerability database used by all vulnerability scans"
            scannerTool.set(root.vulnScannerType())
        }
    } else {
        root.tasks.named("updateVulnerabilityDatabase", UpdateVulnerabilityDatabaseTask::class.java)
    }
}

internal fun Project.vulnScannerType(): VulnScannerType = VulnScannerType.from(findProperty("vulnScanner")?.toString())
