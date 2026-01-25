package io.specmatic.gradle.release

import io.specmatic.gradle.CreateGithubReleaseTask
import io.specmatic.gradle.features.BaseDistribution
import io.specmatic.gradle.features.GithubReleaseFeature
import io.specmatic.gradle.license.pluginInfo
import io.specmatic.gradle.license.pluginWarn
import io.specmatic.gradle.specmaticExtension
import io.specmatic.gradle.versioninfo.versionInfo
import org.gradle.BuildAdapter
import org.gradle.BuildResult
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.BasePlugin
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.GradleBuild
import org.gradle.api.tasks.TaskProvider

class SpecmaticReleasePlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.plugins.apply(BasePlugin::class.java)
        target.afterEvaluate {
            target.configureReleaseTasks()
        }
    }
}

const val RELEASE_TASK_NAME = "release"

private fun Project.configureReleaseTasks() {
    val originalGitCommit = project.versionInfo().gitCommit

    val preReleaseCheckTask = getPreReleaseCheckTask()
    val removeSnapshotTask = preReleaseBumpTask(preReleaseCheckTask)
    val runReleaseLifecycleHooksTask = runReleaseLifecycleHooksTask(removeSnapshotTask)
    val createReleaseTagTask = runReleaseTagTask(runReleaseLifecycleHooksTask)
    val postReleaseBumpTask = postReleaseBumpTask(createReleaseTagTask)
    val gitPushTask = gitPushTask(postReleaseBumpTask)
    val createGithubReleaseTask = findOrCreateGithubReleaseTask(gitPushTask)

    val releaseTask =
        project.tasks.register(RELEASE_TASK_NAME) {
            group = RELEASE_TASK_NAME
            description = "Release the project"
            dependsOn(createGithubReleaseTask)
        }

    project.gradle.addBuildListener(
        object : BuildAdapter() {
            @Deprecated("Deprecated in Java")
            override fun buildFinished(result: BuildResult) {
                if (result.failure != null) {
                    val executedTasks =
                        project.gradle.taskGraph.allTasks
                            .map { it.path }
                    val wasMyTaskRun = executedTasks.contains(releaseTask.get().path)
                    pluginWarn("Checking if release task ran: $wasMyTaskRun")
                    if (wasMyTaskRun) {
                        pluginWarn("Rolling back git changes to original commit $originalGitCommit")
                        revertGitChanges(originalGitCommit)
                    }
                }
            }
        },
    )
}

private fun Project.validateSnapshotDependenciesTask(): TaskProvider<ValidateSnapshotDependencies> =
    tasks.register("validateSnapshotDependencies", ValidateSnapshotDependencies::class.java) {
        group = RELEASE_TASK_NAME
        description = "Validate snapshot dependencies"
    }

private fun Project.gitPushTask(runReleaseLifecycleHooksTask: TaskProvider<*>): TaskProvider<*> =
    project.tasks.register("releaseGitPush", GitPushTask::class.java) {
        dependsOn(runReleaseLifecycleHooksTask)
        group = RELEASE_TASK_NAME
        description = "Perform a git push after release"

        rootDir.set(project.rootProject.rootDir)
    }

private fun Project.postReleaseBumpTask(dependentTask: TaskProvider<*>): TaskProvider<*> =
    project.tasks.register("postReleaseBump", PostReleaseBump::class.java) {
        dependsOn(dependentTask)
        group = RELEASE_TASK_NAME
        description = "Post-release bump"

        rootDir.set(project.rootProject.rootDir)
        postReleaseVersion.set(project.provider { project.property("release.newVersion").toString() })
    }

