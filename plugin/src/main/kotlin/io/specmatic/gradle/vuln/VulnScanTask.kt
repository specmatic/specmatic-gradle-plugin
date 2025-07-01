package io.specmatic.gradle.vuln

import io.specmatic.gradle.exec.shellEscapedArgs
import io.specmatic.gradle.license.pluginInfo
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.URL
import javax.inject.Inject
import org.apache.commons.io.FileUtils
import org.apache.commons.lang3.SystemUtils
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import org.kohsuke.github.GitHubBuilder

@CacheableTask
abstract class AbstractVulnScanTask
    @Inject
    constructor(private val execLauncher: ExecOperations) : DefaultTask() {
        @TaskAction
        fun vulnScan() {
            maybeDownloadTrivy()

            reportsDir.get().mkdirs()

            val formats =
                mapOf(
                    "table" to getTextTableReportFile(),
                    "json" to getJsonReportFile(),
                )

            formats.map { (format, output) -> runScan(format, output) }
        }

        @get:OutputDirectory
        abstract val reportsDir: Property<File>

        @OutputFile
        fun getJsonReportFile(): File = reportsDir.get().resolve("report.json")

        @OutputFile
        fun getTextTableReportFile(): File = reportsDir.get().resolve("report.txt")

        @get:OutputDirectory
        abstract val trivyHomeDir: Property<File>

        private fun runScan(format: String, output: File): Boolean {
            try {
                output.outputStream().use { outputStream: FileOutputStream ->
                    val cliArgs = getCommandLine(format)
                    project.pluginInfo("$ ${shellEscapedArgs(cliArgs)}")

                    val result = execLauncher.exec {
                        standardOutput = outputStream
                        errorOutput = System.err
                        commandLine = cliArgs
                        isIgnoreExitValue = true
                    }

                    if (result.exitValue != 0) {
                        printReportFile(project, output)
                        throw GradleException("Found HIGH or CRITICAL vulnerabilities")
                    }
                }
            } catch (e: Exception) {
                error("Failed to run Trivy scan: ${e.message}")
            }
            return true
        }

        abstract fun getCommandLine(format: String): List<String>

        private fun maybeDownloadTrivy() {
            trivyHomeDir.get().mkdirs()

            // acquire a file lock to prevent multiple tasks from trying to download trivy at the same time
            val lockFile = trivyHomeDir.get().resolve("trivy-download.lock")
            RandomAccessFile(lockFile, "rw").channel.use { channel ->
                channel.lock().use {
                    val lastModified = if (trivyExecutableFile().exists()) trivyExecutableFile().lastModified() else 0L
                    val oneWeekInMillis = 7 * 24 * 60 * 60 * 1000L
                    val isOlderThanOneWeek = System.currentTimeMillis() - lastModified > oneWeekInMillis
                    if (isOlderThanOneWeek) {
                        downloadTrivy()
                    }
                }
            }
        }

        private fun trivyInstallDir(): File = trivyHomeDir.get().resolve("trivy")

        private fun trivyVersionFile(): File = trivyHomeDir.get().resolve("trivy.version")

        private fun downloadTrivy() {
            project.pluginInfo("Checking if trivy is up to date")
            val gitHub = GitHubBuilder().build()
            val repository = gitHub.getRepository("aquasecurity/trivy")
            val release = repository.latestRelease

            val currentVersion = if (trivyVersionFile().exists()) trivyVersionFile().readText() else "unknown"

            if (currentVersion != release.name) {
                val asset =
                    release.listAssets().find {
                        it.name.lowercase().contains("_$os-$arch") &&
                            (
                                it.name
                                    .lowercase()
                                    .endsWith(".zip") ||
                                    it.name.lowercase().endsWith(".tar.gz")
                            )
                    } ?: throw RuntimeException("No asset found for trivy for $os $arch")
                val trivyCompressedDownloadPath = temporaryDir.resolve(asset.name)
                val downloadUrl = asset.browserDownloadUrl

                project.pluginInfo(
                    "Currently installed trivy version($currentVersion) is not up-to-date. Downloading version ${release.name} from $downloadUrl to $trivyCompressedDownloadPath"
                )
                FileUtils.copyURLToFile(URL(downloadUrl), trivyCompressedDownloadPath)
                project.delete(trivyInstallDir())
                trivyInstallDir().mkdirs()
                project.copy {
                    if (trivyCompressedDownloadPath.extension == "zip") {
                        from(project.zipTree(trivyCompressedDownloadPath))
                    } else {
                        from(project.tarTree(trivyCompressedDownloadPath))
                    }
                    into(trivyInstallDir())
                }

                trivyVersionFile().writeText(release.name)
            }
        }

        @get:Input
        val os: String
            get() =
                when {
                    SystemUtils.IS_OS_WINDOWS -> "windows"
                    SystemUtils.IS_OS_MAC -> "macos"
                    SystemUtils.IS_OS_LINUX -> "linux"
                    else -> throw RuntimeException("Unsupported operating system for trivy: ${SystemUtils.OS_NAME}")
                }

        @get:Input
        val arch: String
            get() {
                val osArch = SystemUtils.OS_ARCH.lowercase()

                return when {
                    (osArch.contains("x86") || osArch.contains("amd64")) && osArch.contains("64") -> "64bit"
                    osArch.contains("aarch") && osArch.contains("64") -> "arm64"
                    else -> throw GradleException("Unsupported architecture for trivy: $osArch")
                }
            }

        protected fun trivyExecutable(): String = trivyExecutableFile().path

        private fun trivyExecutableFile(): File {
            val extension = if (os == "windows") ".exe" else ""
            return trivyInstallDir().resolve("trivy$extension")
        }

        @get:Input
        protected val commonArgs: Array<String>
            get() =
                arrayOf(
                    "--exit-code", "1",
                    "--severity", "HIGH,CRITICAL",
                    "--ignore-unfixed",
                    "--quiet",
                    "--no-progress",
                )
    }

internal fun trivyHomeDir(): File = SystemUtils.getUserHome().resolve(".specmatic-trivy")

internal fun printReportFile(project: Project, reportFile: File) {
    project.logger.warn(reportFile.readText())
    project.logger.warn("Vulnerability report file: ${reportFile.toURI()}")
}
