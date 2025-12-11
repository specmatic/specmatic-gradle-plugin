package io.specmatic.gradle.features

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import io.specmatic.gradle.jar.publishing.createShadowedUnobfuscatedJarPublication
import io.specmatic.gradle.jar.publishing.createUnobfuscatedJarPublication
import io.specmatic.gradle.jar.publishing.createUnobfuscatedShadowJar
import io.specmatic.gradle.jar.publishing.forceJavadocAndSourcesJars
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin

open class OSSApplicationAndLibraryFeature(project: Project) :
    BaseDistribution(project),
    ApplicationFeature,
    DockerBuildFeature,
    ShadowingFeature,
    GithubReleaseFeature {
    override var mainClass: String = ""

    override fun applyToProject() {
        super.applyToProject()
        setupLogging()

        if (this.isGradlePlugin) {
            return
        }

        project.plugins.withType(JavaPlugin::class.java) {
            project.forceJavadocAndSourcesJars()
            val unobfuscatedShadowJarTask = project.createUnobfuscatedShadowJar(shadowActions, shadowPrefix, true)

            project.createRunFatJarTask(unobfuscatedShadowJarTask, mainClass)

            project.plugins.withType(MavenPublishPlugin::class.java) {
                project.createUnobfuscatedJarPublication(
                    project.name,
                )

                val shadowJarPublication =
                    project.createShadowedUnobfuscatedJarPublication(
                        unobfuscatedShadowJarTask,
                        "${project.name}-all",
                    )

                shadowJarPublication.configure {
                    configureSourcesAndJavadocPublishing(project)
                }
            }
        }
    }

    override fun shadow(prefix: String?, action: Action<ShadowJar>?) {
        super.shadow(prefix, action)
    }

    override fun githubRelease(block: GithubReleaseConfig.() -> Unit) {
        super.githubRelease(block)
    }

    override fun dockerBuild(block: DockerBuildConfig.() -> Unit) {
        super.dockerBuild {
            apply { block() }
            mainJarTaskName = "unobfuscatedShadowJar"
        }
    }
}

internal fun MavenPublication.configureSourcesAndJavadocPublishing(project: Project) {
    artifact(project.tasks.named("sourcesJar")) {
        classifier = "sources"
    }
    artifact(project.tasks.named("javadocJar")) {
        classifier = "javadoc"
    }
}
