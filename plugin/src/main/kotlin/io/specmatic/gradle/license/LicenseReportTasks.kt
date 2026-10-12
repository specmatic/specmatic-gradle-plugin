package io.specmatic.gradle.license

import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.CacheableTask

@CacheableTask
abstract class GenerateLicenseReportTask : DefaultTask() {
    init {
        manualLicenseData.convention(emptyList())
    }

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sbomFiles: ConfigurableFileCollection

    @get:Input
    abstract val reportTitle: Property<String>

    @get:Input
    abstract val projectVersion: Property<String>

    @get:Input
    abstract val manualLicenseData: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val dependencies = LicenseReportData.fromSboms(sbomFiles.files, manualLicenseData.get())
        val outputDir = outputDirectory.get().asFile.apply { mkdirs() }
        File(outputDir, "index.html").writeText(renderReport(dependencies))
        File(outputDir, "inventory.html").writeText(renderInventory(dependencies))
    }

    private fun renderReport(dependencies: List<Any?>): String = buildString {
        append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>")
        append(html("Dependency License Report for " + reportTitle.get()))
        append("</title></head><body><h1>")
        append(html("Dependency License Report for " + reportTitle.get() + " " + projectVersion.get()))
        append("</h1><table><thead><tr><th>Dependency</th><th>Description</th><th>Version</th><th>License</th><th>Project URL</th></tr></thead><tbody>")
        dependencies.forEach { dependency ->
            val item = dependency as? Map<*, *> ?: return@forEach
            val licenses = (item["moduleLicenses"] as? List<*>).orEmpty()
            appendRow(item, licenses.filterIsInstance<Map<*, *>>())
        }
        append("</tbody></table></body></html>")
    }

    private fun StringBuilder.appendRow(item: Map<*, *>, licenses: List<Map<*, *>>) {
        append("<tr><td>").append(html(item["moduleName"].toString()))
            .append("</td><td>").append(html(item["description"]?.toString().orEmpty()))
            .append("</td><td>").append(html(item["moduleVersion"].toString())).append("</td><td>")
        licenses.forEachIndexed { index, entry ->
            if (index > 0) append(", ")
            val license = entry["moduleLicense"].toString()
            val licenseUrl = entry["moduleLicenseUrl"]?.toString().orEmpty()
            if (licenseUrl.isBlank()) append(html(license))
            else append("<a href=\"").append(html(licenseUrl)).append("\">").append(html(license)).append("</a>")
        }
        append("</td><td>")
        val projectUrl = item["projectUrl"]?.toString()?.takeIf(String::isNotBlank)
            ?: item["scmUrl"]?.toString().orEmpty()
        if (projectUrl.isBlank()) append("")
        else append("<a href=\"").append(html(projectUrl)).append("\">").append(html(projectUrl)).append("</a>")
        append("</td></tr>")
    }

    private fun renderInventory(dependencies: List<Any?>): String = buildString {
        append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>License Inventory</title></head><body>")
        append("<h1>License Inventory for ").append(html(reportTitle.get())).append("</h1><ul>")
        val inventory = dependencies.flatMap { dependency ->
            val item = dependency as? Map<*, *> ?: return@flatMap emptyList()
            val licenses = (item["moduleLicenses"] as? List<*>).orEmpty()
            if (licenses.isEmpty()) listOf("" to item["moduleName"].toString())
            else licenses.map { license ->
                (license as? Map<*, *>)?.get("moduleLicense")?.toString().orEmpty() to item["moduleName"].toString()
            }
        }.groupBy({ it.first }, { it.second }).toSortedMap()
        inventory.forEach { (license, modules) ->
            append("<li><strong>").append(html(license.ifBlank { "Unknown" })).append("</strong>: ")
                .append(html(modules.sorted().joinToString(", "))).append("</li>")
        }
        append("</ul></body></html>")
    }

    private fun html(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&#39;")

}

@CacheableTask
abstract class CheckLicenseTask : DefaultTask() {
    init {
        manualLicenseData.convention(emptyList())
    }

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sbomFiles: ConfigurableFileCollection

    @get:Input
    abstract val allowedLicenses: ListProperty<String>

    @get:Input
    abstract val manualLicenseData: ListProperty<String>

    @TaskAction
    fun check() {
        val disallowed = LicenseReportData.fromSboms(sbomFiles.files, manualLicenseData.get()).filter { dependency ->
            val licenses = (dependency["moduleLicenses"] as List<*>).filterIsInstance<Map<*, *>>()
            licenses.isEmpty() || licenses.none { it["moduleLicense"] in allowedLicenses.get() }
        }
        if (disallowed.isNotEmpty()) throw GradleException(
            "The following dependencies have licenses that are not allowed:\n" + disallowed.joinToString("\n") {
                "${it["moduleName"]} (${it["moduleVersion"]}): " +
                    (it["moduleLicenses"] as List<*>).joinToString { (it as Map<*, *>)["moduleLicense"].toString() }.ifEmpty { "Unknown" }
            },
        )
    }
}