private fun Project.runReleaseLifecycleHooksTask(removeSnapshotTask: TaskProvider<*>): TaskProvider<*> =
    if (project.hasProperty("functionalTestingHack") && project.property("functionalTestingHack") == "true") {
        project.tasks.register("runReleaseLifecycleHooks") {
            dependsOn(removeSnapshotTask)
            group = RELEASE_TASK_NAME
            description = "Run release lifecycle hooks (add your tasks here) - test hax"
        }
    } else {
        val validateSnapshotDependenciesTask = validateSnapshotDependenciesTask()

        val prepareGithubReleaseArtifactsTask =
            tasks.register("prepareGithubReleaseArtifacts", Copy::class.java) {
                group = RELEASE_TASK_NAME
                description = "Prepare artifacts for Github release"

                val targetDir = project.layout.buildDirectory.dir("githubAssets")
                specmaticExtension().projectConfigurations.forEach { (eachProject, eachProjectConfig) ->
                    if (eachProjectConfig is GithubReleaseFeature) {
                        (eachProjectConfig as BaseDistribution).githubRelease.files.forEach { (taskName, releaseFileName) ->
                            dependsOn(eachProject.tasks.named(taskName))
                            from(eachProject.tasks.named(taskName).map { it.outputs.files.singleFile })
                            into(targetDir)
                            rename { releaseFileName }
                        }
                    }
                }
            }

        project.tasks.register("runReleaseLifecycleHooks", GradleBuild::class.java) {
            dependsOn(removeSnapshotTask)
            group = RELEASE_TASK_NAME
            description = "Run release lifecycle hooks (add your tasks here)"

            startParameter = project.gradle.startParameter.newInstance()
            startParameter.projectProperties.putAll(project.gradle.startParameter.projectProperties)
            buildName = "(release-publish)"

            tasks =
                listOf(
                    "clean",
                    validateSnapshotDependenciesTask.name,
                    prepareGithubReleaseArtifactsTask.name,
                    *project.specmaticExtension().releasePublishTasks.toTypedArray(),
                )
        }
    }

private fun Project.runReleaseTagTask(dependentTask: TaskProvider<*>): TaskProvider<*> =
    project.tasks.register("createReleaseTag", CreateReleaseTagTask::class.java) {
        dependsOn(dependentTask)
        group = RELEASE_TASK_NAME
        description = "Create release tag"

        rootDir.set(project.rootProject.rootDir)
        releaseVersion.set(project.provider { project.property("release.releaseVersion").toString() })
    }

private fun Project.preReleaseBumpTask(dependentTask: TaskProvider<*>): TaskProvider<*> =
    project.tasks.register("preReleaseBump", PreReleaseVersionBump::class.java) {
        dependsOn(dependentTask)
        group = RELEASE_TASK_NAME
        description = "Bump version before release"

        rootDir.set(project.rootProject.rootDir)
        releaseVersion.set(project.provider { project.property("release.releaseVersion").toString() })
    }

private fun Project.getPreReleaseCheckTask(): TaskProvider<*> = project.tasks.register("preReleaseCheck", PreReleaseCheck::class.java) {
    group = RELEASE_TASK_NAME
    description = "Pre-release checks"

    rootDir.set(project.rootProject.rootDir)
}

private fun Project.revertGitChanges(originalGitCommit: String) {
    GitOperations(rootProject.rootDir, project.properties, logger).apply {
        pluginInfo("Reverting changes to original commit $originalGitCommit")
        resetHardTo(originalGitCommit)
    }
}

private fun Project.findOrCreateGithubReleaseTask(dependentTasks: TaskProvider<*>): TaskProvider<*> {
    val existingTask = tasks.findByName("createGithubRelease")
    return if (existingTask == null) {
        pluginInfo("Creating createGithubRelease task")
        tasks.register("createGithubRelease", CreateGithubReleaseTask::class.java) {
            dependsOn(dependentTasks)
            group = RELEASE_TASK_NAME
            description = "Create a Github release"
            sourceDir.set(
                project.layout.buildDirectory
                    .dir("githubAssets")
                    .get()
                    .asFile
            )

            releaseVersion.set(project.provider { project.property("release.releaseVersion").toString() })
        }
    } else {
        tasks.named("createGithubRelease")
    }
}
