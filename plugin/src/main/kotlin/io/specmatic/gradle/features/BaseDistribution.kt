package io.specmatic.gradle.features

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import io.specmatic.gradle.autogen.createJULLogForwarderClassTask
import io.specmatic.gradle.autogen.createLogbackXMLFileTask
import io.specmatic.gradle.docker.registerDockerTasks
import io.specmatic.gradle.jar.massage.mavenPublications
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin
import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven
import org.gradle.api.publish.tasks.GenerateModuleMetadata
import org.gradle.jvm.tasks.Jar
import org.gradle.kotlin.dsl.exclude
import org.gradle.plugins.signing.Sign
import org.gradlex.jvm.dependency.conflict.resolution.JvmDependencyConflictResolutionPlugin
import org.gradlex.jvm.dependency.conflict.resolution.JvmDependencyConflictsExtension

abstract class BaseDistribution(protected val project: Project) : DistributionFlavor {
    internal var isGradlePlugin = false
    internal var publicationConfigurations = mutableListOf<Action<MavenPublication>>()
    internal var githubRelease: GithubReleaseConfig = GithubReleaseConfig()
    internal var shadowActions = mutableListOf<Action<ShadowJar>>()
    internal var proguardExtraArgs = mutableListOf<String?>()
    internal var shadowPrefix = ""
    internal var vulnerableDeps = mutableListOf<String?>()

    fun publish(configuration: Action<MavenPublication>) {
        this.publicationConfigurations.add(configuration)
    }

    fun vulnerableDeps(vararg deps: String?) {
        this.vulnerableDeps.addAll(deps)
    }

    fun publishGradle(configuration: Action<MavenPublication>) {
        this.isGradlePlugin = true
        this.publicationConfigurations.add(configuration)
    }

    internal open fun applyToProject() {
        // hook for any common setup
        project.plugins.apply(JavaPlugin::class.java)
        project.plugins.withType(MavenPublishPlugin::class.java) {
            project.mavenPublications {
                publicationConfigurations.forEach {
                    it.execute(this)
                }
            }
        }
    }

    protected fun signPublishTasksDependOnSourcesJar() {
        project.tasks.withType(Sign::class.java) {
            dependsOn(
                project.tasks
                    .withType(Jar::class.java)
                    .filter { it.name.lowercase().endsWith("sourcesjar") },
            )
        }

        project.tasks.withType(AbstractPublishToMaven::class.java) {
            dependsOn(
                project.tasks
                    .withType(Jar::class.java)
                    .filter { it.name.lowercase().endsWith("sourcesjar") },
            )
        }

        project.tasks.withType(GenerateModuleMetadata::class.java) {
            dependsOn(
                project.tasks
                    .withType(Jar::class.java)
                    .filter { it.name.lowercase().endsWith("sourcesjar") },
            )
        }
    }

    protected open fun shadow(prefix: String?, action: Action<ShadowJar>?) {
        if (prefix != null) {
            // check that prefix is a valid java package name
            require(prefix.matches(Regex("^[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)*$"))) { "Invalid Java package name: $prefix" }
        }
        shadowPrefix = prefix.orEmpty().trim()
        if (action != null) {
            shadowActions.add(action)
        }
    }

    protected open fun obfuscate(vararg proguardExtraArgs: String?) {
        this.proguardExtraArgs.addAll(proguardExtraArgs)
    }

    protected open fun githubRelease(block: GithubReleaseConfig.() -> Unit) {
        githubRelease = GithubReleaseConfig().apply(block)
    }

    protected open fun dockerBuild(block: DockerBuildConfig.() -> Unit) {
        this.project.registerDockerTasks(DockerBuildConfig().apply(block))
    }

    protected fun setupLogging() {
        project.configurations.named("implementation") {
            // add logback classic
            dependencies.add(project.dependencies.create("ch.qos.logback:logback-classic:1.5.18"))
            dependencies.add(project.dependencies.create("ch.qos.logback:logback-core:1.5.18"))

            // add bridges to redirect all other logging frameworks to SLF4J
            dependencies.add(project.dependencies.create("org.apache.logging.log4j:log4j-to-slf4j:2.24.3"))
            dependencies.add(project.dependencies.create("org.slf4j:jul-to-slf4j:2.0.17"))
            dependencies.add(project.dependencies.create("org.slf4j:jcl-over-slf4j:2.0.17"))
            dependencies.add(project.dependencies.create("org.slf4j:log4j-over-slf4j:2.0.17"))

            excludeLoggingFrameworks()
        }

        project.configurations.named("testImplementation") {
            excludeLoggingFrameworks()
        }

        project.plugins.withType(JvmDependencyConflictResolutionPlugin::class.java) {
            project.extensions.configure(JvmDependencyConflictsExtension::class.java) {
                logging {
                    enforceLogback("implementation")
                }
            }
        }

        project.createLogbackXMLFileTask()
        project.createJULLogForwarderClassTask()
    }

    private fun Configuration.excludeLoggingFrameworks() {
        // exclude the logging frameworks from any transitive dependencies
        exclude(group = "org.apache.logging.log4j", module = "log4j-core")
        exclude(group = "log4j", module = "log4j")
        exclude(group = "commons-logging", module = "commons-logging")
        exclude(group = "org.springframework", module = "spring-jcl")
    }
}
