package io.specmatic.gradle.vuln.scanner

data class ScanTarget(
    val kind: ScanTargetKind,
    val value: String,
)
