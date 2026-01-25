package io.specmatic.gradle.features

import io.specmatic.gradle.jar.publishing.createObfuscatedOriginalJar
import io.specmatic.gradle.jar.publishing.createObfuscatedOriginalJarPublication
import io.specmatic.gradle.jar.publishing.createObfuscatedShadowJar
import io.specmatic.gradle.jar.publishing.createShadowedObfuscatedJarPublication
import io.specmatic.gradle.jar.publishing.createShadowedUnobfuscatedJarPublication
import io.specmatic.gradle.jar.publishing.createUnobfuscatedJarPublication
import io.specmatic.gradle.jar.publishing.createUnobfuscatedShadowJar
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin

class CommercialLibraryFeature(project: Project) :
    BaseDistribution(project),
    ObfuscationFeature,
    ShadowingFeature,
    GithubReleaseFeature {
    override fun applyToProject() {
        super.applyToProject()
        if (this.isGradlePlugin) {
            return
        }

        project.plugins.withType(JavaPlugin::class.java) {
            val obfuscatedOriginalJar = project.createObfuscatedOriginalJar(proguardExtraArgs)
            val unobfuscatedShadowJar = project.createUnobfuscatedShadowJar(shadowActions, shadowPrefix, false)
            val obfuscatedShadowJar =
                project.createObfuscatedShadowJar(
                    obfuscatedOriginalJar,
                    shadowActions,
                    shadowPrefix,
                    false,
                )
            project.plugins.withType(MavenPublishPlugin::class.java) {
                project.createUnobfuscatedJarPublication(
                    "${project.name}-dont-use-this-unless-you-know-what-you-are-doing",
                )
                project.createObfuscatedOriginalJarPublication(
                    obfuscatedOriginalJar,
                    "${project.name}-min",
                )
                project.createShadowedUnobfuscatedJarPublication(
                    unobfuscatedShadowJar,
                    "${project.name}-all-debug",
                )
                project.createShadowedObfuscatedJarPublication(
                    obfuscatedShadowJar,
                    project.name,
                )
            }
        }
    }
}
