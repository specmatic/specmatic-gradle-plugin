package io.specmatic.gradle.vuln.scanner

import java.io.File
import org.gradle.api.Project

data class ScannerContext(
    val project: Project,
    val scannerHomeDir: File,
    val temporaryDir: File,
)
