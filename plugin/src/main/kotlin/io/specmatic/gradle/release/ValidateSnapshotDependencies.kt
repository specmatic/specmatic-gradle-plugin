package io.specmatic.gradle.release

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Inspects the resolved dependency graph for snapshot versions")
abstract class ValidateSnapshotDependencies : DefaultTask() {
    @get:Input
    abstract val snapshotDependencies: MapProperty<String, List<String>>

    @get:Input
    abstract val allowSnapshotDependencies: org.gradle.api.provider.Property<Boolean>

    @TaskAction
    fun assertNoSnapshotDependencies() {
        if (snapshotDependencies.get().isNotEmpty()) {
            val allPrettyPrintedDependencies =
                snapshotDependencies.get()
                    .filterValues { it.isNotEmpty() }
                    .map { (projectPath, matchingDependencies) ->
                        "Project $projectPath uses dependencies with SNAPSHOT versions:\n" +
                            matchingDependencies.joinToString(
                                separator = "\n",
                            ) { "- $it" }
                    }.joinToString(separator = "\n\n")

            if (allPrettyPrintedDependencies.isNotEmpty()) {
                val message =
                    "The following projects have dependencies with SNAPSHOT versions:\n\n$allPrettyPrintedDependencies"
                if (allowSnapshotDependencies.get()) {
                    logger.warn(message)
                } else {
                    throw GradleException(
                        "$message\nPlease remove them before creating a release. Run with `-PallowSnapshotDependencies=true` to disable."
                    )
                }
            } else {
                logger.warn("No SNAPSHOT dependencies found.")
            }
        }
    }
}
