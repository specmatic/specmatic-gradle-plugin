package io.specmatic.gradle.release

import io.specmatic.gradle.CreateGithubReleaseTask
import io.specmatic.gradle.features.BaseDistribution
import io.specmatic.gradle.features.GithubReleaseFeature
import io.specmatic.gradle.license.pluginInfo
import io.specmatic.gradle.tasks.SpecmaticAggregateTask
import io.specmatic.gradle.specmaticExtension
import io.specmatic.gradle.versioninfo.versionInfo
import javax.inject.Inject
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.build.event.BuildEventsListenerRegistry
import org.gradle.api.plugins.BasePlugin
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.GradleBuild
import org.gradle.api.tasks.TaskProvider

class SpecmaticReleasePlugin @Inject constructor(
    private val buildEventsListenerRegistry: BuildEventsListenerRegistry,
) : Plugin<Project> {
    override fun apply(target: Project) {
        target.plugins.apply(BasePlugin::class.java)
        target.afterEvaluate {
            target.configureReleaseTasks(buildEventsListenerRegistry)
        }
    }
}

const val RELEASE_TASK_NAME = "release"

private fun Project.configureReleaseTasks(buildEventsListenerRegistry: BuildEventsListenerRegistry) {
    val originalGitCommit = project.versionInfo().gitCommit
    val releaseOutcomeService =
        project.gradle.sharedServices.registerIfAbsent(
            "specmaticReleaseOutcome",
            SpecmaticReleaseOutcomeService::class.java,
        ) {
            parameters.rootDir.set(project.layout.projectDirectory)
            parameters.originalGitCommit.set(originalGitCommit)
        }
    buildEventsListenerRegistry.onTaskCompletion(releaseOutcomeService)
    val preReleaseCheckTask = getPreReleaseCheckTask()
    val removeSnapshotTask = preReleaseBumpTask(preReleaseCheckTask, releaseOutcomeService)
    val runReleaseLifecycleHooksTask = runReleaseLifecycleHooksTask(removeSnapshotTask)
    val createReleaseTagTask = runReleaseTagTask(runReleaseLifecycleHooksTask)
    val postReleaseBumpTask = postReleaseBumpTask(createReleaseTagTask)
    val gitPushTask = gitPushTask(postReleaseBumpTask)
    val createGithubReleaseTask = findOrCreateGithubReleaseTask(gitPushTask)

    project.tasks.register(RELEASE_TASK_NAME, SpecmaticAggregateTask::class.java) {
        group = RELEASE_TASK_NAME
        description = "Release the project"
        dependsOn(createGithubReleaseTask)
    }
}

private fun Project.validateSnapshotDependenciesTask(): TaskProvider<ValidateSnapshotDependencies> =
    tasks.register("validateSnapshotDependencies", ValidateSnapshotDependencies::class.java) {
        group = RELEASE_TASK_NAME
        description = "Validate snapshot dependencies"
        allowSnapshotDependencies.set(findProperty("allowSnapshotDependencies") == "true")
        snapshotDependencies.set(
            allprojects.associate { eachProject ->
                val dependencies =
                    (eachProject.configurations + eachProject.buildscript.configurations).flatMap { configuration ->
                        configuration.dependencies
                            .filter { dependency ->
                                dependency !is org.gradle.api.artifacts.ProjectDependency &&
                                    dependency.version?.contains("SNAPSHOT") == true
                            }.map { dependency ->
                                "${dependency.group}:${dependency.name}:${dependency.version}"
                            }
                    }
                eachProject.path to dependencies
            }
        )
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
        postReleaseVersion.set(providers.gradleProperty("release.newVersion"))
    }

private fun Project.runReleaseLifecycleHooksTask(removeSnapshotTask: TaskProvider<*>,): TaskProvider<*> =
    if (project.hasProperty("functionalTestingHack") && project.property("functionalTestingHack") == "true") {
        project.tasks.register("runReleaseLifecycleHooks", SpecmaticAggregateTask::class.java) {
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
        releaseVersion.set(providers.gradleProperty("release.releaseVersion"))
    }

private fun Project.preReleaseBumpTask(
    dependentTask: TaskProvider<*>,
    releaseOutcomeService: org.gradle.api.provider.Provider<SpecmaticReleaseOutcomeService>,
): TaskProvider<*> =
    project.tasks.register("preReleaseBump", PreReleaseVersionBump::class.java) {
        usesService(releaseOutcomeService)
        dependsOn(dependentTask)
        group = RELEASE_TASK_NAME
        description = "Bump version before release"

        rootDir.set(project.rootProject.rootDir)
        releaseVersion.set(providers.gradleProperty("release.releaseVersion"))
        currentVersion.set(project.version.toString())
    }

private fun Project.getPreReleaseCheckTask(): TaskProvider<*> = project.tasks.register("preReleaseCheck", PreReleaseCheck::class.java) {
    group = RELEASE_TASK_NAME
    description = "Pre-release checks"

    rootDir.set(project.rootProject.rootDir)
    projectProperties.set(
        listOf("skipBranchCheck", "skipRepoDirtyCheck", "skipIncomingOutgoingCheck")
            .mapNotNull { key -> project.findProperty(key)?.let { key to it.toString() } }
            .toMap(),
    )
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
