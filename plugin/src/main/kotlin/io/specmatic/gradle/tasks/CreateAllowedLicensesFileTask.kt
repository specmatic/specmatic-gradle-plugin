package io.specmatic.gradle.tasks

import groovy.json.JsonBuilder
import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

private val ALLOWED_LICENSES =
    setOf(
        "0BSD",
        "Apache-2.0",
        "BSD-2-Clause",
        "BSD-3-Clause",
        "Bouncy Castle Licence",
        "CC0-1.0",
        "CDDL-1.0",
        "CDDL-1.1",
        "EDL-1.0",
        "EPL-1.0",
        "EPL-2.0",
        "LGPL-2.1-only",
        "MIT",
        "MIT-0",
        "MPL-2.0",
        "Public-Domain",
        "Similar to Apache License but with the acknowledgment clause removed",
    )

// Returns a file with the default allowed licenses. Structure is as follows:
// {
//     "allowedLicenses": [
//         {
//             "moduleLicense": "MIT",
//             "moduleName": ".*"
//         },
//         {
//             "moduleLicense": "Apache-2.0",
//             "moduleName": ".*"
//         },
//         ...
//     ]
// }
open class CreateAllowedLicensesFileTask : DefaultTask() {
    init {
        group = "verification"
        description = "Creates a file with the allowed licenses to be consumed by the gradle license plugin"
    }

    @TaskAction
    fun get() {
        if (outputFile == null) {
            throw GradleException("Output file is not specified")
        }
        createDefaultAllowedLicensesFile(outputFile!!, allowedLicenses)
    }

    @get:OutputFile
    var outputFile: File? = null

    @get:Input
    var allowedLicenses = setOf<String>()
}

internal fun Project.createDefaultAllowedLicensesFile(): File =
    createDefaultAllowedLicensesFile(project.defaultAllowedLicensesFile(), project.allowedLicenses())

fun createDefaultAllowedLicensesFile(allowedLicensesFile: File, allowedLicenses: Set<String>): File {
    val allowedLicensesDocument =
        allowedLicenses.map { eachLicense ->
            mapOf(
                "moduleLicense" to eachLicense,
                "moduleName" to ".*",
            )
        }

    allowedLicensesFile.writeText(
        JsonBuilder(mapOf("allowedLicenses" to allowedLicensesDocument)).toPrettyString(),
    )
    return allowedLicensesFile
}

internal fun Project.defaultAllowedLicensesFile(): File {
    val buildDir =
        project.layout.buildDirectory
            .get()
            .asFile
    buildDir.mkdirs()
    return File(buildDir, "allowed-licenses.json")
}

internal fun Project.allowedLicenses(): Set<String> {
    val extraAllowedLicenses = this.properties["extraAllowedLicenses"]
    return if (extraAllowedLicenses != null) {
        ALLOWED_LICENSES +
            extraAllowedLicenses
                .toString()
                .split(";;")
                .map { it.trim() }
                .toSet()
    } else {
        ALLOWED_LICENSES
    }
}
