package io.specmatic.gradle.tasks

import groovy.json.JsonSlurper
import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction

open class PrettyPrintLicenseCheckFailures : DefaultTask() {
    init {
        group = "verification"
        description = "Prints the license check failures in a pretty format"
        outputs.upToDateWhen { false }
    }

    @get:InputFile
    var inputFile: File? = null

    @TaskAction
    fun executeAction() {
        if (inputFile?.exists() != true) {
            return
        }

        val json = JsonSlurper().parse(inputFile) as Map<*, *>

        val unsupportedLicenses = json["dependenciesWithoutAllowedLicenses"] as List<*>

        if (unsupportedLicenses.isNotEmpty()) {
            val unsupportedLicensesGroupedByLicense =
                unsupportedLicenses.groupBy { (it as Map<*, *>)["moduleLicense"] }
            val messages = mutableListOf<String>()

            unsupportedLicensesGroupedByLicense.forEach { (license, modules) ->
                messages.add("License: $license")
                modules.forEach { module ->
                    if (module is Map<*, *>) {
                        messages.add("   - ${module["moduleName"]} (${module["moduleVersion"]})")
                    } else {
                        throw GradleException("Unexpected module format: $module")
                    }
                }
                messages.add("")
            }

            val joinedMessages = messages.joinToString("\n")
            throw GradleException(
                "The following dependencies have licenses that are not allowed:\n\n$joinedMessages\n\nYou may use -xCheckLicense argument to disable license checking."
            )
        }
    }
}
