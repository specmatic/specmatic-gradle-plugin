package io.specmatic.gradle.jar.publishing

import groovy.util.Node
import io.specmatic.gradle.features.CommercialApplicationAndLibraryFeature
import io.specmatic.gradle.features.CommercialLibraryFeature
import io.specmatic.gradle.features.OSSApplicationAndLibraryFeature
import io.specmatic.gradle.features.OSSApplicationFeature
import io.specmatic.gradle.features.OSSLibraryFeature
import io.specmatic.gradle.jar.massage.jar
import io.specmatic.gradle.jar.massage.publishing
import io.specmatic.gradle.jar.massage.shadow
import io.specmatic.gradle.license.pluginInfo
import io.specmatic.gradle.specmaticExtension
import org.gradle.api.GradleException
import org.gradle.api.NamedDomainObjectProvider
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.ExcludeRule
import org.gradle.api.artifacts.ModuleDependency
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.TaskProvider
import org.gradle.jvm.tasks.Jar
import org.gradle.kotlin.dsl.get

internal fun Project.forceJavadocAndSourcesJars() {
    extensions.configure(JavaPluginExtension::class.java) {
        withJavadocJar()
        withSourcesJar()
    }
}

internal fun Project.createUnobfuscatedJarPublication(artifactIdentifier: String): NamedDomainObjectProvider<MavenPublication> {
    val jarTask = tasks.jar
    val publication =
        publishJar(
            object : PublishingConfigurer {
                override fun configure(publication: MavenPublication) {
                    val component = components["java"]
                    pluginInfo(
                        "Configuring publication named ${name()} for artifact '${publication.groupId}:${publication.artifactId}:${publication.version}' using component ${component.name}",
                    )
                    publication.artifact(jarTask) {
                        // we keep the classifier when building the jar, because we need to distinguish between the original and obfuscated jars, in the `lib` dir.
                        // but we remove the classifier when publishing, because we don't want the classifier in the published jar name.
                        classifier = null
                    }

                    val distribution = project.specmaticExtension().projectConfigurations[project]

                    if (distribution is OSSLibraryFeature || distribution is OSSApplicationAndLibraryFeature ||
                        distribution is OSSApplicationFeature
                    ) {
                        publication.artifact(project.tasks.named("sourcesJar")) {
                            classifier = "sources"
                        }
                        publication.artifact(project.tasks.named("javadocJar")) {
                            classifier = "javadoc"
                        }
                    }

                    publication.artifactId = artifactIdentifier
                    publication.pom {
                        withXml {
                            val topLevel = asNode()
                            val dependenciesNode = topLevel.appendNode("dependencies")
                            val projectDeps =
                                configurations["compileClasspath"].allDependencies - configurations.shadow.get().allDependencies
                            val globalExcludeRules = configurations["implementation"].excludeRules
                            processDependencies(projectDeps, dependenciesNode, globalExcludeRules, false)
                        }
                    }
                }

                override fun name(): String = artifactIdentifier
            },
        )
    createConfigurationAndAddArtifacts(publication.name, jarTask)
    return publication
}

internal fun Project.createObfuscatedOriginalJarPublication(task: TaskProvider<out Jar>, artifactIdentifier: String) {
    val publication =
        publishJar(
            object : PublishingConfigurer {
                override fun configure(publication: MavenPublication) {
                    pluginInfo(
                        "Configuring publication named ${name()} for artifact '${publication.groupId}:${publication.artifactId}:${publication.version}' using task ${task.get().path}",
                    )
                    publication.artifact(task) {
                        // we keep the classifier when building the jar, because we need to distinguish between the original and obfuscated jars, in the `lib` dir.
                        // but we remove the classifier when publishing, because we don't want the classifier in the published jar name.
                        classifier = null
                    }
                    publication.artifactId = artifactIdentifier
                    publication.pom {
                        withXml {
                            val topLevel = asNode()
                            val dependenciesNode = topLevel.appendNode("dependencies")
                            val projectDeps =
                                configurations["compileClasspath"].allDependencies - configurations.shadow.get().allDependencies
                            val globalExcludeRules = configurations["implementation"].excludeRules

                            processDependencies(projectDeps, dependenciesNode, globalExcludeRules, true)
                        }
                    }
                }

                override fun name(): String = artifactIdentifier
            },
        )
    createConfigurationAndAddArtifacts(publication.name, task)
}

private fun Project.processDependencies(
    projectDeps: Set<Dependency>,
    dependenciesNode: Node,
    globalExcludeRules: Set<ExcludeRule>,
    obfuscated: Boolean,
) {
    projectDeps.forEach { eachDependency ->
        val dependency = dependenciesNode.appendNode("dependency")

        dependency.appendNode("groupId", eachDependency.group)
        handleGroupId(eachDependency, dependency, obfuscated)
        dependency.appendNode("version", eachDependency.version)
        dependency.appendNode("scope", "runtime")

        addExcludes(eachDependency, globalExcludeRules, dependency)
    }
}

