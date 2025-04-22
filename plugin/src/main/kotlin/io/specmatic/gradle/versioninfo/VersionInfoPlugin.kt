package io.specmatic.gradle.versioninfo

import io.specmatic.gradle.autogen.createVersionInfoKotlinTask
import io.specmatic.gradle.autogen.createVersionPropertiesFileTask
import io.specmatic.gradle.license.pluginInfo
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPlugin

class VersionInfoPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.plugins.withType(JavaPlugin::class.java) {
            if (project.group.toString().isBlank()) {
                throw GradleException("Set your project group in the `gradle.properties`, not in the `build.gradle.kts` file")
            }

            if (project.version.toString().isEmpty()) {
                throw GradleException("Set your project version in the `gradle.properties`, not in the `build.gradle.kts` file")
            }

            project.pluginInfo("Configuring version properties file")

            val versionInfoForProject = project.versionInfo()

            project.createVersionInfoKotlinTask(versionInfoForProject)
            project.createVersionPropertiesFileTask(versionInfoForProject)
        }
    }
}
