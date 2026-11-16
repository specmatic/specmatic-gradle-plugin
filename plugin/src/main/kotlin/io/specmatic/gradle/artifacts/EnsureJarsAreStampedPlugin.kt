package io.specmatic.gradle.artifacts

import io.specmatic.gradle.features.ApplicationFeature
import io.specmatic.gradle.license.pluginInfo
import io.specmatic.gradle.specmaticExtension
import io.specmatic.gradle.versioninfo.versionInfo
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.jvm.tasks.Jar

class EnsureJarsAreStampedPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.afterEvaluate {
            target.tasks.withType(Jar::class.java) {
                versionInfo().addToManifest(manifest)
                if (mainclass().isNullOrEmpty()) {
                    target.pluginInfo("Ensuring that ${this.path} is stamped")
                } else {
                    manifest.attributes["Main-Class"] = mainclass()
                    target.pluginInfo("Ensuring that ${this.path} is stamped with main class ${mainclass()}")
                }

            }
        }
    }

    private fun Project.mainclass(): String? {
        val extension = this.specmaticExtension()
        val module = extension.projectConfigurations[this]
        if (module is ApplicationFeature && module.mainClass.isNotBlank()) {
            return module.mainClass
        }
        return null
    }
}
