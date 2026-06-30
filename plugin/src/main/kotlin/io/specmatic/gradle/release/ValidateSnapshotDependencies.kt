package io.specmatic.gradle.release

import io.specmatic.gradle.license.pluginWarn
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.tasks.TaskAction

abstract class ValidateSnapshotDependencies : DefaultTask() {
    @TaskAction
    fun assertNoSnapshotDependencies() {
        val allowSnapshotDependencies =
            project.hasProperty("allowSnapshotDependencies") && project.property("allowSnapshotDependencies") == "true"

        val projectToSnapshotDependencies =
            project.allprojects.associateWith { project ->
                (project.configurations + project.buildscript.configurations).flatMap { cfg ->
                    cfg.dependencies.matching { dep ->
                        dep !is ProjectDependency && dep.version?.contains("SNAPSHOT") == true
                    }
                }
            }

        if (projectToSnapshotDependencies.isNotEmpty()) {
            val allPrettyPrintedDependencies =
                projectToSnapshotDependencies
                    .filterValues { it.isNotEmpty() }
                    .map { (project, matchingDependencies) ->
                        "Project $project uses dependencies with SNAPSHOT versions:\n" +
                            matchingDependencies.joinToString(
                                separator = "\n",
                            ) { "- ${it.group}:${it.name}:${it.version}" }
                    }.joinToString(separator = "\n\n")

            if (allPrettyPrintedDependencies.isNotEmpty()) {
                val message =
                    "The following projects have dependencies with SNAPSHOT versions:\n\n$allPrettyPrintedDependencies"
                if (allowSnapshotDependencies) {
                    project.pluginWarn(message)
                } else {
                    throw GradleException(
                        "$message\nPlease remove them before creating a release. Run with `-PallowSnapshotDependencies=true` to disable."
                    )
                }
            } else {
                project.pluginWarn("No SNAPSHOT dependencies found.")
            }
        }
    }
}
