package io.specmatic.gradle.features

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import io.specmatic.gradle.jar.massage.mavenPublications
import io.specmatic.gradle.jar.publishing.createShadowedUnobfuscatedJarPublication
import io.specmatic.gradle.jar.publishing.createUnobfuscatedShadowJar
import io.specmatic.gradle.jar.publishing.forceJavadocAndSourcesJars
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin

open class OSSApplicationFeature(project: Project) :
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

            project.plugins.withType(MavenPublishPlugin::class.java) {
                project.createShadowedUnobfuscatedJarPublication(
                    unobfuscatedShadowJarTask,
                    project.name,
                )

                project.mavenPublications {
                    artifact(project.tasks.named("sourcesJar")) {
                        classifier = "sources"
                    }
                    artifact(project.tasks.named("javadocJar")) {
                        classifier = "javadoc"
                    }
                }

                signPublishTasksDependOnSourcesJar()
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