private fun Project.handleGroupId(eachDependency: Dependency, dependency: Node, obfuscated: Boolean) {
    if (eachDependency is ProjectDependency) {
        val projectDependency = rootProject.project(eachDependency.path)
        val distribution = rootProject.specmaticExtension().projectConfigurations[projectDependency]
        when (distribution) {
            is CommercialLibraryFeature ->
                if (obfuscated) {
                    dependency.appendNode("artifactId", "${projectDependency.name}-min")
                } else {
                    dependency.appendNode("artifactId", "${projectDependency.name}-dont-use-this-unless-you-know-what-you-are-doing")
                }

            is CommercialApplicationAndLibraryFeature -> {
                if (obfuscated) {
                    dependency.appendNode("artifactId", projectDependency.name)
                } else {
                    dependency.appendNode("artifactId", "${projectDependency.name}-dont-use-this-unless-you-know-what-you-are-doing")
                }
            }

            is OSSLibraryFeature, is OSSApplicationAndLibraryFeature -> {
                dependency.appendNode("artifactId", projectDependency.name)
            }

            else -> throw GradleException("Don't know how to express dependency on project ${eachDependency.name} in ${this.name}")
        }
    } else {
        dependency.appendNode("artifactId", eachDependency.name)
    }
}

private fun addExcludes(eachDependency: Dependency, globalExcludeRules: Set<ExcludeRule>, dependency: Node) {
    if (eachDependency is ModuleDependency) {
        val excludeRules = HashSet<ExcludeRule>() + globalExcludeRules + eachDependency.excludeRules
        if (excludeRules.isNotEmpty()) {
            val exclusionsNode = dependency.appendNode("exclusions")
            excludeRules.forEach { rule ->
                val exclusionNode = exclusionsNode.appendNode("exclusion")
                exclusionNode.appendNode("groupId", rule.group)
                exclusionNode.appendNode("artifactId", rule.module)
            }
        }
    }
}

internal fun Project.createShadowedObfuscatedJarPublication(task: TaskProvider<out Jar>, artifactIdentifier: String) {
    publishJar(ArtifactPublishingConfigurer(project, artifactIdentifier, task))
    createConfigurationAndAddArtifacts(task)
}

internal fun Project.createShadowedUnobfuscatedJarPublication(
    task: TaskProvider<out Jar>,
    artifactIdentifier: String,
): NamedDomainObjectProvider<MavenPublication> {
    val publication = publishJar(ArtifactPublishingConfigurer(project, artifactIdentifier, task))
    createConfigurationAndAddArtifacts(task)
    return publication
}

private fun Project.createConfigurationAndAddArtifacts(configurationName: String, artifactTask: TaskProvider<out Jar>) {
    pluginInfo("Creating configuration $configurationName")
    val configuration =
        configurations.create(configurationName) {
            extendsFrom(configurations["runtimeClasspath"])
            isTransitive = configurations["runtimeClasspath"].isTransitive
            isCanBeResolved = configurations["runtimeClasspath"].isCanBeResolved
            isCanBeConsumed = configurations["runtimeClasspath"].isCanBeConsumed
        }
    pluginInfo("Adding output of ${artifactTask.get().path} to artifact named ${configuration.name}")
    artifacts.add(configuration.name, artifactTask)
}

interface PublishingConfigurer {
    fun configure(publication: MavenPublication)

    fun name(): String
}

class ArtifactPublishingConfigurer(
    private val project: Project,
    private val artifactIdentifier: String,
    private val task: TaskProvider<out Jar>,
) : PublishingConfigurer {
    override fun configure(publication: MavenPublication) {
        project.pluginInfo(
            "Configuring publication named ${name()} for artifact '${publication.groupId}:${publication.artifactId}:${publication.version}' using task ${task.get().path}",
        )
        publication.artifact(task) {
            // we keep the classifier when building the jar, because we need to distinguish between the original and obfuscated jars, in the `lib` dir.
            // but we remove the classifier when publishing, because we don't want the classifier in the published jar name.
            classifier = null
        }
        publication.artifactId = artifactIdentifier
    }

    override fun name(): String = artifactIdentifier
}

private fun Project.publishJar(configurer: PublishingConfigurer): NamedDomainObjectProvider<MavenPublication> =
    publishing.publications.register(configurer.name(), MavenPublication::class.java) {
        pom.packaging = "jar"

        configurer.configure(this)
    }

private fun Project.createConfigurationAndAddArtifacts(taskProvider: TaskProvider<out Jar>) {
    val shadowOriginalJarConfig = configurations.create(taskProvider.name)
    pluginInfo("Adding output of ${taskProvider.get().path} to artifact named ${shadowOriginalJarConfig.name}")
    artifacts.add(shadowOriginalJarConfig.name, taskProvider)
}
