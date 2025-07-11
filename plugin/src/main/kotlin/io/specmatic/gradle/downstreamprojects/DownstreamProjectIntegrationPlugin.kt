package io.specmatic.gradle.downstreamprojects

import io.specmatic.gradle.specmaticExtension
import java.io.File
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.GradleBuild
import org.gradle.api.tasks.TaskProvider
import org.gradle.util.internal.GUtil

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
        tasks.register("validateDownstreamProjects") {
            group = "verification"
            description = "Validate downstream project(s)"
        }

    val bumpVersionsInDownstreamProjectGradlePropertiesTask =
        tasks.register("bumpVersionsInDownstreamProjects") {
            group = "other"
            description = "Bump versions in downstream project(s)"
        }

    val fetchArtifactsInDownstreamProjectGradlePropertiesTask =
        tasks.register("fetchArtifactsInDownstreamProjects") {
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

private fun Project.getDownstreamProjectDir(eachProject: String): File = file("../$eachProject")

private val Project.specmaticModulePropertyKey: String
    get() = GUtil.toLowerCamelCase(project.name) + "Version"

private val Project.specmaticModuleVersion: Any?
    get() = project.findProperty(specmaticModulePropertyKey)

private fun Project.bumpSpecmaticVersionInProjectTask(
    eachProject: String,
    cloneRepoIfNotExists: TaskProvider<out Task>,
): TaskProvider<Task> = tasks.register("bumpVersion-$eachProject") {
    onlyIf("$specmaticModulePropertyKey is set") {
        project.hasProperty(specmaticModulePropertyKey)
    }
    dependsOn(cloneRepoIfNotExists)

    doFirst {
        println("Bumping $specmaticModulePropertyKey in $eachProject")
        val newValue = "$specmaticModulePropertyKey=$specmaticModuleVersion"
        val gradlePropertiesFile = file("../$eachProject/gradle.properties")
        val content =
            replacePropertyValue(
                gradlePropertiesFile,
                specmaticModulePropertyKey,
                specmaticModuleVersion,
            ).let { updatedContent ->
                if (!updatedContent.lines().any { it.startsWith("$specmaticModulePropertyKey=") }) {
                    println("$specmaticModulePropertyKey property not found, adding")
                    updatedContent + "\n${newValue}\n"
                } else {
                    updatedContent
                }
            }
        gradlePropertiesFile.writeText(content)
    }
}

internal fun replacePropertyValue(file: File, key: String, value: Any?): String = file.readLines().joinToString("\n") { line ->
    if (line.trim().startsWith("#")) {
        line
    } else {
        line.replace(Regex("""^\s*$key\s*=.*"""), "$key=$value")
    }
}

private fun Project.fetchLibsInProjectTask(eachProject: String, cloneRepoIfNotExists: TaskProvider<out Task>,): TaskProvider<Exec?> =
    tasks.register("fetchArtifacts-$eachProject", Exec::class.java) {
        onlyIf("$specmaticModulePropertyKey is set") {
            project.hasProperty(specmaticModulePropertyKey)
        }
        dependsOn(cloneRepoIfNotExists)
        workingDir = getDownstreamProjectDir(eachProject)

        commandLine =
            listOf(
                "../specmatic-github-workflows/bin/fetch-artifacts",
                "-a",
                "${project.group}:${project.name}-min:$specmaticModuleVersion",
                "-r",
                "lib",
            )
    }

private fun Project.validateDownstreamProjectTask(
    eachRepo: String,
    cloneRepoIfNotExists: TaskProvider<out Task>,
): TaskProvider<GradleBuild?> = tasks.register("validate-$eachRepo", GradleBuild::class.java) {
    subprojects.forEach { subproject ->
        dependsOn("${subproject.path}:publishAllPublicationsToStagingRepository")
        dependsOn("${subproject.path}:publishToMavenLocal")
    }

    dependsOn(cloneRepoIfNotExists)

    dir = getDownstreamProjectDir(eachRepo)
    tasks = listOf("check")
    startParameter.projectProperties.put(specmaticModulePropertyKey, version.toString())
    doFirst {
        println("Validating $eachRepo using ./gradlew check -P$specmaticModulePropertyKey=$version")
    }
}

private fun Project.cloneOrUpdateRepoTask(eachRepo: String,): TaskProvider<Exec> = tasks.register("clone-$eachRepo", Exec::class.java) {
    doFirst {
        val downstreamProjectDir = getDownstreamProjectDir(eachRepo)

        if (!downstreamProjectDir.exists()) {
            commandLine("git", "clone", "https://github.com/specmatic/$eachRepo.git", downstreamProjectDir)
        } else {
            // pull the latest changes if the repo already exists
            commandLine("git", "pull", "--ff-only", "--no-rebase")
            workingDir = downstreamProjectDir
        }
    }
}
