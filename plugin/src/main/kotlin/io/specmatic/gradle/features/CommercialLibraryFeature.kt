package io.specmatic.gradle.features

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import io.specmatic.gradle.jar.publishing.createObfuscatedOriginalJar
import io.specmatic.gradle.jar.publishing.createObfuscatedOriginalJarPublication
import io.specmatic.gradle.jar.publishing.createObfuscatedShadowJar
import io.specmatic.gradle.jar.publishing.createShadowedObfuscatedJarPublication
import io.specmatic.gradle.jar.publishing.createShadowedUnobfuscatedJarPublication
import io.specmatic.gradle.jar.publishing.createUnobfuscatedJarPublication
import io.specmatic.gradle.jar.publishing.createUnobfuscatedShadowJar
import io.specmatic.gradle.jar.publishing.generateAllJars
import org.gradle.api.Action
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

            project.plugins.withType(MavenPublishPlugin::class.java) {
                project.createObfuscatedOriginalJarPublication(
                    obfuscatedOriginalJar,
                    "${project.name}-min",
                )

                if (project.generateAllJars()) {
                    val unobfuscatedShadowJar = project.createUnobfuscatedShadowJar(shadowActions, shadowPrefix, false)
                    val obfuscatedShadowJar =
                        project.createObfuscatedShadowJar(
                            obfuscatedOriginalJar,
                            shadowActions,
                            shadowPrefix,
                            false,
                        )
                    project.createUnobfuscatedJarPublication(
                        "${project.name}-dont-use-this-unless-you-know-what-you-are-doing",
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

    override fun shadow(prefix: String?, action: Action<ShadowJar>?) {
        super.shadow(prefix, action)
    }

    override fun obfuscate(vararg proguardExtraArgs: String?) {
        super.obfuscate(*proguardExtraArgs)
    }

    override fun githubRelease(block: GithubReleaseConfig.() -> Unit) {
        super.githubRelease(block)
    }
}
