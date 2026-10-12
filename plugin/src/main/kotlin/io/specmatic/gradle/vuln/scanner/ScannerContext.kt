package io.specmatic.gradle.vuln.scanner

import java.io.File
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.logging.Logger

data class ScannerContext(
    val logger: Logger,
    val projectPath: String,
    val fileSystemOperations: FileSystemOperations,
    val archiveOperations: ArchiveOperations,
    val scannerHomeDir: File,
    val temporaryDir: File,
) {
    fun pluginInfo(message: String) = logger.warn("[SGP - $projectPath]: $message")
}
