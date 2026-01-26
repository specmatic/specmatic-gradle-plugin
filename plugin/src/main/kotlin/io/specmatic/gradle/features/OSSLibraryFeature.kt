package io.specmatic.gradle.features

import io.specmatic.gradle.jar.publishing.createUnobfuscatedJarPublication
import io.specmatic.gradle.jar.publishing.forceJavadocAndSourcesJars
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin

open class OSSLibraryFeature(project: Project) :
    BaseDistribution(project),
    GithubReleaseFeature {
    override fun applyToProject() {
        super.applyToProject()
        if (this.isGradlePlugin) {
            return
        }

        project.plugins.withType(JavaPlugin::class.java) {
            project.forceJavadocAndSourcesJars()

            project.plugins.withType(MavenPublishPlugin::class.java) {
                project.createUnobfuscatedJarPublication(project.name)
            }
        }
    }
}
