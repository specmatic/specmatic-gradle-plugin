package io.specmatic.gradle.vuln.scanner

import org.gradle.api.GradleException

enum class VulnScannerType {
    TRIVY,
    GRYPE,
    ;

    companion object {
        fun from(value: String?): VulnScannerType = if (value.isNullOrBlank()) {
            GRYPE
        } else {
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: throw GradleException("Unsupported vulnScanner '$value'. Supported values are: trivy, grype.")
        }
    }
}
