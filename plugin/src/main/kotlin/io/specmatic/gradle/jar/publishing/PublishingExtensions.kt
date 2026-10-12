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
import io.specmatic.gradle.versioninfo.SpecmaticArtifactType
import io.specmatic.gradle.versioninfo.versionInfo
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
import org.gradle.api.XmlProvider
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
                override fun artifactType(): SpecmaticArtifactType = SpecmaticArtifactType.ORIGINAL

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
                    val dependencies =
                        pomDependencies(
                            configurations["compileClasspath"].allDependencies - configurations.shadow.get().allDependencies,
                            configurations["implementation"].excludeRules,
                            obfuscated = false,
                        )
                    publication.pom.withXml(AppendPomDependenciesAction(dependencies))
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
                override fun artifactType(): SpecmaticArtifactType = SpecmaticArtifactType.OBFUSCATED_SLIM

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
                    val dependencies =
                        pomDependencies(
                            configurations["compileClasspath"].allDependencies - configurations.shadow.get().allDependencies,
                            configurations["implementation"].excludeRules,
                            obfuscated = true,
                        )
                    publication.pom.withXml(AppendPomDependenciesAction(dependencies))
                }

                override fun name(): String = artifactIdentifier
            },
        )
    createConfigurationAndAddArtifacts(publication.name, task)
}

private data class PomDependency(
    val groupId: String?,
    val artifactId: String,
    val version: String?,
    val exclusions: List<Pair<String?, String?>>,
) : java.io.Serializable

private class AppendPomDependenciesAction(private val dependencies: List<PomDependency>) : org.gradle.api.Action<XmlProvider> {
    override fun execute(xmlProvider: XmlProvider) {
        appendPomDependencies(xmlProvider.asNode(), dependencies)
    }
}

private fun Project.pomDependencies(
    projectDeps: Set<Dependency>,
    globalExcludeRules: Set<ExcludeRule>,
    obfuscated: Boolean,
): List<PomDependency> = projectDeps.map { dependency ->
    val artifactId = if (dependency is ProjectDependency) {
        val dependencyProject = rootProject.project(dependency.path)
        when (rootProject.specmaticExtension().projectConfigurations[dependencyProject]) {
            is CommercialLibraryFeature -> if (obfuscated) "${dependencyProject.name}-min" else "${dependencyProject.name}-dont-use-this-unless-you-know-what-you-are-doing"
            is CommercialApplicationAndLibraryFeature -> if (obfuscated) dependencyProject.name else "${dependencyProject.name}-dont-use-this-unless-you-know-what-you-are-doing"
            is OSSLibraryFeature, is OSSApplicationAndLibraryFeature -> dependencyProject.name
            else -> throw GradleException("Don't know how to express dependency on project ${dependency.name} in $name")
        }
    } else {
        dependency.name
    }
    val exclusions = if (dependency is ModuleDependency) {
        (globalExcludeRules + dependency.excludeRules).toSet().map { it.group to it.module }
    } else {
        emptyList()
    }
    PomDependency(dependency.group, artifactId, dependency.version, exclusions)
}

private fun appendPomDependencies(topLevel: Node, dependencies: List<PomDependency>) {
    val dependenciesNode = topLevel.appendNode("dependencies")
    dependencies.forEach { dependency ->
        val node = dependenciesNode.appendNode("dependency")
        node.appendNode("groupId", dependency.groupId)
        node.appendNode("artifactId", dependency.artifactId)
        node.appendNode("version", dependency.version)
        node.appendNode("scope", "compile")
        if (dependency.exclusions.isNotEmpty()) {
            val exclusionsNode = node.appendNode("exclusions")
            dependency.exclusions.forEach { (group, module) ->
                val exclusionNode = exclusionsNode.appendNode("exclusion")
                exclusionNode.appendNode("groupId", group)
                exclusionNode.appendNode("artifactId", module)
            }
        }
    }
}

internal fun Project.createShadowedObfuscatedJarPublication(task: TaskProvider<out Jar>, artifactIdentifier: String) {
    publishJar(ArtifactPublishingConfigurer(project, artifactIdentifier, task, SpecmaticArtifactType.OBFUSCATED_FAT))
    createConfigurationAndAddArtifacts(task)
}

internal fun Project.createShadowedUnobfuscatedJarPublication(
    task: TaskProvider<out Jar>,
    artifactIdentifier: String,
): NamedDomainObjectProvider<MavenPublication> {
    val publication = publishJar(ArtifactPublishingConfigurer(project, artifactIdentifier, task, SpecmaticArtifactType.ORIGINAL_FAT))
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

    fun artifactType(): SpecmaticArtifactType
}

class ArtifactPublishingConfigurer(
    private val project: Project,
    private val artifactIdentifier: String,
    private val task: TaskProvider<out Jar>,
    private val artifactType: SpecmaticArtifactType,
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

    override fun artifactType(): SpecmaticArtifactType = artifactType
}

private fun Project.publishJar(configurer: PublishingConfigurer): NamedDomainObjectProvider<MavenPublication> {
    val versionInfo = versionInfo()
    val commercial = isCommercial()

    return publishing.publications.register(configurer.name(), MavenPublication::class.java) {
        pom.packaging = "jar"
        pom {
            versionInfo.addToPom(this, configurer.artifactType())
        }

        configurer.configure(this)

        if (commercial) {
            addCommercialPomFragments()
        }
    }
    }

internal fun Project.isOSS(): Boolean {
    val specmaticExtension = project.rootProject.specmaticExtension()

    return specmaticExtension.projectConfigurations[project] is OSSLibraryFeature ||
        specmaticExtension.projectConfigurations[project] is OSSApplicationFeature ||
        specmaticExtension.projectConfigurations[project] is OSSApplicationAndLibraryFeature
}

internal fun Project.isCommercial(): Boolean = !isOSS()

private fun MavenPublication.addCommercialPomFragments() {
    pom {
        licenses {
            license {
                name.set("Specmatic End User License Agreement")
                url.set("https://specmatic.io/eula/")
            }
        }

        developers {
            developer {
                organization.set("Specmatic")
                organizationUrl.set("https://specmatic.io")
            }
        }

        scm {
            url.set("https://github.com/specmatic")
            issueManagement {
                url.set("https://support.specmatic.io")
            }
        }
    }
}

private fun Project.createConfigurationAndAddArtifacts(taskProvider: TaskProvider<out Jar>) {
    val shadowOriginalJarConfig = configurations.create(taskProvider.name)
    pluginInfo("Adding output of ${taskProvider.get().path} to artifact named ${shadowOriginalJarConfig.name}")
    artifacts.add(shadowOriginalJarConfig.name, taskProvider)
}
