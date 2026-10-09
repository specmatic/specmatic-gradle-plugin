package io.specmatic.gradle.downstreamprojects

import io.specmatic.gradle.specmaticExtension
import io.specmatic.gradle.tasks.SpecmaticAggregateTask
import java.io.File
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskProvider

class DownstreamProjectIntegrationPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.afterEvaluate {
            if (specmaticExtension().downstreamDependentProjects.isNotEmpty()) {
                target.defineTasks()
            }
        }
    }
}

private fun Project.defineTasks() {
    val specmaticExtension = specmaticExtension()
    val validateDownstreamProjectTask =
        tasks.register("validateDownstreamProjects", SpecmaticAggregateTask::class.java) {
            group = "verification"
            description = "Validate downstream project(s)"
        }

    val bumpVersionsInDownstreamProjectGradlePropertiesTask =
        tasks.register("bumpVersionsInDownstreamProjects", SpecmaticAggregateTask::class.java) {
            group = "other"
            description = "Bump versions in downstream project(s)"
        }

    val fetchArtifactsInDownstreamProjectGradlePropertiesTask =
        tasks.register("fetchArtifactsInDownstreamProjects", SpecmaticAggregateTask::class.java) {
            group = "other"
            description = "Fetch artifacts downstream project(s)"
        }

    val cloneGithubWorkflowsRepo = cloneOrUpdateRepoTask("specmatic-github-workflows")

    specmaticExtension.downstreamDependentProjects.map { eachProject ->
        val cloneRepoIfNotExists = cloneOrUpdateRepoTask(eachProject)

        val validateTask = validateDownstreamProjectTask(eachProject, cloneRepoIfNotExists)
        validateTask.configure {
            dependsOn(cloneGithubWorkflowsRepo)
        }

        validateDownstreamProjectTask.configure {
            dependsOn(validateTask)
        }

        val bumpVersionTask =
            bumpSpecmaticVersionInProjectTask(
                eachProject,
                cloneRepoIfNotExists,
            )

        val fetchLibsTask =
            fetchLibsInProjectTask(
                eachProject,
                cloneRepoIfNotExists,
            )

        bumpVersionsInDownstreamProjectGradlePropertiesTask.configure {
            dependsOn(bumpVersionTask)
        }

        fetchArtifactsInDownstreamProjectGradlePropertiesTask.configure {
            dependsOn(fetchLibsTask)
        }
    }
}

private val Project.specmaticModulePropertyKey: String
    get() = project.name.toLowerCamelCase() + "Version"

private fun String.toLowerCamelCase(): String =
    split(Regex("[^A-Za-z0-9]+"))
        .filter(String::isNotEmpty)
        .mapIndexed { index, part ->
            if (index == 0) part.replaceFirstChar { it.lowercaseChar() }
            else part.replaceFirstChar { it.uppercaseChar() }
        }.joinToString("")

private fun Project.bumpSpecmaticVersionInProjectTask(
    eachProject: String,
    cloneRepoIfNotExists: TaskProvider<out Task>,
): TaskProvider<BumpDownstreamVersionTask> = tasks.register("bumpVersion-$eachProject", BumpDownstreamVersionTask::class.java) {
    dependsOn(cloneRepoIfNotExists)
    propertyKey.set(specmaticModulePropertyKey)
    moduleVersion.set(providers.gradleProperty(specmaticModulePropertyKey))
    gradlePropertiesFile.set(file("../$eachProject/gradle.properties"))
}

private fun Project.fetchLibsInProjectTask(eachProject: String, cloneRepoIfNotExists: TaskProvider<out Task>): TaskProvider<FetchDownstreamArtifactsTask> =
    tasks.register("fetchArtifacts-$eachProject", FetchDownstreamArtifactsTask::class.java) {
        dependsOn(cloneRepoIfNotExists)
        downstreamProjectDir.set(layout.projectDirectory.dir("../$eachProject"))
        dependencyGroup.set(project.group.toString())
        artifact.set("${project.name}-min")
        moduleVersion.set(providers.gradleProperty(specmaticModulePropertyKey))
    }

internal fun replacePropertyValue(file: File, key: String, value: Any?): String = file.readLines().joinToString("\n") { line ->
    if (line.trim().startsWith("#")) {
        line
    } else {
        line.replace(Regex("""^\s*$key\s*=.*"""), "$key=$value")
    }
}

private fun Project.validateDownstreamProjectTask(eachRepo: String, cloneRepoIfNotExists: TaskProvider<out Task>,): TaskProvider<ValidateDownstreamProjectTask> =
    tasks.register("validate-$eachRepo", ValidateDownstreamProjectTask::class.java) {
        subprojects.forEach { subproject ->
            dependsOn("${subproject.path}:publishAllPublicationsToStagingRepository")
            dependsOn("${subproject.path}:publishToMavenLocal")
        }

        dependsOn(cloneRepoIfNotExists)
        downstreamProjectDir.set(layout.projectDirectory.dir("../$eachRepo"))
        repositoryName.set(eachRepo)
        propertyKey.set(specmaticModulePropertyKey)
        moduleVersion.set(providers.gradleProperty(specmaticModulePropertyKey).orElse("null"))
    }

private fun Project.cloneOrUpdateRepoTask(eachRepo: String): TaskProvider<CloneOrUpdateDownstreamRepositoryTask> =
    tasks.register("clone-$eachRepo", CloneOrUpdateDownstreamRepositoryTask::class.java) {
        downstreamProjectDir.set(layout.projectDirectory.dir("../$eachRepo"))
        repositoryName.set(eachRepo)
        skipClone.set(providers.gradleProperty("skipDownstreamClone").map { true }.orElse(false))
    }
