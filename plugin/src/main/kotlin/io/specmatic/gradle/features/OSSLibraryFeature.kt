package io.specmatic.gradle.features

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import io.specmatic.gradle.jar.publishing.createUnobfuscatedJarPublication
import io.specmatic.gradle.jar.publishing.forceJavadocAndSourcesJars
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin

open class OSSLibraryFeature(project: Project) :
    BaseDistribution(project),
    GithubReleaseFeature {
    override fun applyToProject() {
        super.applyToProject()
        if (this.isGradlePlugin) {
            signPublishTasksDependOnSourcesJar()
            return
        }

        project.plugins.withType(JavaPlugin::class.java) {
            project.forceJavadocAndSourcesJars()

            project.plugins.withType(MavenPublishPlugin::class.java) {
                project.createUnobfuscatedJarPublication(project.name)
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
}
