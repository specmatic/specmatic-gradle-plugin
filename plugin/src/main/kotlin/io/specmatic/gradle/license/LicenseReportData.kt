package io.specmatic.gradle.license

import groovy.json.JsonSlurper
import java.io.File
import org.gradle.api.GradleException
import org.cyclonedx.model.Component
import org.cyclonedx.model.ExternalReference
import org.cyclonedx.model.License
import org.cyclonedx.parsers.JsonParser

internal object LicenseReportData {
    fun fromSboms(files: Collection<File>, manualLicenseData: Collection<String> = emptyList()): List<Map<String, Any?>> {
        val normalizer = SpdxLicenseNormalizer()
        val overrides = manualLicenseData.mapNotNull { encoded ->
            (JsonSlurper().parseText(encoded) as? Map<*, *>)?.entries
                ?.associate { it.key.toString() to it.value?.toString().orEmpty() }
        }
        return files.flatMap { readComponents(it, normalizer) }
            .groupBy { it["moduleName"] to it["moduleVersion"] }
            .values.map { duplicates ->
                val first = duplicates.first()
                val matchingOverrides = overrides.filter {
                    it["name"] == first["moduleName"] && it["version"] == first["moduleVersion"] && it["license"].orEmpty().isNotBlank()
                }
                val licenses = duplicates.flatMap { it["moduleLicenses"] as List<*> }
                    .distinctBy { license ->
                        val entry = license as Map<*, *>
                        entry["moduleLicense"] to entry["moduleLicenseUrl"]
                    }
                val resolvedLicenses = if (licenses.isNotEmpty()) {
                    licenses
                } else {
                    matchingOverrides.flatMap { override ->
                        val name = override.getValue("license")
                        val url = override["licenseUrl"].orEmpty()
                        val normalized = normalizer.normalize(name, url, null)
                        (normalized.ifEmpty { listOf(com.github.jk1.license.License(name, url)) }).map {
                            mapOf("moduleLicense" to it.name, "moduleLicenseUrl" to (normalizer.canonicalUrl(it.name) ?: it.url.orEmpty()))
                        }
                    }
                }.distinctBy { license ->
                    val entry = license as Map<*, *>
                    entry["moduleLicense"] to entry["moduleLicenseUrl"]
                }
                first + mapOf(
                    "description" to duplicates.firstNotBlank("description"),
                    "projectUrl" to (duplicates.firstNotBlank("projectUrl")
                        .ifBlank { matchingOverrides.firstOverrideValue("projectUrl") }),
                    "scmUrl" to duplicates.firstNotBlank("scmUrl"),
                    "moduleLicenses" to resolvedLicenses,
                )
            }
    }

    private fun readComponents(file: File, licenseNormalizer: SpdxLicenseNormalizer): List<Map<String, Any?>> {
        val bom = try {
            JsonParser().parse(file)
        } catch (e: Exception) {
            throw GradleException("Invalid CycloneDX SBOM: $file", e)
        }
        return bom.components.orEmpty().mapNotNull { component ->
            if (component.type == Component.Type.APPLICATION) return@mapNotNull null
            val group = component.group.orEmpty()
            if (group == "io.specmatic" || group.startsWith("io.specmatic.")) return@mapNotNull null
            val artifactName = component.name.orEmpty()
            if (artifactName.isBlank()) return@mapNotNull null
            val licenses = component.licenses?.items.orEmpty().flatMap { item ->
                item.license?.let { license -> licenseData(license, licenseNormalizer) }
                    ?: item.expression?.value?.let { listOf(expressionData(it, licenseNormalizer)) }
                    ?: item.expressionDetailed?.expression?.let { listOf(expressionData(it, licenseNormalizer)) }
                    ?: emptyList()
            }
            mapOf(
                "moduleName" to (component.purl?.removePrefix("pkg:maven/")?.substringBefore("@")?.replace("/", ":")
                    ?: listOf(group, artifactName).filter(String::isNotBlank).joinToString(":")),
                "description" to component.description.orEmpty(),
                "moduleVersion" to component.version.orEmpty(),
                "moduleLicenses" to licenses,
                "projectUrl" to component.externalReferences.orEmpty()
                    .firstOrNull { it.type == ExternalReference.Type.WEBSITE }?.url.orEmpty(),
                "scmUrl" to component.externalReferences.orEmpty()
                    .firstOrNull { it.type == ExternalReference.Type.VCS }?.url.orEmpty(),
            )
        }
    }

    private fun licenseData(license: License, normalizer: SpdxLicenseNormalizer): List<Map<String, String>> {
        val name = license.id ?: license.name ?: return emptyList()
        val url = license.url.orEmpty()
        val normalized = normalizer.normalize(name, url, license.attachmentText?.text)
        return (normalized.ifEmpty { listOf(com.github.jk1.license.License(name, url)) }).map {
            mapOf("moduleLicense" to it.name, "moduleLicenseUrl" to (normalizer.canonicalUrl(it.name) ?: it.url.orEmpty()))
        }
    }

    private fun expressionData(expression: String, normalizer: SpdxLicenseNormalizer) =
        mapOf("moduleLicense" to expression, "moduleLicenseUrl" to (normalizer.canonicalUrl(expression) ?: ""))

    private fun List<Map<String, Any?>>.firstNotBlank(key: String): String =
        firstNotNullOfOrNull { it[key]?.toString()?.takeIf(String::isNotBlank) }.orEmpty()

    private fun List<Map<String, String>>.firstOverrideValue(key: String): String =
        firstNotNullOfOrNull { it[key]?.takeIf(String::isNotBlank) }.orEmpty()
}
