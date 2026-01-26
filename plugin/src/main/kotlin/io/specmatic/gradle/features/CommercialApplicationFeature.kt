package io.specmatic.gradle.features

import io.specmatic.gradle.jar.publishing.SHADOW_OBFUSCATED_JAR
import io.specmatic.gradle.jar.publishing.createObfuscatedOriginalJar
import io.specmatic.gradle.jar.publishing.createObfuscatedShadowJar
import io.specmatic.gradle.jar.publishing.createShadowedObfuscatedJarPublication
import io.specmatic.gradle.jar.publishing.createShadowedUnobfuscatedJarPublication
import io.specmatic.gradle.jar.publishing.createUnobfuscatedShadowJar
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin

class CommercialApplicationFeature(project: Project) :
    BaseDistribution(project),
    ApplicationFeature,
    ShadowingFeature,
    ObfuscationFeature,
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
                project.createShadowedObfuscatedJarPublication(
                    obfuscatedShadowJar,
                    project.name,
                )
                project.createShadowedUnobfuscatedJarPublication(
                    unobfuscatedShadowJar,
                    "${project.name}-all-debug",
                )
            }
        }
    }

    override fun dockerBuild(block: DockerBuildConfig.() -> Unit) {
        super.dockerBuild {
            apply { block() }
            mainJarTaskName = SHADOW_OBFUSCATED_JAR
        }
    }
}
