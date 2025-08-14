package io.specmatic.gradle.jar.publishing

import io.specmatic.gradle.jar.massage.jar
import io.specmatic.gradle.jar.massage.publishing
import io.specmatic.gradle.jar.massage.shadow
import io.specmatic.gradle.license.pluginInfo
import org.gradle.api.NamedDomainObjectProvider
import org.gradle.api.Project
import org.gradle.api.component.SoftwareComponent
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
            JavaComponentPublisher(project, artifactIdentifier, components["java"]),
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
                        "Configuring publication named \\${name()} for artifact '\\${publication.groupId}:\\${publication.artifactId}:\\${publication.version}' using task \\${task.get().path}",
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
                            val dependencies = topLevel.appendNode("dependencies")
                            val allDependencies =
                                configurations["compileClasspath"].allDependencies -
                                    configurations.shadow.get().allDependencies
                            allDependencies.forEach {
                                val dependency = dependencies.appendNode("dependency")
                                dependency.appendNode("groupId", it.group)
                                // If this is a project dependency and the project publishes a -min artifact, use the -min suffix
                                val artifactId =
                                    if (it is org.gradle.api.artifacts.ProjectDependency) {
                                        // Convention: the obfuscated artifactId is project.name + "-min"
                                        "${it.dependencyProject.name}-min"
                                    } else {
                                        it.name
                                    }
                                dependency.appendNode("artifactId", artifactId)
                                dependency.appendNode("version", it.version)
                                dependency.appendNode("scope", "compile")
                            }
                        }
                    }
                }

                override fun name(): String = artifactIdentifier
            },
        )
    createConfigurationAndAddArtifacts(publication.name, task)
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

class JavaComponentPublisher(
    private val project: Project,
    private val artifactIdentifier: String,
    private val component: SoftwareComponent,
) : PublishingConfigurer {
    override fun configure(publication: MavenPublication) {
        publication.from(component)
        publication.artifactId = artifactIdentifier
        project.pluginInfo(
            "Configuring publication named ${name()} for artifact '${publication.groupId}:${publication.artifactId}:${publication.version}' using component ${component.name}",
        )
    }

    override fun name(): String = artifactIdentifier
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
