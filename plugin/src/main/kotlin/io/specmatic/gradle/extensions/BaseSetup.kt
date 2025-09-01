package io.specmatic.gradle.extensions

import io.specmatic.gradle.jar.massage.mavenPublications
import io.specmatic.gradle.jar.massage.signing
import io.specmatic.gradle.license.pluginInfo
import io.specmatic.gradle.specmaticExtension
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.kotlin.dsl.exclude
import org.gradle.plugins.signing.Sign
import org.gradle.plugins.signing.SigningPlugin

internal fun Project.baseSetup() {
    project.plugins.withType(JavaPlugin::class.java) {
        project.configurations.named("implementation") {
            project.pluginInfo(
                "Adding 'org.jetbrains.kotlin:kotlin-stdlib:${project.specmaticExtension().kotlinVersion}' to implementation configuration",
            )
            dependencies.add(
                project.dependencies.create("org.jetbrains.kotlin:kotlin-stdlib:${project.specmaticExtension().kotlinVersion}"),
            )
        }
    }

    project.configureSigning()
    project.configurePublishing()

    project.configureVulns()

    plugins.withType(SigningPlugin::class.java) {
        project.plugins.withType(MavenPublishPlugin::class.java) {
            project.mavenPublications {
                project.pluginInfo("Ensuring that ${this.name} is signed")
                signing.sign(this)
            }
        }
    }

    project.tasks.withType(PublishToMavenRepository::class.java) {
        dependsOn(project.tasks.withType(Sign::class.java))
    }
}

private fun Project.configureVulns() {
    project.specmaticExtension().projectConfigurations[this]?.vulnerableDeps?.forEach {
        if (it != null) {
            configurations.named("implementation") {
                dependencies.add(
                    project.dependencies.create(it),
                )

                val (groupId, artifactId, version) = it.split(":")
                exclude(group = groupId, module = artifactId)
            }
        }
    }
}
