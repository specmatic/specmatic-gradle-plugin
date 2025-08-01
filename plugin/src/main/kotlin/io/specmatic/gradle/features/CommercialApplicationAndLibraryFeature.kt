package io.specmatic.gradle.features

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import io.specmatic.gradle.jar.publishing.SHADOW_OBFUSCATED_JAR
import io.specmatic.gradle.jar.publishing.createObfuscatedOriginalJar
import io.specmatic.gradle.jar.publishing.createObfuscatedOriginalJarPublication
import io.specmatic.gradle.jar.publishing.createObfuscatedShadowJar
import io.specmatic.gradle.jar.publishing.createShadowedObfuscatedJarPublication
import io.specmatic.gradle.jar.publishing.createShadowedUnobfuscatedJarPublication
import io.specmatic.gradle.jar.publishing.createUnobfuscatedJarPublication
import io.specmatic.gradle.jar.publishing.createUnobfuscatedShadowJar
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.TaskProvider

class CommercialApplicationAndLibraryFeature(project: Project) :
    BaseDistribution(project),
    ShadowingFeature,
    ObfuscationFeature,
    ApplicationFeature,
    GithubReleaseFeature,
    DockerBuildFeature {
    override var mainClass: String = ""

    override fun applyToProject() {
        super.applyToProject()
        setupLogging()

        if (this.isGradlePlugin) {
            return
        }

        project.plugins.withType(JavaPlugin::class.java) {
            val obfuscatedOriginalJar = project.createObfuscatedOriginalJar(proguardExtraArgs)
            val unobfuscatedShadowJar = project.createUnobfuscatedShadowJar(shadowActions, shadowPrefix, true)
            val obfuscatedShadowJar =
                project.createObfuscatedShadowJar(obfuscatedOriginalJar, shadowActions, shadowPrefix, true)

            project.createRunObfuscatedFatJarTask(
                obfuscatedShadowJar,
                mainClass,
            )

            project.createRunFatJarTask(unobfuscatedShadowJar, mainClass)

            project.plugins.withType(MavenPublishPlugin::class.java) {
                project.createUnobfuscatedJarPublication(
                    "${project.name}-dont-use-this-unless-you-know-what-you-are-doing",
                )
                project.createShadowedObfuscatedJarPublication(
                    obfuscatedShadowJar,
                    "${project.name}-all",
                )
                project.createObfuscatedOriginalJarPublication(
                    obfuscatedOriginalJar,
                    project.name,
                )
                project.createShadowedUnobfuscatedJarPublication(
                    unobfuscatedShadowJar,
                    "${project.name}-all-debug",
                )
            }
        }
    }

    override fun shadow(prefix: String?, action: Action<ShadowJar>?) {
        super.shadow(prefix, action)
    }

    override fun obfuscate(vararg proguardExtraArgs: String?) {
        super.obfuscate(*proguardExtraArgs)
    }

    override fun githubRelease(block: GithubReleaseConfig.() -> Unit) {
        super.githubRelease(block)
    }

    override fun dockerBuild(block: DockerBuildConfig.() -> Unit) {
        super.dockerBuild {
            apply { block() }
            mainJarTaskName = SHADOW_OBFUSCATED_JAR
        }
    }
}
