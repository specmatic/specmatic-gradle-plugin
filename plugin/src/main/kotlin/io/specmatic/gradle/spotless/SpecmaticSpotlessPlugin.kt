package io.specmatic.gradle.spotless

import com.diffplug.gradle.spotless.BaseKotlinExtension
import com.diffplug.gradle.spotless.SpotlessExtension
import com.diffplug.gradle.spotless.SpotlessPlugin
import com.diffplug.gradle.spotless.SpotlessTaskImpl
import io.specmatic.gradle.autogen.createEditorConfigFile
import org.gradle.api.Plugin
import org.gradle.api.Project

class SpecmaticSpotlessPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.repositories.mavenCentral()
        target.plugins.apply(SpotlessPlugin::class.java)

        val createEditorConfigFileTask = target.createEditorConfigFile()

        target.plugins.withType(SpotlessPlugin::class.java) {
            val spotlessExtension = target.extensions.getByType(SpotlessExtension::class.java)

            spotlessExtension.apply {
                kotlinGradle {
                    target("**/*.kts")
                    configure(this)
                }

                kotlin {
                    target("**/*.kt")
                    targetExclude("**/gen-kt/**")
                    configure(this)
                }
                isEnforceCheck = false
            }
        }

        target.tasks.withType(SpotlessTaskImpl::class.java) {
            dependsOn(createEditorConfigFileTask)
        }
    }

    private fun configure(extension: BaseKotlinExtension) {
        extension.apply {
            ktlint("1.8.0")
        }
    }
}
