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
import org.gradle.api.file.DirectoryProperty
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
import org.gradle.api.logging.Logging
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor

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

            val jsonReportFile = getJsonReportFile()
            runScan(scanner, scanner.commandFor(scannerContext, scanTarget(), "json", extraArgs.get()), jsonReportFile)

            val textReport = scanner.renderTextReport(jsonReportFile)
            getTextTableReportFile().writeText(textReport)
            logger.warn(textReport)
            logger.warn("Vulnerability report file: ${getTextTableReportFile().toURI()}")

            validateNoHighOrCriticalVulnerabilities(scanner, jsonReportFile)
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

internal fun retryVulnerabilityDatabaseUpdate(
    action: () -> Unit,
    wait: (Long) -> Unit = Thread::sleep,
    onRetry: (Int, Long, Exception) -> Unit = { _, _, _ -> },
) {
    var attempt = 1
    var delayMillis = 1_000L
    while (true) {
        try {
            action()
            return
        } catch (exception: Exception) {
            if (attempt == 4) throw exception
            onRetry(attempt, delayMillis, exception)
            try {
                wait(delayMillis)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw interrupted
            }
            attempt++
            delayMillis *= 2
        }
    }
}

@DisableCachingByDefault(because = "Updates an external vulnerability database")
abstract class UpdateVulnerabilityDatabaseTask
    @Inject
    constructor(private val workerExecutor: WorkerExecutor) : DefaultTask() {
        @get:Input
        abstract val scannerTools: ListProperty<VulnScannerType>

        init {
            scannerTools.convention(listOf(VulnScannerType.GRYPE, VulnScannerType.TRIVY))
        }

        @TaskAction
        fun update() {
            val workQueue = workerExecutor.noIsolation()
            scannerTools.get().forEach { scanner ->
                workQueue.submit(UpdateScannerDatabaseWorkAction::class.java) {
                    scannerTool.set(scanner)
                    projectPath.set(this@UpdateVulnerabilityDatabaseTask.path)
                    temporaryDir.set(this@UpdateVulnerabilityDatabaseTask.temporaryDir)
                }
            }
            workQueue.await()
        }
    }

interface UpdateScannerDatabaseParameters : WorkParameters {
    val scannerTool: Property<VulnScannerType>
    val projectPath: Property<String>
    val temporaryDir: DirectoryProperty
}

abstract class UpdateScannerDatabaseWorkAction : WorkAction<UpdateScannerDatabaseParameters> {
    @get:Inject
    abstract val execLauncher: ExecOperations

    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations

    @get:Inject
    abstract val archiveOperations: ArchiveOperations

    override fun execute() {
        val scanner = scannerFor(parameters.scannerTool.get())
        val scannerName = parameters.scannerTool.get().name.lowercase()
        val projectPath = parameters.projectPath.get()
        val context = ScannerContext(
            logger = Logging.getLogger(UpdateScannerDatabaseWorkAction::class.java),
            projectPath = projectPath,
            fileSystemOperations = fileSystemOperations,
            archiveOperations = archiveOperations,
            scannerHomeDir = scanner.homeDir(),
            temporaryDir = parameters.temporaryDir.get().asFile,
        )
        retryVulnerabilityDatabaseUpdate(
            action = { scanner.ensureInstalled(context) },
            onRetry = { attempt, delay, error ->
                context.pluginInfo("Retrying $scannerName installation (attempt ${attempt + 1}) in ${delay}ms: ${error.message}")
            },
        )
        scanner.databaseUpdateCommands(context).forEach { command ->
            retryVulnerabilityDatabaseUpdate(
                action = {
                    execLauncher.exec {
                        commandLine(command)
                        environment(scanner.databaseUpdateEnvironment())
                    }
                },
                onRetry = { attempt, delay, error ->
                    context.pluginInfo("Retrying $scannerName database update (attempt ${attempt + 1}) in ${delay}ms: ${error.message}")
                },
            )
        }
    }
}

internal fun Project.updateVulnerabilityDatabaseTask(): TaskProvider<UpdateVulnerabilityDatabaseTask> {
    val root = rootProject
    return if (root.tasks.findByName("updateVulnerabilityDatabase") == null) {
        root.tasks.register("updateVulnerabilityDatabase", UpdateVulnerabilityDatabaseTask::class.java) {
            group = "verification"
            description = "Updates the vulnerability database used by all vulnerability scans"
        }
    } else {
        root.tasks.named("updateVulnerabilityDatabase", UpdateVulnerabilityDatabaseTask::class.java)
    }
}
